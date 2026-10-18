package com.marketplace.lending;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 8 (ADR-0004): the loan machine's reads — the overlap query (the
 * machine's own 409 before the EXCLUDE constraint's raw violation; the
 * same rule twice: the query for the message, the constraint for the
 * race), the settlement listener's lookup, and the two history pages.
 *
 * <p><b>The self-exclusion law (measured 2026-10-18, the W6 journey's own
 * CI):</b> the APPROVE edge runs {@code loan.approve()} (the managed
 * entity's dirty APPROVED status) BEFORE the overlap count — and a JPQL
 * count over Loan triggers Hibernate's documented pre-query AUTO flush,
 * so the just-approved row was already visible to the query and the count
 * included the loan ITSELF: every real-HTTP approval answered 409
 * "overlaps another live hold" against no competing hold at all (the
 * unit tests mocked the count to 0 and the dispute journey walked the
 * entity directly — the service edge was never measured against a real
 * database). The query now takes the candidate's own id and excludes it;
 * the REQUEST edge passes {@code null} (its loan is not saved yet —
 * nothing of its own exists to exclude).
 */
public interface LoanRepository extends JpaRepository<Loan, UUID> {

    @Query("""
            select count(l) from Loan l
            where l.productId = :productId
              and l.status in (com.marketplace.lending.LoanStatus.APPROVED,
                               com.marketplace.lending.LoanStatus.ACTIVE,
                               com.marketplace.lending.LoanStatus.RETURN_REQUESTED,
                               com.marketplace.lending.LoanStatus.DISPUTED)
              and l.startAt < :endAt
              and l.endAt > :startAt
              and (:selfId is null or l.id <> :selfId)
            """)
    long countLiveOverlap(@Param("selfId") UUID selfId,
                          @Param("productId") UUID productId,
                          @Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

    Optional<Loan> findByPaymentIntentId(UUID paymentIntentId);

    Page<Loan> findByBorrowerIdOrderByCreatedAtDescIdDesc(UUID borrowerId, Pageable pageable);

    Page<Loan> findByOwnerIdOrderByCreatedAtDescIdDesc(UUID ownerId, Pageable pageable);
}
