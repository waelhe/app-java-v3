package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * The store-categories dictionary's data-operation surface — the A-17/C.7
 * closing increment. The V116 contract pinned the words «الصفوف تصل كعمليات
 * بيانات» (rows arrive as data operations), and this service is that
 * operation: {@link ProductTypeService} verbatim in shape, because both are
 * reference-data dictionaries with the same lifecycle (register/update/
 * withdraw behind the ADMIN gate, a configuration read for the writing
 * roles).
 *
 * <p><b>Code identity:</b> {@code uq_store_categories_code} is GLOBAL —
 * unlike {@code uq_product_types_code_live}, a soft-deleted category KEEPS
 * its code reserved (the V116 identity stance: the value the products FK
 * references is never recycled). The service's {@code findByCode} precheck
 * answers the friendly duplicate message for LIVE rows; the database
 * constraint stays the race-safe authority for codes reserved by withdrawn
 * rows (the V236 documented division of labor).
 */
@Service
public class StoreCategoryService {
    private final StoreCategoryRepository repository;
    public StoreCategoryService(StoreCategoryRepository repository) { this.repository = repository; }

    /** The configuration read for the writing roles (product registration picks a code from it). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<StoreCategory> listForConfiguration() { return repository.findAllByOrderByPositionAsc(); }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public StoreCategory get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("StoreCategory", id));
    }

    /** Read one dictionary item for the same provider/admin roles as the configuration roster. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public StoreCategory getForConfiguration(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("StoreCategory", id));
    }

    @Observed(name = "catalog.store-category.register")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public StoreCategory register(String code, String nameEn, String nameAr, Integer position) {
        StoreCategory candidate = StoreCategory.register(code, nameEn, nameAr, position);
        repository.findByCode(candidate.getCode()).ifPresent(existing -> {
            throw new ConflictException("Store category code '" + candidate.getCode() + "' is already registered");
        });
        return repository.save(candidate);
    }

    @Observed(name = "catalog.store-category.update")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public StoreCategory update(UUID id, String nameEn, String nameAr, Integer position) {
        StoreCategory category = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StoreCategory", id));
        category.update(nameEn, nameAr, position);
        return repository.save(category);
    }

    /**
     * Withdraws one dictionary row — the Hibernate 7 {@code @SoftDelete}
     * write (the row keeps its Envers trail and its globally reserved code;
     * products already registered under the code are untouched, the FK is
     * by code, not by row).
     */
    @Observed(name = "catalog.store-category.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID id) {
        StoreCategory category = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StoreCategory", id));
        repository.delete(category);
    }
}
