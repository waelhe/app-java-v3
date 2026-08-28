package com.marketplace.notifications;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.email.EmailService;
import com.marketplace.shared.email.EmailSendException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EmailNotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USER_EMAIL = "user@test.com";

    private UserLookupPort mockUserLookup() {
        UserLookupPort lookup = mock(UserLookupPort.class);
        when(lookup.findById(USER_ID)).thenReturn(Optional.of(
                new UserSummary(USER_ID, USER_EMAIL, "Test", "USER", Instant.now(), Instant.now())));
        return lookup;
    }

    @Test
    void sendEmailDelegatesToEmailService() {
        EmailService emailService = mock(EmailService.class);
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verify(emailService).send(eq(USER_EMAIL), eq("Test Subject"), eq("email/template"), anyMap());
    }

    @Test
    void sendEmailNoOpWhenEmailServiceNotPresent() {
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.empty(), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verifyNoInteractions(userLookupPort);
    }

    @Test
    void sendEmailNoOpWhenUserNotFound() {
        EmailService emailService = mock(EmailService.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        when(userLookupPort.findById(USER_ID)).thenReturn(Optional.empty());
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void sendEmailPropagatesExceptionOnFailure() {
        EmailService emailService = mock(EmailService.class);
        doThrow(new EmailSendException("SMTP error", new RuntimeException()))
                .when(emailService).send(anyString(), anyString(), anyString(), anyMap());
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        assertThatThrownBy(() -> service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("SMTP error");
    }

    @Test
    void sendEmailRequiresNewTransaction() throws Exception {
        EmailNotificationService service = new EmailNotificationService(Optional.empty(), mockUserLookup());

        var method = EmailNotificationService.class.getMethod("sendEmail", UUID.class, String.class, String.class, Map.class);
        var annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }
}
