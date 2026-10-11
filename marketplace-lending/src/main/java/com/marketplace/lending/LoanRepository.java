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
            """)
    long countLiveOverlap(@Param("productId") UUID productId,
                          @Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

    Optional<Loan> findByPaymentIntentId(UUID paymentIntentId);

    Page<Loan> findByBorrowerIdOrderByCreatedAtDescIdDesc(UUID borrowerId, Pageable pageable);

    Page<Loan> findByOwnerIdOrderByCreatedAtDescIdDesc(UUID ownerId, Pageable pageable);
}
