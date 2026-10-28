package com.marketplace.disputes;

import com.marketplace.shared.api.DisputeResolution;

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

    /**
     * Row 8 (agent findings 2026-10-26 14:33 export): the release edge's
     * refund-decision fact — whether ANY of the loan's disputes was
     * resolved to the consumer's refund (applied OR still awaiting its
     * event's consumption). The lending release path holds the freeze
     * while such a decision exists: the refund branch is terminal-first,
     * so releasing first would drop the approved refund on arrival (the
     * loan no longer DISPUTED); holding lets the registry's resubmission
     * deliver the termination the decision already carries.
     */
    boolean existsByLoanIdAndResolution(UUID loanId, DisputeResolution resolution);
}
