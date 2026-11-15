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
                new MailChannelProperties(FROM));
        sender.send("member@example.com", "Booking confirmed", "email/notification",
                Map.of("message", "Booking confirmed"));
        sender.send("member@example.com", "Booking confirmed", "email/notification",
                Map.of("message", "Booking confirmed"));

        assertThat(firstKey.get()).isNotBlank().hasSize(82);
        server.verify();
    }
}
