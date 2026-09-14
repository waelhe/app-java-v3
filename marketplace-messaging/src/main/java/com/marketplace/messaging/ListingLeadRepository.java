package com.marketplace.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture). The provider inbox
 * read (paged, deterministic order) and the G-R6 daily-cap window count
 * both ride the V52 partial indexes; soft-deleted rows are filtered by
 * Hibernate's {@code @SoftDelete} on every derived query.
 */
public interface ListingLeadRepository extends JpaRepository<ListingLead, UUID> {

    /**
     * The provider's inbox — the service layer passes the sort
     * (created_at DESC, id DESC: the stable-order L32 lesson) so the
     * tiebreak is always explicit, never the database's whim.
     */
    Page<ListingLead> findByProviderId(UUID providerId, Pageable pageable);

    /** The inbox filtered by one status (NEW unread badge, ARCHIVED history). */
    Page<ListingLead> findByProviderIdAndStatus(UUID providerId, LeadStatus status, Pageable pageable);

    /** Inbox item scoped to its owner — the 403 gate's single read. */
    Optional<ListingLead> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * The G-R6 daily-cap key: how many leads this sender fingerprint
     * submitted inside the trailing 24h window.
     */
    long countBySenderIpHashAndCreatedAtAfter(String senderIpHash, Instant after);
}
