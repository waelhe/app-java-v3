package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The V230 return's line repository (the governing one-interface-per-file
 * public-repository law — same measured #624/#628 boot failure this branch's
 * Full Integration red reproduced: a package-private repository interface
 * cannot be proxied across the packaged application's split class loading).
 */
public interface OrderReturnLineRepository extends JpaRepository<OrderReturnLine, UUID> {

    List<OrderReturnLine> findByOrderReturnId(UUID orderReturnId);

    /**
     * row 6 (agent findings 2026-10-25, CWE-400): the overlap guard's ONE
     * fixed aggregate — the quantity units the counted returns (REQUESTED
     * through COMPLETED, the caller's counted set) have already ceded per
     * requested order line, restricted to the request's own item IDs. The
     * subselect on {@code OrderReturn} carries the order and status
     * predicates (the line entity carries only its return's id — the V225
     * snapshot shape), so the whole guard reads its history in a single
     * statement regardless of how many returns the order carries: the
     * per-return {@code findByOrderReturnId} walk the finding measured —
     * one query per historical return inside the locked transaction, an
     * amplification a buyer could replay with every rejected request — is
     * gone.
     */
    @Query("""
            select l.orderItemId as orderItemId,
                   sum(l.quantity) as consumedQuantity
            from OrderReturnLine l
            where l.orderItemId in :itemIds
              and l.orderReturnId in (
                  select r.id from OrderReturn r
                  where r.orderId = :orderId and r.status in :statuses)
            group by l.orderItemId
            """)
    List<ConsumedReturnQuantity> sumConsumedByOrderItemId(@Param("orderId") UUID orderId,
                                                          @Param("itemIds") Collection<UUID> itemIds,
                                                          @Param("statuses") Collection<OrderReturn.ReturnStatus> statuses);
}
