package com.marketplace.shared.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Resend HTTPS implementation of the platform's transactional email contract.
 *
 * <p>The idempotency key is a SHA-256 fingerprint of the rendered request.
 * It remains stable across retries of an unchanged payload. Resend retains
 * keys for 24 hours; this is a bounded retry safeguard, not a claim of
 * permanent exactly-once delivery. A durable event/delivery identifier is
 * still required for stronger long-term deduplication.
 */
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final RestClient restClient;
    private final TemplateEngine templateEngine;
    private final String from;

    public ResendEmailSender(RestClient restClient, TemplateEngine templateEngine,
            MailChannelProperties mailProperties) {
        this.restClient = restClient;
        this.templateEngine = templateEngine;
        this.from = mailProperties.from();
    }

    @Override
    @Retry(name = "mailSend")
    @CircuitBreaker(name = "mailSend")
    @ConcurrencyLimit(3)
    @Observed(name = "email.send")
    public void send(String to, String subject, String template, Map<String, Object> variables) {
        try {
            Context context = new Context();
            context.setVariables(variables);
            String html = templateEngine.process(template, context);

            SendEmailRequest request = new SendEmailRequest(from, List.of(to), subject, html);
            String idempotencyKey = idempotencyKey(from, to, subject, template, html);

            SendEmailResponse response = restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(SendEmailResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new IllegalStateException("Resend returned no email id");
            }

            log.info("Email accepted by Resend: providerMessageId={}, template={}",
                    response.id(), template);
        } catch (Exception ex) {
            log.error("Failed to send email via Resend: template={}", template, ex);
            throw new EmailSendException("Failed to send email via Resend", ex);
        }
    }

    private static String idempotencyKey(String from, String to, String subject,
            String template, String html) {
        String fingerprint = String.join("\n", from, to, subject, template, html);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return "marketplace-email-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record SendEmailRequest(String from, List<String> to, String subject, String html) {
    }

    private record SendEmailResponse(String id) {
    }
}
