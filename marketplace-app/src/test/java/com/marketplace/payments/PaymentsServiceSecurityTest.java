package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { PaymentsService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class PaymentsServiceSecurityTest {

    @Autowired
    private PaymentsService paymentsService;

    @MockitoBean
    private PaymentIntentRepository paymentIntentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentWebhookEventRepository webhookEventRepository;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    private PaymentWebhookSecurity paymentWebhookSecurity;

    @Test
    @WithMockUser(roles = "USER")
    void createIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.createIntent(UUID.randomUUID(), UUID.randomUUID(), "key"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void processIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.processIntent(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void confirmIntent_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.confirmIntent(UUID.randomUUID(), "ext-123"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cancelIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.cancelIntent(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void refundPayment_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.refundPayment(UUID.randomUUID()));
    }
}
