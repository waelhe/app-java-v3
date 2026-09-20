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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L44 (neighborhood community plan §5 — direct neighbor messages)
 * acceptance criterion 5's window half: spending the
 * {@code conversationCreate} window answers 429 — deterministically.
 *
 * <p><b>The instance-window proof (HTTP level, tiny instance per the
 * {@code LeadsRateLimitIntegrationTest} convention — including its 60s
 * refresh window, which CANNOT expire during the test):</b> with
 * {@code limit-for-period=2 / refresh=60s}, the first two rapid opens of
 * TWO DIFFERENT pairs pass (whichever cycle they land in starts full)
 * and the third is deterministically rejected. The rejection carries the
 * RL-001 problem+json contract — the L29 model verbatim.
 *
 * <p>The self-400 and unknown-recipient-404 halves of criterion 5 are
 * proven in {@code DirectConversationModuleIntegrationTest} over the
 * real chain; this test isolates the window arithmetic alone.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "resilience4j.ratelimiter.instances.conversationCreate.limit-for-period=2",
        "resilience4j.ratelimiter.instances.conversationCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.conversationCreate.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DirectConversationRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID callerId;

    @BeforeEach
    void seed() {
        callerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, callerId, "l44-rl-" + callerId + "@example.com",
                "l44-rl", "CONSUMER");
        org.mockito.Mockito.when(currentUserProvider.getCurrentUserId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(callerId);
    }

    /** Distinct recipients — the window bounds FREQUENCY, never pair identity (V67's own job). */
    private org.springframework.test.web.servlet.ResultActions open(UUID recipientId) throws Exception {
        return mockMvc.perform(post("/api/v1/messages/conversations/direct")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\": \"" + recipientId + "\"}"));
    }

    private UUID seededRecipient(String tag) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, userId, "l44-rl-" + tag + "-" + userId + "@example.com",
                "l44-rl-" + tag, "CONSUMER");
        return userId;
    }

    @Test
    void spentWindowAnswers429WithTheHouseProblemShape() throws Exception {
        // The first two rapid opens of two DIFFERENT pairs pass (whichever
        // absolute cycle they land in starts full); the third is THE
        // deterministic rejection — the 60s window cannot refresh during
        // the test, so no boundary arithmetic is involved at all.
        open(seededRecipient("first")).andExpect(status().isCreated());
        open(seededRecipient("second")).andExpect(status().isCreated());
        open(seededRecipient("third"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/rate-limited"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429));
    }
}
