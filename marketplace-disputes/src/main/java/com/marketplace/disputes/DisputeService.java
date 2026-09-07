package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
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

    public DisputeService(DisputeRepository repository, CurrentUserProvider currentUserProvider,
                          BookingParticipantProvider bookingParticipantProvider, PaymentRefundPort paymentRefundPort) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentRefundPort = paymentRefundPort;
    }

    @Observed(name = "dispute.open")
    public Dispute open(UUID bookingId, String reason, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        info.requireParticipant(userId);
        return repository.save(Dispute.open(bookingId, userId, reason));
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
     * L24 (feature-expansion roadmap §5): the resolve decision carries the
     * financial outcome. The OPEN -&gt; RESOLVED validation runs BEFORE any
     * money movement, so a repeated request is a 409 with zero refund
     * attempts (acceptance 1). On {@code REFUND_CONSUMER} the existing
     * refund path executes through the {@code PaymentRefundPort} module
     * contract (full refund — the decision's shape) and the movement is
     * recorded on the dispute; the ledger debit rides the refund path's
     * own {@code PaymentStateChangedEvent} (acceptance 2) and every
     * decision leaves an Envers trace on the @Audited row (acceptance 3).
     */
    @Observed(name = "dispute.resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Dispute resolve(UUID id, DisputeResolution resolution, Authentication authentication) {
        Dispute dispute = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + id));
        dispute.resolve(resolution);
        if (resolution == DisputeResolution.REFUND_CONSUMER) {
            RefundOutcome outcome = paymentRefundPort.refundForBooking(dispute.getBookingId(), null);
            dispute.recordRefund(outcome.paymentId(), outcome.refundedAmountCents());
        }
        return dispute;
    }
}
