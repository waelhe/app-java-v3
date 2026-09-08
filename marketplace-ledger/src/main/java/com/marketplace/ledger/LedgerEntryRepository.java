package com.marketplace.ledger;

import com.marketplace.shared.api.LedgerStatsPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, RevisionRepository<LedgerEntry, UUID, Integer> {
    Optional<LedgerEntry> findBySourceId(UUID sourceId);

    /** Provider statement (L20): newest-first movement page for one provider.
     * Secondary id-DESC keeps ties (the credit + commission-debit pair land
     * in one listener transaction — identical createdAt) in a stable order,
     * so pagination across a tie boundary is deterministic. */
    Page<LedgerEntry> findByProviderIdOrderByCreatedAtDescIdDesc(UUID providerId, Pageable pageable);

    /**
     * L25 (feature-expansion roadmap §5): the provider's net ledger
     * movement inside {@code [from, to)} (by {@code createdAt}, the
     * statement's own ordering key) — credits minus commission debits minus
     * refund debits, signed by entry type. Backs
     * {@link LedgerStatsPort#findNetCentsForProviderBetween} through
     * {@code LedgerStatsAdapter}. COALESCE: an empty window sums to 0, not
     * NULL.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.entryType = com.marketplace.ledger.LedgerEntryType.PAYMENT_CREDIT
                                     THEN e.amountCents ELSE -e.amountCents END), 0)
            FROM LedgerEntry e
            WHERE e.providerId = :providerId
              AND e.createdAt >= :from
              AND e.createdAt < :to
            """)
    long sumNetCentsForProviderBetween(@Param("providerId") UUID providerId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);
}
