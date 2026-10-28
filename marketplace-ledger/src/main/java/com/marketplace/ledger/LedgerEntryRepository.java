package com.marketplace.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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
     * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the
     * provider's {@code PAYMENT_CREDIT} entries — the settled credits the
     * settlement-eligibility read derives over (one per collected intent,
     * the credit's idempotency key being the intent id itself). Same
     * deterministic ordering law as the provider statement: newest first,
     * secondary id-DESC — the eligibility page over them is stable across
     * tie boundaries. Read-only; the derivation itself lives in the
     * payments module behind {@code LedgerReadPort}.
     */
    List<LedgerEntry> findByProviderIdAndEntryTypeOrderByCreatedAtDescIdDesc(UUID providerId,
                                                                            LedgerEntryType entryType);

    /**
     * R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's currency):
     * the provider's windowed net movement PER CURRENCY — credits minus
     * commission debits minus refund debits (the L25 sign convention),
     * grouped by the entry's ISO 4217 currency (the plan's own wording:
     * aggregations group by the {@code (provider, currency)} pair). The
     * pre-fix single-currency sum mixed different currencies into one
     * number and is gone with the port it served. Rows come back as
     * {@code [currency (String), netCents (Long)]} tuples ordered by
     * currency; the COALESCE per group keeps an all-debit window at its
     * honest negative, and an empty window returns no rows at all.
     */
    @Query("""
            SELECT e.currency AS currency,
                   COALESCE(SUM(CASE WHEN e.entryType = com.marketplace.ledger.LedgerEntryType.PAYMENT_CREDIT
                                     THEN e.amountCents ELSE -e.amountCents END), 0) AS netCents
            FROM LedgerEntry e
            WHERE e.providerId = :providerId
              AND e.createdAt >= :from
              AND e.createdAt < :to
            GROUP BY e.currency
            ORDER BY e.currency
            """)
    List<Object[]> sumNetCentsByCurrencyForProviderBetween(@Param("providerId") UUID providerId,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to);
}
