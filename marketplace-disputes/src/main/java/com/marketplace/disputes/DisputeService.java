package com.marketplace.disputes;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.DisputeSubject;
import com.marketplace.shared.api.LoanInfo;
import com.marketplace.shared.api.LoanPartyProvider;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DisputeService {
    private final DisputeRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final LoanPartyProvider loanPartyProvider;
    private final PaymentRefundPort paymentRefundPort;
    private final ApplicationEventPublisher eventPublisher;

    public DisputeService(DisputeRepository repository, CurrentUserProvider currentUserProvider,
                          BookingParticipantProvider bookingParticipantProvider,
                          LoanPartyProvider loanPartyProvider, PaymentRefundPort paymentRefundPort,
                          ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.loanPartyProvider = loanPartyProvider;
        this.paymentRefundPort = paymentRefundPort;
        this.eventPublisher = eventPublisher;
    }

    /**
     * B-06 (compliance plan 0.7 — Modulith events.html): the dispute's
     * entry into the pipeline publishes {@link DisputeOpenedEvent} — the
     * booking subject (the V20 original; ADR-0009 generalized the event
     * into the shared vocabulary with its subject carried).
     */
    @Observed(name = "dispute.open")
    public Dispute open(UUID bookingId, String reason, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        info.requireParticipant(userId);
        Dispute saved = repository.save(Dispute.openBooking(bookingId, userId, reason));
        eventPublisher.publishEvent(new DisputeOpenedEvent(
                saved.getId(), DisputeSubject.BOOKING, bookingId, null, userId));
        return saved;
    }

    /**
     * ADR-0009 (plan D-09 closure — the ADR-0004 deferral): the loan
     * subject. The party gate is the {@link LoanPartyProvider} contract
     * (the {@code BookingParticipantProvider} twin — the module-contract
     * pair ADR-0004 named "the DisputeOpenPort seam"); the opened dispute
     * publishes the shared event whose LOAN subject the lending module's
     * freeze listener consumes (the loan's ACTIVE edges freeze through
     * {@code Loan.markDisputed()}).
     */
    @Observed(name = "dispute.open.loan")
    public Dispute openForLoan(UUID loanId, String reason, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        LoanInfo info = loanPartyProvider.getLoanInfo(loanId);
        info.requireParty(userId);
        Dispute saved = repository.save(Dispute.openLoan(loanId, userId, reason));
        eventPublisher.publishEvent(new DisputeOpenedEvent(
                saved.getId(), DisputeSubject.LOAN, null, loanId, userId));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Dispute> listForBooking(UUID bookingId, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        if (!currentUserProvider.isAdmin(authentication)) {
            info.requireParticipant(userId);
        }
        return repository.findByBookingId(bookingId);
    }

    /** ADR-0009: the loan subject's trail — the booking twin verbatim. */
    @Transactional(readOnly = true)
    public List<Dispute> listForLoan(UUID loanId, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        LoanInfo info = loanPartyProvider.getLoanInfo(loanId);
        if (!currentUserProvider.isAdmin(authentication)) {
            info.requireParty(userId);
        }
        return repository.findByLoanId(loanId);
    }

    /**
     * The amount-less resolve — the L24 call site, byte-identical (the
     * full-refund decision's shape; Track A's app-side stubs couple to it).
     *
     * <p>B-06's review fix (the app-level
     * {@code DisputeServiceSecurityTest.resolve_whenNotAdmin} red): the
     * annotations live HERE too, not only on the 4-arg chain — the
     * security gate must fire on EVERY public entry (the L24 contract:
     * "the gate fires BEFORE the refund path is ever touched"), and the
     * observation likewise: the delegation below is an INTERNAL call
     * (this.) that bypasses the proxy, so an undecorated overload had
     * neither the {@code @PreAuthorize} gate nor the
     * {@code @Observed} span — exactly the regression the security test
     * caught.
     */
    @Observed(name = "dispute.resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Dispute resolve(UUID id, DisputeResolution resolution, Authentication authentication) {
        return resolve(id, resolution, null, authentication);
    }

    /**
     * L24 (feature-expansion roadmap §5): the resolve decision carries the
     * financial outcome. The OPEN -&gt; RESOLVED validation runs BEFORE any
     * money movement, so a repeated request is a 409 with zero refund
     * attempts (acceptance 1). On {@code REFUND_CONSUMER} the existing
     * refund path executes through the {@code PaymentRefundPort} module
     * contract and the movement is recorded on the dispute; the ledger
     * debit rides the refund path's own {@code PaymentStateChangedEvent}
     * (acceptance 2) and every decision leaves an Envers trace on the
     * @Audited row (acceptance 3).
     *
     * <p>B-06 (compliance plan 0.7): the optional {@code refundAmountCents}
     * ACTIVATES the partial refund — the port has carried the capability
     * since L24 ({@code null} = full); a value is honored on
     * REFUND_CONSUMER only (a 400 otherwise — money never moves
     * implicitly), and the decision now publishes
     * {@link DisputeResolvedEvent} with the EXECUTED outcome.
     *
     * <p>ADR-0009 (the loan subject): the refund branch is BOOKING-ONLY —
     * a loan subject's REFUND_CONSUMER decision moves NO money here; the
     * lending module's resolution listener terminates the loan through its
     * own cancellation edge, whose {@code LoanCancelledEvent} drives the
     * payments module's listener (the ONE refund contract — the
     * refund-for-booking path is never re-pointed at loans, and disputes
     * never reimplements a refund).
     */
    @Observed(name = "dispute.resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Dispute resolve(UUID id, DisputeResolution resolution, Long refundAmountCents,
                           Authentication authentication) {
        if (refundAmountCents != null && resolution != DisputeResolution.REFUND_CONSUMER) {
            throw new BadRequestException("refundAmountCents is honored on REFUND_CONSUMER only, not: " + resolution);
        }
        Dispute dispute = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + id));
        dispute.resolve(resolution);
        Long refundedAmountCents = null;
        if (resolution == DisputeResolution.REFUND_CONSUMER && dispute.getSubjectType() == DisputeSubject.BOOKING) {
            RefundOutcome outcome = paymentRefundPort.refundForBooking(dispute.getBookingId(), refundAmountCents);
            dispute.recordRefund(outcome.paymentId(), outcome.refundedAmountCents());
            refundedAmountCents = outcome.refundedAmountCents();
        }
        eventPublisher.publishEvent(new DisputeResolvedEvent(id, dispute.getSubjectType(),
                dispute.getBookingId(), dispute.getLoanId(), resolution, refundedAmountCents));
        return dispute;
    }
}
