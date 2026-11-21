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
 * <p>The idempotency key rides the caller's DELIVERY KEY — the
 * notification's occurrence identity (CodeRabbit round 1: two distinct
 * occurrences that render identically must never collapse into one provider
 * idempotency record). Callers without an occurrence key fall back to a
 * SHA-256 fingerprint of the exact provider request body (from, to, subject,
 * and rendered HTML). Resend rejects a reused key with a different body.
 * Event callers therefore supply an occurrence key and keep the body stable
 * across retries. Resend retains keys for 24 hours; this is a bounded retry
 * safeguard, not a claim of permanent exactly-once delivery. A durable
 * event/delivery identifier is still required for stronger long-term
 * deduplication.
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
    public void send(String to, String subject, String template, Map<String, Object> variables) {
        send(to, subject, template, variables, null);
    }

    /**
     * CodeRabbit round 1 (major — the delivery identity): the idempotency
     * key rides the notification's OCCURRENCE identity when the caller
     * carries one, never the rendered-content fingerprint. Two distinct
     * occurrences that render identically (two messages of one
     * conversation: same subject, same template, same composed text) kept
     * collapsing into one provider idempotency record — the second email
     * silently never went out while its event completed as delivered. With
     * no caller key the content fingerprint remains the fallback (the
     * legacy callers' own contract), EXCLUDING the time-varying payload
     * fragments so a registry resubmission of the SAME event replays the
     * same key instead of double-sending a fresh one (the password-reset's
     * remaining-minutes rendered 29 then 28 across a resubmission — the
     * round-1 Minor finding). The resilience annotations live HERE (the
     * once-annotated legacy overload simply delegates into this method, so
     * the retry/circuit/observation wrap the actual network call exactly
     * once per send).
     */
    @Override
    @Retry(name = "mailSend")
    @CircuitBreaker(name = "mailSend")
    @ConcurrencyLimit(3)
    @Observed(name = "email.send")
    public void send(String to, String subject, String template, Map<String, Object> variables,
                     String deliveryKey) {
        try {
            Context context = new Context();
            context.setVariables(variables);
            String html = templateEngine.process(template, context);

            SendEmailRequest request = new SendEmailRequest(from, List.of(to), subject, html);
            String idempotencyKey = deliveryKey != null && !deliveryKey.isBlank()
                    ? deliveryKey
                    : idempotencyKey(from, to, subject, html);

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

    /**
     * The fallback fingerprint covers the complete Resend request body:
     * sender, recipient, subject, and rendered HTML.
     * This guarantees that a changed payload cannot reuse a provider key
     * (Resend returns 409 for that mismatch). Callers that need retry
     * deduplication must supply a stable occurrence key AND stable payload.
     */
    private static String idempotencyKey(String from, String to, String subject, String html) {
        String fingerprint = String.join("\n", from, to, subject, html);
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
