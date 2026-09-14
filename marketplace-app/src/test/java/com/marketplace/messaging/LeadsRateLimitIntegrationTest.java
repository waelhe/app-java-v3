package com.marketplace.messaging;

import com.marketplace.shared.api.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L34 (realestate systems plan §5 — lead capture) acceptance criterion 3:
 * spending the window answers 429 — for BOTH gates, deterministically.
 *
 * <p><b>The instance-window proof (HTTP level, tiny instance per the
 * {@code RateLimitProblemDetailIntegrationTest} convention):</b> with
 * {@code limit-for-period=2 / refresh=2s}, the first two rapid
 * distinct-IP submissions are guaranteed to pass (whichever absolute
 * refresh cycle they land in starts full — no other traffic consumes),
 * and a 429 is guaranteed to appear within the next four calls (each
 * cycle grants two permits; a one-second call span crosses at most one
 * boundary, so at most four calls can ever pass — the fifth or earlier
 * is rejected). The rejection carries the RL-001 problem+json contract.
 *
 * <p><b>The G-R6 daily-cap proof (service level, no limiter in the
 * path):</b> with {@code daily-cap-per-sender=1}, the second submission
 * from the same fingerprint raises {@link TooManyRequestsException}
 * while a distinct-IP submission immediately after succeeds — the cap is
 * per-sender, IP-specific, and provably not the instance window (the
 * service call bypasses the controller annotation entirely).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "resilience4j.ratelimiter.instances.leadCreate.limit-for-period=2",
        "resilience4j.ratelimiter.instances.leadCreate.limit-refresh-period=2s",
        "resilience4j.ratelimiter.instances.leadCreate.timeout-duration=0",
        "marketplace.messaging.leads.daily-cap-per-sender=1",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LeadsRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches NotificationPreferencesIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LeadsService leadsService;

    private UUID listingId;

    @BeforeEach
    void seed() {
        // The A1/V2 fact: the listing's provider_id IS the user id; a real
        // ACTIVE listing drives the liveness gate (no catalog mocks — the
        // CI round-1 lesson: interface mocks in the full context break
        // the controller's concrete-type injection).
        listingId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerUserId, "l34-rl-" + providerUserId + "@example.com",
                "l34-rl-" + providerUserId, "PROVIDER");
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'L34 flat', 'seed listing', 'APARTMENT', 10000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);
    }

    private ResultActions submit(String remoteAddr) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/{id}/leads", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactName": "Sami Ahmad",
                                 "contactPhone": "+963991234567",
                                 "message": "Rate limit probe"}
                                """)
                        .with(req -> {
                            req.setRemoteAddr(remoteAddr);
                            return req;
                        }));
    }

    private LeadRequest request() {
        return new LeadRequest("Sami Ahmad", "+963991234567", "Rate limit probe");
    }

    @Test
    void instanceWindowAnswers429WithTheHouseProblemShape() throws Exception {
        // The first two rapid distinct-IP submissions are guaranteed to
        // pass (whichever absolute cycle they land in starts full).
        submit("198.51.100.1").andExpect(status().isCreated());
        submit("198.51.100.2").andExpect(status().isCreated());

        // A 429 is guaranteed within the next four calls (at most one
        // refresh boundary can fall inside a ~1s call span, so at most
        // four calls can ever be permitted).
        boolean rejected = false;
        for (int i = 3; i <= 6 && !rejected; i++) {
            ResultActions result = submit("198.51.100." + i);
            if (result.andReturn().getResponse().getStatus() == 429) {
                result.andExpect(content().contentType("application/problem+json"))
                        .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                        .andExpect(jsonPath("$.title").value("Too Many Requests"))
                        .andExpect(jsonPath("$.status").value(429));
                rejected = true;
            } else {
                result.andExpect(status().isCreated());
            }
        }
        assertThat(rejected)
                .as("the leadCreate instance window must reject within six rapid calls")
                .isTrue();
    }

    @Test
    void dailyCapIsPerSenderAndIpSpecific() {
        // The G-R6 cap, proven at the service seam where the limiter is
        // not in the path: the same fingerprint's second submission in
        // the 24h window is rejected; a different fingerprint passes
        // immediately after.
        LeadResponse first = leadsService.createLead(listingId, request(), null, "198.51.100.10");
        assertThat(first.status()).isEqualTo("NEW");

        assertThatThrownBy(() -> leadsService.createLead(listingId, request(), null, "198.51.100.10"))
                .isInstanceOf(TooManyRequestsException.class);

        // IP-specific: a different sender on the same window passes.
        LeadResponse other = leadsService.createLead(listingId, request(), null, "198.51.100.11");
        assertThat(other.status()).isEqualTo("NEW");
    }
}
