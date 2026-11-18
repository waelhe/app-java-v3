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
     * Round 1 (minor — the resubmission payload): the SAME occurrence key
     * re-derived across a registry resubmission REPLAYS (the identical
     * Idempotency-Key header) even when the rendered HTML drifted (the
     * password-reset's remaining-minutes rendered 29 then 28): the key
     * names the occurrence, never the rendered instant, so one event
     * never double-sends two different emails.
     */
    @Test
    void sameDeliveryKeyAcrossADriftedRender_replaysTheSameIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        // the two renders of the SAME template drift (29 → 28 minutes left)
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/password-reset"),
                org.mockito.ArgumentMatchers.any(Context.class)))
                .thenReturn("<p>29 minutes</p>", "<p>28 minutes</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_reset\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(firstKey.get()))
                .andRespond(withSuccess("{\"id\":\"email_reset\"}", MediaType.APPLICATION_JSON));

        ResendEmailSender sender = new ResendEmailSender(builder.build(), templateEngine,
                new MailChannelProperties(null, FROM));
        String occurrenceKey = "marketplace-email-PASSWORD_RESET-member-link-1";
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("resetLink", "link-1", "expirationMinutes", 29L), occurrenceKey);
        // the resubmission of the SAME event: same key, drifted payload
        sender.send("member@example.com", "Reset Your Password", "email/password-reset",
                Map.of("resetLink", "link-1", "expirationMinutes", 28L), occurrenceKey);

        assertThat(firstKey.get()).isEqualTo(occurrenceKey);
        server.verify();
    }

    /**
     * Round 1 (minor — the fallback fingerprint): callers WITHOUT a delivery
     * key keep the fallback fingerprint, which now EXCLUDES the rendered
     * HTML — two sends whose rendered content drifts (a time-varying
     * fragment) but whose request shape is identical re-derive the SAME
     * fallback key (a replay), so the pre-fix behavior of double-sending
     * on a drifted render is gone for the legacy callers too.
     */
    @Test
    void legacyFallbackKeyExcludesTheRenderedHtml() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-resend-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TemplateEngine templateEngine = org.mockito.Mockito.mock(TemplateEngine.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("email/password-reset"),
                org.mockito.ArgumentMatchers.any(Context.class)))
                .thenReturn("<p>29 minutes</p>", "<p>28 minutes</p>");

        AtomicReference<String> firstKey = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> firstKey.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("{\"id\":\"email_x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Idempotency-Key"))
                        .isEqualTo(firstKey.get()))
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
        server.verify();
    }
}
