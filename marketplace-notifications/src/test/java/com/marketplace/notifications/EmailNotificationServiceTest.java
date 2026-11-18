package com.marketplace.notifications;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.email.EmailSender;
import com.marketplace.shared.email.EmailSendException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailNotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USER_EMAIL = "user@test.com";

    private UserLookupPort mockUserLookup() {
        UserLookupPort lookup = mock(UserLookupPort.class);
        when(lookup.findById(USER_ID)).thenReturn(Optional.of(
                new UserSummary(USER_ID, USER_EMAIL, "Test", "USER", Instant.now(), Instant.now(), null)));
        return lookup;
    }

    @Test
    void sendEmailDelegatesToEmailSender() {
        EmailSender emailService = mock(EmailSender.class);
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verify(emailService).send(eq(USER_EMAIL), eq("Test Subject"), eq("email/template"), anyMap(), any());
    }

    @Test
    void sendEmailNoOpWhenEmailSenderNotPresent() {
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.empty(), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verifyNoInteractions(userLookupPort);
    }

    @Test
    void sendEmailNoOpWhenUserNotFound() {
        EmailSender emailService = mock(EmailSender.class);
        UserLookupPort userLookupPort = mock(UserLookupPort.class);
        when(userLookupPort.findById(USER_ID)).thenReturn(Optional.empty());
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of("key", "value"));

        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void sendEmailPropagatesExceptionOnFailure() {
        EmailSender emailService = mock(EmailSender.class);
        doThrow(new EmailSendException("SMTP error", new RuntimeException()))
                .when(emailService).send(anyString(), anyString(), anyString(), anyMap(), any());
        UserLookupPort userLookupPort = mockUserLookup();
        EmailNotificationService service = new EmailNotificationService(Optional.of(emailService), userLookupPort);

        assertThatThrownBy(() -> service.sendEmail(USER_ID, "Test Subject", "email/template", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("SMTP error");
    }
}
