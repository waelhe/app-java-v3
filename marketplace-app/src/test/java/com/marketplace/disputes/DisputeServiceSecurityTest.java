package com.marketplace.disputes;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { DisputeService.class })
@EnableMethodSecurity
class DisputeServiceSecurityTest {

    @Autowired
    private DisputeService disputeService;

    @MockitoBean
    private DisputeRepository repository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    private PaymentRefundPort paymentRefundPort;

    @Test
    @WithMockUser(roles = "USER")
    void resolve_whenNotAdmin_thenAccessDenied() {
        // L24: the financial resolve stays ADMIN-only — the gate fires
        // BEFORE the refund path is ever touched.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> disputeService.resolve(UUID.randomUUID(), DisputeResolution.REFUND_CONSUMER, null));
        verifyNoInteractions(paymentRefundPort);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void resolve_whenAdmin_thenInvokes() {
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "late delivery");
        when(repository.findById(any(UUID.class))).thenReturn(Optional.of(dispute));

        Dispute result = disputeService.resolve(dispute.getId(), DisputeResolution.NO_ACTION,
                SecurityContextHolder.getContext().getAuthentication());

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        verifyNoInteractions(paymentRefundPort);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void resolve_whenAdminAndRefundConsumer_thenRefundPortExecutes() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "late delivery");
        when(repository.findById(any(UUID.class))).thenReturn(Optional.of(dispute));
        when(paymentRefundPort.refundForBooking(bookingId, null))
                .thenReturn(new RefundOutcome(paymentId, 5000L));

        Dispute result = disputeService.resolve(dispute.getId(), DisputeResolution.REFUND_CONSUMER,
                SecurityContextHolder.getContext().getAuthentication());

        assertThat(result.getRefundPaymentId()).isEqualTo(paymentId);
        verify(paymentRefundPort).refundForBooking(bookingId, null);
    }
}
