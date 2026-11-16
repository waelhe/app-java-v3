package com.marketplace.community;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4 / J09 (unified end-to-end plan §3, the journeys register row J09 — the
 * CONFIRMED GAP the register pinned: «لا جسر مقيس من منشور التوصية إلى ملف
 * المصدر وإجراءه») — the recommendation-to-source composite journey on the
 * REAL chain: the member publishes a RECOMMENDATION post carrying the REAL
 * provider id → the publish gate answers 404 for a phantom provider BEFORE
 * any write → the feed carries the post with the provider's CURRENT facts
 * (displayName + verification status, resolved at read time through the
 * shared-api port — never frozen) → the viewer crosses the bridge to the
 * SOURCE (the provider's public business page) → a malformed id answers
 * the honest 400.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecommendationSourceJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        return userId;
    }

    /** Seeds a REAL provider row — the source the bridge points at (the adapters resolve it for real). */
    private UUID seedProvider(String displayName, String status) {
        UUID providerId = UUID.randomUUID();
        jdbc.update("INSERT INTO provider_profiles (id, display_name, status, user_id, version, "
                        + "is_deleted, created_at, updated_at) VALUES (?, ?, ?, ?, 0, FALSE, now(), now())",
                providerId, displayName, status, UUID.randomUUID());
        return providerId;
    }

    /**
     * The journey's linking test (plan §5): the recommendation with its REAL
     * source → the current facts ride the feed row → the viewer opens the
     * SOURCE's own public page — the bridge is one id, one truth.
     */
    @Test
    void theRecommendationJourneyFromPostToTheRealSource() throws Exception {
        UUID providerId = seedProvider("Abu Samir Repairs", "VERIFIED");

        UUID memberId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());

        // (1) The recommendation post CARRYING the real provider id.
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"RECOMMENDATION\","
                                + "\"title\":\"Best plumber in the hood\",\"body\":\"Fixed my kitchen leak same day.\","
                                + "\"recommendedProviderId\":\"" + providerId + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recommendedProvider").doesNotExist());

        // (2) The feed carries the post with the provider's CURRENT facts —
        // resolved at read time (the write echo carries none: nothing was
        // frozen).
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recommendedProvider.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.content[0].recommendedProvider.displayName").value("Abu Samir Repairs"))
                .andExpect(jsonPath("$.content[0].recommendedProvider.verificationStatus").value("VERIFIED"));

        // (3) The viewer crosses the bridge: the SOURCE's own public page
        // opens with the same identity — one id, one truth.
        mockMvc.perform(get("/api/v1/providers/{id}/public", providerId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(providerId.toString()))
                .andExpect(jsonPath("$.displayName").value("Abu Samir Repairs"));

        // (4) The provider's truth is ITS OWN: a name change reflects on the
        // next feed read (nothing was frozen into the post).
        jdbc.update("UPDATE provider_profiles SET display_name = 'Abu Samir & Sons' WHERE id = ?", providerId);
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recommendedProvider.displayName").value("Abu Samir & Sons"));
    }

    /** The phantom-source branch: a recommendation pointing at nothing answers 404 BEFORE any write. */
    @Test
    void aRecommendationForAUnknownProviderAnswers404BeforeAnyWrite() throws Exception {
        UUID memberId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"RECOMMENDATION\","
                                + "\"title\":\"Phantom\",\"body\":\"Points at nothing.\","
                                + "\"recommendedProviderId\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwt()))
                .andExpect(status().isNotFound());

        // No post row was written by the refused recommendation.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts WHERE title = 'Phantom'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
    }

    /** The malformed-id branch: the type gate answers the honest 400 before any write. */
    @Test
    void aMalformedProviderIdAnswers400() throws Exception {
        UUID memberId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"RECOMMENDATION\","
                                + "\"title\":\"Malformed\",\"body\":\"Bad id shape.\","
                                + "\"recommendedProviderId\":\"not-a-uuid\"}")
                        .with(jwt()))
                .andExpect(status().isBadRequest());
    }
}
