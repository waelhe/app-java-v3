package com.marketplace.shared.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

class ResendEmailSenderTest {

    private static final String FROM = "Marketplace <no-reply@example.com>";

    @Test
    void sendsRenderedMessageOverHttpsWithStableIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/notification"),
                org.mockito.ArgumentMatchers.any(Context.class))).thenReturn("<p>Booking confirmed</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-resend-key"))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to[0]").value("member@example.com"))
                .andExpect(jsonPath("$.subject").value("Booking confirmed"))
                .andExpect(jsonPath("$.html").value("<p>Booking confirmed</p>"))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_test_123\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(firstKey.get()))
                .andRespond(withSuccess("{\"id\":\"email_test_123\"}", MediaType.APPLICATION_JSON));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        sender.send("member@example.com", "Booking confirmed", "email/notification",
                Map.of("message", "Booking confirmed"));
        sender.send("member@example.com", "Booking confirmed", "email/notification",
                Map.of("message", "Booking confirmed"));

        assertThat(firstKey.get()).isNotBlank().hasSize(82);
        server.verify();
    }

    /**
     * CodeRabbit round 1 (major — the delivery identity): two DISTINCT
     * occurrences that render IDENTICALLY (two messages of one conversation
     * to one recipient — same from, to, subject, template, and variables)
     * must carry DIFFERENT idempotency keys: with the pre-fix
     * rendered-content fingerprint they collapsed into one provider record
     * and the second email silently never went out. With the caller's
     * delivery keys the two sends name their own occurrences.
     */
    @Test
    void twoIdenticalRendersWithDistinctDeliveryKeys_sendTwoDistinctIdempotencyKeys() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/notification"),
                org.mockito.ArgumentMatchers.any(Context.class))).thenReturn("<p>New message</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicReference<String> secondKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_a\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> secondKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_b\"}", MediaType.APPLICATION_JSON));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        // the SAME rendered content under TWO occurrence keys — the exact
        // round-1 scenario shape
        sender.send("member@example.com", "New message", "email/notification",
                Map.of("message", "New message"),
                "marketplace-email-MESSAGE_RECEIVED-member-conv-1");
        sender.send("member@example.com", "New message", "email/notification",
                Map.of("message", "New message"),
                "marketplace-email-MESSAGE_RECEIVED-member-conv-2");

        assertThat(firstKey.get()).isEqualTo("marketplace-email-MESSAGE_RECEIVED-member-conv-1");
        assertThat(secondKey.get()).isEqualTo("marketplace-email-MESSAGE_RECEIVED-member-conv-2");
        assertThat(firstKey.get()).isNotEqualTo(secondKey.get());
        server.verify();
    }

    /**
     * Resend replays only when both the occurrence key and request body
     * remain stable. The reset event carries an immutable expiry instant,
     * so the same publication renders the identical HTML on resubmission.
     */
    @Test
    void sameDeliveryKeyAcrossAStableRender_replaysTheSameIdempotentRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        // the same event renders the same absolute expiry on every retry
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/password-reset"),
                org.mockito.ArgumentMatchers.any(Context.class)))
                .thenReturn("<p>Expires at 2026-11-21T12:30:00Z</p>", "<p>Expires at 2026-11-21T12:30:00Z</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.html").value("<p>Expires at 2026-11-21T12:30:00Z</p>"))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_reset\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.html").value("<p>Expires at 2026-11-21T12:30:00Z</p>"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(firstKey.get()))
                .andRespond(withSuccess("{\"id\":\"email_reset\"}", MediaType.APPLICATION_JSON));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        String occurrenceKey = "marketplace-email-PASSWORD_RESET-member-link-1";
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("resetLink", "opaque-link", "expiresAtUtc", "2026-11-21T12:30:00Z"), occurrenceKey);
        // the resubmission of the SAME event: same key and same provider payload
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("resetLink", "opaque-link", "expiresAtUtc", "2026-11-21T12:30:00Z"), occurrenceKey);

        assertThat(firstKey.get()).isEqualTo(occurrenceKey);
        server.verify();
    }

    @Test
    void sameDeliveryKeyWithDifferentRenderedPayloadIsRejectedByResend() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/password-reset"),
                org.mockito.ArgumentMatchers.any(Context.class)))
                .thenReturn("<p>29 minutes</p>", "<p>28 minutes</p>");

        String occurrenceKey = "marketplace-email-PASSWORD_RESET-member-opaque-digest";
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(occurrenceKey))
                .andExpect(jsonPath("$.html").value("<p>29 minutes</p>"))
                .andRespond(withSuccess("{\\"id\\":\\"email_first\\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(occurrenceKey))
                .andExpect(jsonPath("$.html").value("<p>28 minutes</p>"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\\"name\\":\\"invalid_idempotent_request\\"}"));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("expiresAtUtc", "2026-11-21T12:30:00Z"), occurrenceKey);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sender.send(
                "member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("expiresAtUtc", "2026-11-21T12:31:00Z"), occurrenceKey))
                .isInstanceOf(EmailSendException.class);

        server.verify();
    }

    /**
     * The fallback fingerprint covers the provider request body. Different
     * rendered HTML must therefore produce a different key, avoiding
     * Resend's 409 same-key/different-payload response.
     */
    @Test
    void legacyFallbackKeyIncludesRenderedPayloadWhenNoDeliveryKeyIsProvided() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/password-reset"),
                org.mockito.ArgumentMatchers.any(Context.class)))
                .thenReturn("<p>29 minutes</p>", "<p>28 minutes</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicReference<String> secondKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.html").value("<p>29 minutes</p>"))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> secondKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_x\"}", MediaType.APPLICATION_JSON));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        // no delivery key: the SAME variables map (the request shape), but
        // the RENDERED html drifts between the two sends
        java.util.Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("resetLink", "link-2");
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.copyOf(variables));
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.copyOf(variables));

        assertThat(firstKey.get()).isNotBlank().hasSize(82);
        assertThat(secondKey.get()).isNotBlank().hasSize(82).isNotEqualTo(firstKey.get());
        server.verify();
    }
}
