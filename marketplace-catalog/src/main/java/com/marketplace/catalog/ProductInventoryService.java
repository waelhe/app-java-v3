package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stage 6 (ADR-0002): the store's inventory and lifecycle writes — the
 * provider's own shelf. The ownership gate is the listing flow's exact
 * shape (the caller IS the product's provider, or an ADMIN); the state
 * machine's guards live on the entity, the audit on Envers (one revision
 * per transition), and the storefront's read side sees only what this
 * service leaves ACTIVE.
 */
@Service
public class ProductInventoryService {

    private final ProductRepository productRepository;
    private final CurrentUserProvider currentUserProvider;

    public ProductInventoryService(ProductRepository productRepository,
                                   CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Sets the shelf count (an absolute write — the restock semantics).
     * Lowering below the reserved set is refused by the entity's own
     * guard (the V172 CHECK's Java twin).
     */
    @Transactional
    public Product setStock(UUID productId, int quantity, Authentication authentication) {
        Product product = requireOwned(productId, authentication);
        product.restock(quantity);
        return productRepository.save(product);
    }

    /** The lifecycle writes — the entity guards answer the illegal edges. */
    @Transactional
    public Product suspend(UUID productId, Authentication authentication) {
        Product product = requireOwned(productId, authentication);
        product.suspend();
        return productRepository.save(product);
    }

    @Transactional
    public Product reactivate(UUID productId, Authentication authentication) {
        Product product = requireOwned(productId, authentication);
        product.reactivate();
        return productRepository.save(product);
    }

    @Transactional
    public Product archive(UUID productId, Authentication authentication) {
        Product product = requireOwned(productId, authentication);
        product.archive();
        return productRepository.save(product);
    }

    /**
     * The ownership gate (the listing flow's exact shape): the product's
     * provider or an ADMIN — anyone else answers the honest 404
     * (existence itself is not public information on a write surface).
     */
    private Product requireOwned(UUID productId, Authentication authentication) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!product.getProviderId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return product;
    }
}
