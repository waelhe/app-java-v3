package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-16 (the M2 store wave): the storefront product read's service seam —
 * the gate the architecture rule holds between the controller and the
 * Spring Data root ({@code ArchitectureRulesTest}: no controller touches
 * a repository directly). The read itself is the M1 {@code Product}
 * aggregate through B-16's dedicated {@link StorefrontProductRepository}
 * (ACTIVE-only, the query is the gate): the suspended, archived or
 * soft-deleted product answers the honest 404 here — the controller
 * maps, it never gates.
 */
@Service
public class StorefrontProductService {

    private final StorefrontProductRepository storefrontProductRepository;

    public StorefrontProductService(StorefrontProductRepository storefrontProductRepository) {
        this.storefrontProductRepository = storefrontProductRepository;
    }

    /**
     * The buyer's public product read — one ACTIVE product or the honest
     * 404 (the L31 discipline: never a silent accept).
     */
    @Transactional(readOnly = true)
    public Product activeProduct(UUID id) {
        return storefrontProductRepository.findActiveProduct(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }
}
