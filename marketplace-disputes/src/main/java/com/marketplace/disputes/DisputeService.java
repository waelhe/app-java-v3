package com.marketplace.disputes;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
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
    private final PaymentRefundPort paymentRefundPort;
    private final ApplicationEventPublisher eventPublisher;

    public DisputeService(DisputeRepository repository, CurrentUserProvider currentUserProvider,
                          BookingParticipantProvider bookingParticipantProvider, PaymentRefundPort paymentRefundPort,
                          ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentRefundPort = paymentRefundPort;
        this.eventPublisher = eventPublisher;
    }

    /**
     * B-06 (compliance plan 0.7 — Modulith events.html): the dispute's
     * entry into the pipeline now publishes {@link DisputeOpenedEvent} on
     * the module's exposed API — the module had ZERO application events
     * (the measured defect §3.4-5: the money was right, the product a
     * text field, and nobody could subscribe to either).
     */
    @Observed(name = "dispute.open")
    public Dispute open(UUID bookingId, String reason, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        info.requireParticipant(userId);
        Dispute saved = repository.save(Dispute.open(bookingId, userId, reason));
        eventPublisher.publishEvent(new DisputeOpenedEvent(saved.getId(), bookingId, userId));
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

    /**
     * The amount-less resolve — the L24 call site, byte-identical (the
     * full-refund decision's shape; Track A's app-side stubs couple to it).
     */
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
        if (resolution == DisputeResolution.REFUND_CONSUMER) {
            RefundOutcome outcome = paymentRefundPort.refundForBooking(dispute.getBookingId(), refundAmountCents);
            dispute.recordRefund(outcome.paymentId(), outcome.refundedAmountCents());
            refundedAmountCents = outcome.refundedAmountCents();
        }
        eventPublisher.publishEvent(new DisputeResolvedEvent(id, dispute.getBookingId(), resolution, refundedAmountCents));
        return dispute;
    }
}
