package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** First executable C1 increment: shared ProductType reference data; not StoreCategory. */
@Service
public class ProductTypeService {
    private final ProductTypeRepository repository;
    /** The live-references guard's own probes (the 2026-11-07 #808 round-19). */
    private final ProductRepository products;
    private final ProductTypeAttributeAssignmentRepository assignments;

    public ProductTypeService(ProductTypeRepository repository,
                              ProductRepository products,
                              ProductTypeAttributeAssignmentRepository assignments) {
        this.repository = repository;
        this.products = products;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<ProductType> listForConfiguration() { return repository.findAllByOrderByCodeAsc(); }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public ProductType get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
    }

    @Observed(name = "catalog.product-type.register")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductType register(String code, String nameEn, String nameAr, String description) {
        ProductType candidate = ProductType.create(code, nameEn, nameAr, description);
        repository.findByCode(candidate.getCode()).ifPresent(existing -> {
            throw new ConflictException("Product type code '" + candidate.getCode() + "' is already registered");
        });
        return repository.save(candidate);
    }

    @Observed(name = "catalog.product-type.update")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductType update(UUID id, String nameEn, String nameAr, String description) {
        ProductType type = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
        type.update(nameEn, nameAr, description);
        return repository.save(type);
    }

    @Observed(name = "catalog.product-type.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID id) {
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the REST delete's own law — the bare
        // find-and-delete let a used type go soft-deleted while live
        // products still referenced its schema (the GraphQL surface's
        // deleteProductType owns the full B21 discipline: the write lock
        // BEFORE the live-references guard, the classified refusal, no
        // cascade — the references own their own lifecycle). The locked
        // read serializes against the concurrent create's FOR-SHARE
        // (the same pairing), and the guard answers the same classified
        // conflict vocabulary this service's own REST shape owns.
        ProductType type = id == null ? null : repository.findByIdForUpdate(id).orElse(null);
        if (type == null) {
            throw new ResourceNotFoundException("ProductType", id);
        }
        if (products.existsByProductTypeId(id) || assignments.existsByProductTypeId(id)) {
            throw new ConflictException(
                    "A product type with live product or attribute-assignment references cannot be deleted");
        }
        repository.delete(type);
    }
}
