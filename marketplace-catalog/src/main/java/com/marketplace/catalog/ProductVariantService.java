package com.marketplace.catalog;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0012 (the merchandise model's variant depth): the seller's variant
 * write/read surface — the {@code ProductService}/{@code
 * ProductInventoryService} shapes verbatim, composed for the variant.
 *
 * <p><b>The gates (the house shape):</b> every write and read gates on
 * the OWNING PRODUCT first — the caller IS the product's provider, or an
 * ADMIN; anyone else answers the honest 404 (existence itself is not
 * public information on this surface, the R5 privacy posture). The SKU
 * namespace is the seller's own ({@code findByProviderIdAndSku} answers
 * the friendly 409; the V219 unique index is the race backstop the
 * global handler maps). The provider and currency are copied from the
 * owning product inside the same transaction — never client-supplied
 * (the ADR's decision 3, the V184 {@code orders.seller_id} precedent).
 *
 * <p>The commands-not-reads observation policy: the registration is the
 * one observed command ({@code catalog.variant.register}); the reads and
 * the inventory/lifecycle writes stay unobserved — the {@code Product}
 * root's own exact treatment (the {@code ObservationCoverageFilesTest}
 * pin discipline).
 */
@Service
public class ProductVariantService {

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CurrentUserProvider currentUserProvider;

    public ProductVariantService(ProductVariantRepository variantRepository,
                                 ProductRepository productRepository,
                                 CurrentUserProvider currentUserProvider) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Registers one variant under an owned product — the seller's write
     * path. The product must exist and be the caller's (or the caller is
     * an ADMIN); the SKU must be free in the seller's own namespace.
     */
    @Observed(name = "catalog.variant.register")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant register(UUID productId, RegisterVariantCommand command,
                                   Authentication authentication) {
        Product product = requireOwnedProduct(productId, authentication);
        UUID providerId = product.getProviderId();
        variantRepository.findByProviderIdAndSku(providerId, command.sku()).ifPresent(existing -> {
            throw new ConflictException("SKU '" + command.sku() + "' is already used by variant "
                    + existing.getId() + " in this seller's namespace");
        });
        ProductVariant variant = ProductVariant.register(product, command.sku(),
                command.name(), command.priceMinor());
        return variantRepository.save(variant);
    }

    /** The owner's roster read — the product's live variants (Sku order, the index's order). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<ProductVariant> listOwnedByProduct(UUID productId, Authentication authentication) {
        requireOwnedProduct(productId, authentication);
        return variantRepository.findByProductIdOrderBySkuAsc(productId);
    }

    /** The owner's read — a stranger's read answers the honest 404 (the R5 posture). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant getOwnedVariant(UUID variantId, Authentication authentication) {
        return requireOwnedVariant(variantId, authentication);
    }

    /** The shelf write — the absolute stock count (the {@code setStock} twin). */
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant setStock(UUID variantId, int quantity, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.restock(quantity);
        return variantRepository.save(variant);
    }

    /** The availability writes — the entity guards answer the illegal edges. */
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant suspend(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.suspend();
        return variantRepository.save(variant);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant reactivate(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.reactivate();
        return variantRepository.save(variant);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant archive(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.archive();
        return variantRepository.save(variant);
    }

    /**
     * The owning-product gate (the {@code ProductInventoryService.requireOwned}
     * shape verbatim): the product's provider or an ADMIN — anyone else
     * answers the honest 404.
     */
    private Product requireOwnedProduct(UUID productId, Authentication authentication) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!product.getProviderId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return product;
    }

    /** The variant's own ownership gate — rides the owning product's provider (the same shape). */
    private ProductVariant requireOwnedVariant(UUID variantId, Authentication authentication) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!variant.getProviderId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("ProductVariant", variantId);
        }
        return variant;
    }

    /**
     * The registration command — the ADR's field set: the seller's SKU,
     * the option's display name, the authoritative unit price. The
     * provider and the currency are the service's own copy from the
     * owning product (never client-supplied); the stock starts at zero
     * and arrives through the inventory surface.
     */
    public record RegisterVariantCommand(String sku, String name, long priceMinor) {
    }
}
