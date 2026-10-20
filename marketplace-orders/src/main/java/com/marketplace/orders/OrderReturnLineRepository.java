package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

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

    List<OrderReturnLine> findByOrderReturnIdIn(java.util.Collection<UUID> orderReturnIds);

    List<OrderReturnLine> findByOrderItemId(UUID orderItemId);
}
