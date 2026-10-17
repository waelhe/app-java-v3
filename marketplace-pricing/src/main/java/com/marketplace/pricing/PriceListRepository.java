package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * V223 (the Saleor fit-gap study's Phase 3): the price lists' persistence
 * port. A PUBLIC interface in its own file — exactly like every other
 * repository in the tree (the 96-file house convention). Spring Data's
 * repository proxies are JDK dynamic proxies, and a non-public interface
 * can only be proxied by the classloader that defined it: the packaged
 * application's split classloading (the restart loader over the module
 * jars) does not guarantee that, and the full-application boot measured
 * exactly that failure («non-public interface is not defined by the given
 * loader»). Public and one per file is the framework's contract and the
 * repository's own convention agreeing.
 */
public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    /**
     * The resolution read: every ACTIVE, in-window list in the currency,
     * deterministic order (createdAt, id — the same total order the cart's
     * lines read in).
     */
    @Query("select l from PriceList l where l.active = true and l.currency = :currency"
            + " and (l.startsAt is null or l.startsAt <= :at) and (l.endsAt is null or l.endsAt > :at)"
            + " order by l.createdAt asc, l.id asc")
    List<PriceList> findActiveCovering(@Param("currency") String currency, @Param("at") Instant at);
}
