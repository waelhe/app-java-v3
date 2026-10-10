package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-17 (compliance plan C.7 — the M1 store root): the provider's product
 * registration and the root's own read — the M1 write path that keeps the
 * root alive (an entity that cannot be created is a schema, not a root).
 *
 * <p><b>The gates (the house shape):</b> registration is PROVIDER-scoped
 * method security with the caller's own id as the owning provider (the
 {@code CurrentUserProvider} seam — the owning provider IS the caller, so
 * no ownership argument can be forged); the category code's dictionary
 * membership is gated BEFORE any write (an unknown code answers the house
 * 404, never a silent accept — the L31 discipline); the read is the
 * owner's own read (the M1 root has no public storefront surface — that
 * arrives with the M2 wave, C.8 — so a stranger's read answers the honest
 * 404, the R5 privacy posture).
 *
 * <p>The commands-not-reads observation policy: the registration is the
 * one observed command ({@code catalog.product.register}); the read stays
 * unobserved (the {@code ObservationCoverageFilesTest} pin discipline).
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StoreCategoryRepository storeCategoryRepository;
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    public ProductService(ProductRepository productRepository,
                          StoreCategoryRepository storeCategoryRepository,
                          com.marketplace.shared.security.CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.storeCategoryRepository = storeCategoryRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Registers one product — the store root's M1 write path. The category
     * code must resolve against the live dictionary (the data-managed
     * vocabulary); the owning provider is the caller.
     */
    @Observed(name = "catalog.product.register")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public Product register(RegisterProductCommand command, Authentication authentication) {
        storeCategoryRepository.findByCode(command.storeCategoryCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "StoreCategory", command.storeCategoryCode()));
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        Product product = Product.register(command.storeCategoryCode(), command.title(),
                command.description(), command.priceMinor(), command.currency(), providerId);
        return productRepository.save(product);
    }

    /**
     * The owner's read — the M1 root has no public storefront yet (M2's
     * C.8 wave); a stranger's read answers the honest 404 (the R5 posture).
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER')")
    public Product getOwnedProduct(UUID productId, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        if (!product.getProviderId().equals(caller)) {
            // The stranger's read: existence itself is private (the R5 shape).
            throw new ResourceNotFoundException("Product", productId);
        }
        return product;
    }

    /**
     * The registration command — the M1 field set (the authoritative
     * pricing + the dictionary reference + the display text).
     */
    public record RegisterProductCommand(
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency) {
    }
}
