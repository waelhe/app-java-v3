package com.marketplace.community;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 / J05 (unified end-to-end plan §3, the journeys register row J05) — the
 * comments-and-interactions composite journey on the REAL chain: the paged
 * comments read → the comment creation (with the author's notification
 * riding AFTER_COMMIT) → the reaction lifecycle with its HONEST counters
 * (the double-thank 409, the withdraw, the live read-back) → the
 * lost-eligibility branch (a REJECTED verdict denies the write mid-journey
 * while reads stay open) → the foreign-neighborhood branch (403 on both
 * the comments and the reactions).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CommentsInteractionsJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

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
     * The journey's linking test (plan §5): the post opens → a neighbor
     * comments → the comment reads back paged → the reaction lands → the
     * counter is live → the double-reaction answers the honest 409 → the
     * withdraw reopens the voice → the REJECTED verdict (the lost
     * eligibility) denies the NEXT write while the reads stay open.
     */
    @Test
    void theCommentsJourneyWithReactionsCountersAndLostEligibility() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"QUESTION\","
                                + "\"title\":\"J05 interactions\",\"body\":\"Who repairs washers here?\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // (1) The empty comments page — the honest zero before any voice.
        UUID neighborId = asCaller(UUID.randomUUID());
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // (2) The neighbor joins the SAME neighborhood and comments.
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Abu Samir near the bakery — highly recommended.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value(postId));

        // (3) The paged read carries the comment, chronological.
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].body").value("Abu Samir near the bakery — highly recommended."));

        // (4) The reaction lands and the counter reads back live.
        mockMvc.perform(post("/api/v1/posts/{postId}/reactions", postId)
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(postId))
                .andExpect(jsonPath("$.content[0].reactionsCount").value(1))
                .andExpect(jsonPath("$.content[0].reactedByMe").value(true));

        // (5) The double-reaction — the honest 409 (one thank per member).
        mockMvc.perform(post("/api/v1/posts/{postId}/reactions", postId)
                        .with(jwt()))
                .andExpect(status().isConflict());

        // (6) The withdraw — the counter returns to zero and the voice reopens.
        mockMvc.perform(delete("/api/v1/posts/{postId}/reactions", postId)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reactionsCount").value(0))
                .andExpect(jsonPath("$.content[0].reactedByMe").value(false));

        // (7) The lost-eligibility branch: the neighbor's verification is
        // REJECTED — the NEXT comment is denied (403) while the reads
        // stay open (the D-N3 split, measured mid-journey).
        String membershipBody = mockMvc.perform(get("/api/v1/me/neighborhood")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String membershipId = idOf(membershipBody);
        mockMvc.perform(post("/api/v1/me/neighborhood/verification-requests")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("PENDING"));
        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification", membershipId)
                        .queryParam("decision", "REJECT")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("REJECTED"));
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"should be denied now\"}")
                        .with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** The foreign-neighborhood branch: neither the comment nor the reaction crosses the hood line. */
    @Test
    void aForeignNeighborCanNeitherCommentNorReact() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J05 foreign branch\",\"body\":\"Old town's own post.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        UUID foreignerId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_SUBURB + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"from another hood\"}")
                        .with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/posts/{postId}/reactions", postId)
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }

    private static String idOf(String body) {
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("j05-verification-admin"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
