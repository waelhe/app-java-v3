package com.marketplace.community;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2 / J02 (unified end-to-end plan §3, the journeys register row J02) — the
 * geographic-membership CONTEXT journey measured end-to-end: the membership
 * anchor (/me/neighborhood) → the REAL geo tree (the fixed seed's level-3
 * nodes) → the verification cycle (member request → the admin channel's
 * verdict) → the CONTENT SCOPE the membership owns (the feed, the posts,
 * the comments gates) → the switch and the leave, each with its measured
 * effect on what the member can and cannot see.
 *
 * <p>The journey's acceptance shape (the register's J02 row): (1) a member
 * joins a level-3 neighborhood and the context is readable; (2) the
 * verification request moves UNVERIFIED → PENDING and the admin APPROVE
 * lands VERIFIED («جار موثق»); (3) the isolation contract — a member of a
 * DIFFERENT neighborhood neither sees the post in their feed nor reads its
 * comments (the 403 of the post's own-neighborhood gate); (4) switching
 * the neighborhood atomically re-scopes the context — the same caller now
 * sees the new neighborhood's feed; (5) the REJECTED verdict denies
 * community writes while reads stay open (D-N3's own split); (6) the
 * boundary branches — a level-2 node answers 400 and an unknown id 404,
 * both before any write; (7) leaving releases the context (404 on the
 * read, 403 on the feed).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MembershipContextJourneyIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the service gates against.
    private static final String QUDSAYYA_CITY = "11111111-1111-4111-8111-111111111103";
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";
    private static final String AL_HAMAH = "11111111-1111-4111-8111-111111111106";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /**
     * The journey's linking test (plan §5): join → verify → publish → the
     * isolation (a different-neighborhood member sees nothing) → the
     * switch re-scopes → the leave releases. Every state read comes from
     * the REAL chain, never from the setup's own variables.
     */
    @Test
    void theMembershipContextJourneyFromJoinThroughScopeSwitchToLeave() throws Exception {
        UUID memberId = asCaller(UUID.randomUUID());

        // (1) Join the level-3 old town — the context lands.
        String membershipId = joinAndRead(QUDSAYYA_OLD_TOWN, memberId, "UNVERIFIED");

        // (2) The verification cycle: the member requests, the admin
        // approves — the trust mark lands on the same row.
        mockMvc.perform(post("/api/v1/me/neighborhood/verification-requests")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("PENDING"));
        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification", membershipId)
                        .queryParam("decision", "APPROVE")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("VERIFIED"));

        // (3) The member publishes a post in their own neighborhood.
        String postId = publishPost(memberId, QUDSAYYA_OLD_TOWN, "J02 anchor", "The membership context journey's own post.");

        // (4) The isolation contract: a member of a DIFFERENT neighborhood
        // neither sees the post in their feed nor reads its comments —
        // the feed is the membership's scope, and the comments gate is
        // the post's OWN neighborhood.
        UUID otherId = asCaller(UUID.randomUUID());
        joinAndRead(QUDSAYYA_SUBURB, otherId, "UNVERIFIED");
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt().jwt(j -> j.subject("other-" + otherId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId)
                        .with(jwt().jwt(j -> j.subject("other-" + otherId))))
                .andExpect(status().isForbidden());

        // (5) The switch: the other member moves to the old town and the
        // SAME feed endpoint now carries the post — the context follows
        // the active membership, atomically.
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt().jwt(j -> j.subject("other-" + otherId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt().jwt(j -> j.subject("other-" + otherId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(postId));

        // (6) The original member switches AWAY — their feed stops
        // carrying the post they authored (the scope is the membership,
        // not the authorship).
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + AL_HAMAH + "\"}")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(AL_HAMAH));
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // (7) The leave: the context is released — the read answers 404
        // and the feed answers the no-membership 403.
        mockMvc.perform(delete("/api/v1/me/neighborhood")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/neighborhood")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt().jwt(j -> j.subject("member-" + memberId))))
                .andExpect(status().isForbidden());
    }

    /**
     * The REJECTED branch (D-N3's own split): the verdict denies community
     * writes while reads stay open — and the verdict follows the member
     * across a leave/rejoin.
     */
    @Test
    void theRejectedVerdictDeniesWritesWhileReadsStayOpen() throws Exception {
        UUID rejectedId = asCaller(UUID.randomUUID());
        String membershipId = joinAndRead(QUDSAYYA_OLD_TOWN, rejectedId, "UNVERIFIED");

        mockMvc.perform(post("/api/v1/me/neighborhood/verification-requests")
                        .with(jwt().jwt(j -> j.subject("rejected-" + rejectedId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("PENDING"));
        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification", membershipId)
                        .queryParam("decision", "REJECT")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("REJECTED"));

        // Writes are denied — the publish answers the verdict's own 403.
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"should be denied\",\"body\":\"the rejected verdict denies writes\"}")
                        .with(jwt().jwt(j -> j.subject("rejected-" + rejectedId))))
                .andExpect(status().isForbidden());

        // Reads stay open — the D-N3 split.
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt().jwt(j -> j.subject("rejected-" + rejectedId))))
                .andExpect(status().isOk());
    }

    /** The boundary branches: a level-2 node answers 400 and an unknown id 404 — both before any write. */
    @Test
    void theBoundaryNodesAnswerBeforeAnyWrite() throws Exception {
        UUID callerId = asCaller(UUID.randomUUID());

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_CITY + "\"}")
                        .with(jwt().jwt(j -> j.subject("boundary-" + callerId))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + UUID.randomUUID() + "\"}")
                        .with(jwt().jwt(j -> j.subject("boundary-" + callerId))))
                .andExpect(status().isNotFound());

        // No membership row was ever written by either attempt.
        mockMvc.perform(get("/api/v1/me/neighborhood")
                        .with(jwt().jwt(j -> j.subject("boundary-" + callerId))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // The journey's own helpers — every read comes back over the REAL
    // chain (the house convention: the caller's random user id, each
    // service call its own committed transaction).
    // ------------------------------------------------------------------

    private String joinAndRead(String locationId, UUID callerId, String expectedState) throws Exception {
        var join = mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + locationId + "\"}")
                        .with(jwt().jwt(j -> j.subject("joiner-" + callerId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationState").value(expectedState))
                .andReturn();
        String body = join.getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/me/neighborhood")
                        .with(jwt().jwt(j -> j.subject("joiner-" + callerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value(expectedState));
        return objectMapperReadId(body);
    }

    private String publishPost(UUID authorId, String locationId, String title, String postBody) throws Exception {
        var created = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + locationId + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"" + title + "\",\"body\":\"" + postBody + "\"}")
                        .with(jwt().jwt(j -> j.subject("author-" + authorId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VISIBLE"))
                .andReturn();
        return objectMapperReadId(created.getResponse().getContentAsString());
    }

    private String objectMapperReadId(String body) {
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /**
     * The administrative post-processor — the REAL resource-server chain on
     * the full integration context accepts JWT authentications only (the
     * verification queue's own green pattern,
     * NeighborhoodMembershipModuleIntegrationTest): the admin JWT
     * post-processor is the one shape both layers accept.
     */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("j02-verification-admin"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
