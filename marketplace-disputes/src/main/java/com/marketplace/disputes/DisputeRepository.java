package com.marketplace.disputes;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID>, JpaSpecificationExecutor<Dispute>, RevisionRepository<Dispute, UUID, Integer> {
    List<Dispute> findByBookingId(UUID bookingId);

    /** ADR-0009: the loan subject's trail (the booking twin). */
    List<Dispute> findByLoanId(UUID loanId);

    /**
     * Row 15 (agent findings 2026-10-25): the freeze edge's open-state
     * fact — the lending module's dispute listener asks whether the
     * dispute it just heard opened is STILL open (the
     * {@code DisputeLookupPort} contract pair; derived existence query).
     */
    boolean existsByIdAndStatus(UUID id, DisputeStatus status);

    /**
     * Row 15: the release edge's open-state fact — whether ANOTHER open
     * dispute on the loan still requires the freeze, after the resolving
     * dispute's own id is discounted (dispute-identity idempotency).
     */
    boolean existsByLoanIdAndStatusAndIdNot(UUID loanId, DisputeStatus status, UUID id);
}
