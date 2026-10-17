package com.marketplace.orders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * A-11: the buyer's own order history, newest-first (the D-N5 complete
     * ordering key — {@code placedAt DESC, id DESC} — so pages neither
     * shuffle nor shift).
     */
    Page<Order> findByConsumerIdOrderByPlacedAtDescIdDesc(UUID consumerId, Pageable pageable);

    /**
     * Stage 6 (ADR-0002): the settlement listener's lookup — the payment's
     * COMPLETED event arrives keyed by intent; the machine's link (the
     * V184 column, unique per order) resolves the order.
     */
    Optional<Order> findByPaymentIntentId(UUID paymentIntentId);

    /**
     * The merchant surface (the Saleor study's Phase-1 slice, PR #602):
     * the seller's own sold-order history, newest-first — the buyer's
     * {@code findByConsumerIdOrderByPlacedAtDescIdDesc} twin, the same
     * D-N5 complete ordering key ({@code placedAt DESC, id DESC}) so pages
     * neither shuffle nor shift. ADR-0010: one order carries one seller,
     * so the single-column match is the whole scoping predicate.
     */
    Page<Order> findBySellerIdOrderByPlacedAtDescIdDesc(UUID sellerId, Pageable pageable);
}
