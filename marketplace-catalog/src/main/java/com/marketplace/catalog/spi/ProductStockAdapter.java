package com.marketplace.catalog.spi;

import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductVariantRepository;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProductStockPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Stage 6 (ADR-0002): the catalog module's implementation of the
 * {@link ProductStockPort} cross-module contract — the atomic inventory
 * seam the order machine rides.
 *
 * <p><b>The whole-map atomicity (the contract's own rule):</b> every
 * method joins the CALLER's transaction ({@code REQUIRED} — placement,
 * cancellation and fulfillment each carry one), writes one conditional
 * UPDATE per line, and answers {@link ConflictException} on the first
 * zero-updated row — the exception rolls the whole caller transaction
 * back with it, so a multi-line reservation is all-or-nothing and a
 * failed placement leaves no order row and no partial reservation.
 */
@Component
public class ProductStockAdapter implements ProductStockPort {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    public ProductStockAdapter(ProductRepository productRepository,
                               ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void reserve(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((productId, qty) -> {
            if (productRepository.reserve(productId, qty) == 0) {
                throw new ConflictException("Product " + productId
                        + " cannot reserve " + qty + " — not ACTIVE or the free stock is short");
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void release(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((productId, qty) -> {
            if (productRepository.release(productId, qty) == 0) {
                throw new ConflictException("Product " + productId
                        + " cannot release " + qty + " — the reserved set is short");
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void commit(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((productId, qty) -> {
            if (productRepository.commit(productId, qty) == 0) {
                throw new ConflictException("Product " + productId
                        + " cannot commit " + qty + " — the reserved set is short");
            }
        });
    }

    // ---- ADR-0012 (the cart-integration slice): the variant twins — the
    // same REQUIRED propagation (the placement's ONE transaction spans the
    // product and variant maps; a refusal of either rolls the whole
    // placement back), the same one-conditional-UPDATE-per-line write,
    // the same house 409 on the first zero-updated row.

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void reserveVariants(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((variantId, qty) -> {
            if (variantRepository.reserve(variantId, qty) == 0) {
                throw new ConflictException("ProductVariant " + variantId
                        + " cannot reserve " + qty + " — not ACTIVE or the free stock is short");
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void releaseVariants(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((variantId, qty) -> {
            if (variantRepository.release(variantId, qty) == 0) {
                throw new ConflictException("ProductVariant " + variantId
                        + " cannot release " + qty + " — the reserved set is short");
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void commitVariants(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((variantId, qty) -> {
            if (variantRepository.commit(variantId, qty) == 0) {
                throw new ConflictException("ProductVariant " + variantId
                        + " cannot commit " + qty + " — the reserved set is short");
            }
        });
    }

    // ---- The V225 RMA restock twins — the received goods go back on the
    // shelves. REQUIRED propagation (the return's completion transaction
    // spans both maps), one unconditional UPDATE per line (the physical
    // truth of goods back on the shelf).

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void restock(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((productId, qty) ->
                productRepository.restock(productId, qty));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void restockVariants(Map<java.util.UUID, Integer> quantities) {
        quantities.forEach((variantId, qty) ->
                variantRepository.restock(variantId, qty));
    }
}
