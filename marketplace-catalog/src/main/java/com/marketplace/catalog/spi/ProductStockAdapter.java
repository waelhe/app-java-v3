package com.marketplace.catalog.spi;

import com.marketplace.catalog.ProductRepository;
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

    public ProductStockAdapter(ProductRepository productRepository) {
        this.productRepository = productRepository;
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
}
