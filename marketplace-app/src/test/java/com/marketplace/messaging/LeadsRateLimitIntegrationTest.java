package com.marketplace.messaging;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L34 (realestate systems plan §5 — lead capture) acceptance criterion 3:
 * spending the window answers 429 — for BOTH gates, through the real HTTP
 * chain with the REAL catalog (the {@code
 * RateLimitProblemDetailIntegrationTest} convention: tiny instances via
 * test properties, the same official Resilience4j model the production
 * config uses).
 *
 * <p><b>The two 429s, distinguished deterministically in one sequence:</b>
 * the instance window is {@code limit-for-period=2 / refresh=2s} (N
 * permits serve exactly N calls — the third rapid call is the first
 * blocked one) and the G-R6 daily cap is 1 per sender fingerprint. (1)
 * three rapid submissions from three distinct IPs: the first two land,
 * the third is rejected — the instance window (the IPs are distinct, so
 * no cap is involved);
 * after the window refreshes, (2) two submissions from the SAME IP: the
 * first lands, the second is rejected — the daily cap, proven IP-specific
 * because a third-IP submission immediately after still succeeds on the
 * same window. Both rejections carry the RL-001 problem+json contract.
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

    @Test
    void bothGatesAnswer429WithTheHouseProblemShape() throws Exception {
        // (1) The instance window: distinct IPs, three rapid calls — the
        // third is 429 with the RL-001 problem+json contract.
        submit("198.51.100.1").andExpect(status().isCreated());
        submit("198.51.100.2").andExpect(status().isCreated());
        submit("198.51.100.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429));

        // The window refreshes (2s) — the same shape the production
        // instance carries, only faster.
        Thread.sleep(2200);

        // (2) The G-R6 daily cap: the same IP twice — the second is 429,
        // and it is the CAP because a distinct-IP submission on the same
        // window still succeeds right after (the refreshed window holds
        // two permits; the fourth call consumed one, the fifth would have
        // been permitted — only the per-sender count rejects it).
        submit("198.51.100.4").andExpect(status().isCreated());
        submit("198.51.100.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"));
        submit("198.51.100.5").andExpect(status().isCreated());
    }
}
