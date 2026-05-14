package com.marketplace.shared.email;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final TemplateEngine templateEngine = mock(TemplateEngine.class);
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, templateEngine);
    }

    @Test
    void sendSuccessfully() {
        var mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>content</html>");

        emailService.send("test@example.com", "Subject", "email/template", Map.of("key", "value"));

        verify(templateEngine).process(eq("email/template"), any(IContext.class));
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void sendThrowsEmailSendExceptionOnFailure() {
        var mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>content</html>");
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        assertThrows(EmailSendException.class,
                () -> emailService.send("test@example.com", "Subject", "email/template", Map.of()));
    }
}
