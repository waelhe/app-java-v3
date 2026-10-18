package com.marketplace.discovery;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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
 * W4 / J12 (unified end-to-end plan §3, the journeys register row J12) — the
 * discovery source-link composite journey on the REAL chain: the discovery
 * rails assemble from the OWNING modules' ports → every card's sourceId
 * OPENS in its owning surface (the LOST_FOUND card's post is the feed's
 * post — one id, one truth) → the membership-less caller answers the
 * honest empty board → a deleted post disappears from BOTH the feed and
 * the discovery rail in one decision (no two truths about visibility).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DiscoverySourceLinkJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        return userId;
    }

    /**
     * The journey's linking test (plan §5): the lost-and-found post → the
     * discovery card that carries it → the card's sourceId opens the SAME
     * post in the owning feed → one delete removes it from BOTH surfaces.
     */
    @Test
    void theDiscoveryCardOpensItsSourceAndRemovalRemovesItEverywhere() throws Exception {
        UUID memberId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());

        // (1) The owning source: a LOST_FOUND post (the state starts ACTIVE
        // by the category's own law).
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"LOST_FOUND\","
                                + "\"title\":\"Lost grey cat\",\"body\":\"Near the old market, answers to Lulu.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lostFoundState").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // (2) The discovery board: the LOST_FOUND rail carries a card whose
        // sourceId IS the post — the bridge is the id, nothing else.
        mockMvc.perform(get("/api/v1/discovery")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.row == 'LOST_FOUND')].cards[0].sourceId").value(postId))
                .andExpect(jsonPath("$[?(@.row == 'LOST_FOUND')].cards[0].sourceType").value("NEIGHBORHOOD_POST"));

        // (3) The card OPENS in the owning surface: the feed carries the
        // same id — one truth, two reads.
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(postId));

        // (4) One decision, both surfaces: the author's own delete removes
        // the post from the feed AND from the discovery rail (the adapter
        // reads the source's own visibility — no second truth).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/neighborhood/posts/{postId}", postId)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/discovery")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.row == 'LOST_FOUND')]").isEmpty());
    }

    /** The no-membership branch: the board answers the honest empty list — no scope, no cards. */
    @Test
    void theMembershiplessCallerSeesTheHonestEmptyBoard() throws Exception {
        asCaller(UUID.randomUUID());
        mockMvc.perform(get("/api/v1/discovery")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String idOf(String body) {
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
