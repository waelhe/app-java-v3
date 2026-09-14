package com.marketplace.messaging;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L34 (realestate systems plan §5 — lead capture) acceptance criterion 3:
 * spending the window answers 429 — for BOTH gates, through the real HTTP
 * chain (the {@code RateLimitProblemDetailIntegrationTest} convention:
 * tiny instances via test properties, the same official Resilience4j
 * model the production config uses).
 *
 * <p><b>The two 429s, distinguished deterministically in one sequence:</b>
 * the instance window is {@code limit-for-period=3 / refresh=2s} and the
 * G-R6 daily cap is 1 per sender fingerprint. (1) three rapid submissions
 * from three distinct IPs: the first two land, the third is rejected —
 * the instance window (the IPs are distinct, so no cap is involved);
 * after the window refreshes, (2) two submissions from the SAME IP: the
 * first lands, the second is rejected — the daily cap, proven IP-specific
 * because a third-IP submission immediately after still succeeds on the
 * same window. Both rejections carry the RL-001 problem+json contract.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "resilience4j.ratelimiter.instances.leadCreate.limit-for-period=3",
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

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    CatalogSpi catalogSpi;

    @Autowired
    private MockMvc mockMvc;

    private UUID listingId;

    @BeforeEach
    void liveListing() {
        listingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(catalogSpi.getActiveById(listingId)).thenReturn(new ProviderListingView(
                listingId, "L34 flat", "seed listing", "APARTMENT",
                100_00L, "SAR", providerId, "ACTIVE", 4, Instant.now(), Instant.now()));
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
        // window still succeeds right after.
        submit("198.51.100.4").andExpect(status().isCreated());
        submit("198.51.100.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"));
        submit("198.51.100.5").andExpect(status().isCreated());
    }
}
