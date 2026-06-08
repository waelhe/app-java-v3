package com.marketplace.shared.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

class EmailServiceTest {

    private JavaMailSender mailSender;
    private TemplateEngine templateEngine;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock();
        templateEngine = mock();
        emailService = new EmailService(mailSender, templateEngine);
    }

    @Test
    void send_sendsEmailSuccessfully() {
        MimeMessage mimeMessage = mock();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("email/welcome"), any(Context.class))).thenReturn("<html></html>");

        emailService.send("test@example.com", "Welcome", "email/welcome", Map.of("name", "Test"));

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void send_throwsEmailSendExceptionOnFailure() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail server down"));

        assertThatThrownBy(() -> emailService.send("test@example.com", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("Failed to send email");
    }
}
