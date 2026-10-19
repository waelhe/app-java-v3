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
    public ProductTypeService(ProductTypeRepository repository) { this.repository = repository; }

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
        ProductType type = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
        repository.delete(type);
    }
}
