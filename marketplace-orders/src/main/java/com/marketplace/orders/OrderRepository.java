package com.marketplace.orders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 
    /** Serialized order/delivery handoff; only the order owner mutates this association. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    // ------------------------------------------------------------------
    // Campaign packet campaign-b5-b (the Saleor orders read root): the
    // ADMIN management population's derived keyset scrolls — the
    // scrollStaffUsers precedent (Window-returning, KeysetScrollPosition/
    // Limit-taking, the static ordering baked into the method name with
    // the id tiebreaker). The population is the whole live order set (no
    // public slice exists on this management root), so Hibernate's own
    // @SoftDelete filter and the framework's own keyset predicate carry
    // the whole contract. Three owning sort keys: the placement instant
    // (the D-N5 complete ordering key's own column), the audit instant,
    // and the lifecycle state (the stored enum text).
    // ------------------------------------------------------------------

    /** The order population's CREATED_AT (placement-instant) ascending keyset scroll. */
    org.springframework.data.domain.Window<Order> findByOrderByPlacedAtAscIdAsc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);

    /** The order population's CREATED_AT (placement-instant) descending keyset scroll. */
    org.springframework.data.domain.Window<Order> findByOrderByPlacedAtDescIdDesc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);

    /** The order population's LAST_MODIFIED_AT ascending keyset scroll. */
    org.springframework.data.domain.Window<Order> findByOrderByUpdatedAtAscIdAsc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);

    /** The order population's LAST_MODIFIED_AT descending keyset scroll. */
    org.springframework.data.domain.Window<Order> findByOrderByUpdatedAtDescIdDesc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);

    /** The order population's STATUS ascending keyset scroll (the stored enum text, id tiebreaker). */
    org.springframework.data.domain.Window<Order> findByOrderByStatusAscIdAsc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);

    /** The order population's STATUS descending keyset scroll (the stored enum text, id tiebreaker). */
    org.springframework.data.domain.Window<Order> findByOrderByStatusDescIdDesc(
            org.springframework.data.domain.KeysetScrollPosition position,
            org.springframework.data.domain.Limit limit);
}
