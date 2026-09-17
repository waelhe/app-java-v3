package com.marketplace.community;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L42 (neighborhood community plan §5 — the posts/feed/comments layer) —
 * the acceptance criteria over the REAL chain: HTTP → the resource-server
 * chain → the membership gate (G-N3's 403) → the REAL geo service (the
 * fixed seed tree answers the existence and level gates) → V61's real
 * schema (the CHECKs, the feed index, the Envers mirrors) → the REAL
 * event publication registry → the notifications module's real listener
 * → V62's widened preference CHECK.
 *
 * <p>Every test uses its OWN random user ids (no test-level transaction —
 * each service call commits its own, the L41/SavedSearch convention), so
 * the shared container's state never bleeds between tests.
 *
 * <p>Acceptance criteria: (1) a member posts in their neighborhood ⇒ 201
 * and the post appears in the feed; (2) a non-member reads the feed ⇒ 403
 * (the mandatory negative); (3) invalid category / blank title /
 * over-limit body ⇒ 400 from the type gate before any write, and a
 * non-member comments on a post ⇒ 403; (4) a comment on a visible post ⇒
 * 201 + the POST_COMMENTED notification to the author through the REAL
 * listener; a self-comment ⇒ no notification; (5) a hidden post (state
 * planted directly) is absent from the feed and its comments are absent
 * too; (6) the author's delete hides the post from the feed (soft) while
 * the Envers trail stays; (8) deterministic pagination: two same-second
 * posts page without duplicate or gap; (9) the purge empties the texts of
 * the subject's posts/comments and keeps the structure (base + mirror);
 * plus the house guards: the 401-anonymous seam and the DB CHECK floors
 * for raw writers. Criterion 7 (the 429 window) lives in
 * {@code NeighborhoodPostRateLimitIntegrationTest} — the L34/L29 own-class
 * convention, a tiny limiter instance per the
 * {@code RateLimitProblemDetailIntegrationTest} model.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodPostModuleIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the service gates against.
    private static final String SYRIA = "11111111-1111-4111-8111-111111111101";
    private static final String RIF_DIMASHQ = "11111111-1111-4111-8111-111111111102";
    private static final String QUDSAYYA_CITY = "11111111-1111-4111-8111-111111111103";
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";
    private static final String AL_HAMAH = "11111111-1111-4111-8111-111111111106";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @Autowired
    private NeighborhoodPostService postService;

    @Autowired
    private com.marketplace.community.spi.CommunityExportAdapter exportAdapter;

    @Autowired
    private com.marketplace.community.spi.CommunityContentPurgeAdapter purgeAdapter;

    /**
     * Cross-test isolation for the shared container (the CodeRabbit
     * round-1 adoption): every test plants its own posts in the SAME
     * fixed seed node, and the feed counts posts by NEIGHBORHOOD, not by
     * author — a test that runs after another would see the earlier
     * tests' visible posts in its feed counts. Comments delete BEFORE
     * posts (V61's internal FK); the membership rows stay (they never
     * affect feed counts — the G-N1 slot is per user and every test uses
     * its own random users).
     */
    @org.junit.jupiter.api.BeforeEach
    void isolateNeighborhoodData() {
        jdbc.update("DELETE FROM post_comments");
        jdbc.update("DELETE FROM neighborhood_posts");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL chain; asserts the given status. */
    private void joinOverHttp(UUID userId, String locationId, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions postOverHttp(
            UUID userId, String locationId, String category, String title, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/neighborhood/posts")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\": \"" + locationId + "\", \"category\": \"" + category
                        + "\", \"title\": \"" + title + "\", \"body\": \"" + body + "\"}"));
    }

    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    /** A member + their stored VISIBLE post id, created over the REAL chain. */
    private record SeededPost(UUID authorId, UUID postId) {
    }

    private SeededPost visiblePostIn(String locationId) throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        joinOverHttp(authorId, locationId, 201);
        String responseBody = postOverHttp(authorId, locationId, "GENERAL",
                        "Seeded title", "Seeded body")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID postId = UUID.fromString(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(responseBody).get("id").asText());
        return new SeededPost(authorId, postId);
    }

    @Test
    void criterion1_memberPostsInTheirNeighborhood_201AndTheFeedCarriesIt() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        joinOverHttp(authorId, QUDSAYYA_OLD_TOWN, 201);

        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "GENERAL",
                        "Missing cat near the old market", "Orange tabby, answers to Simsim.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId").value(authorId.toString()))
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_OLD_TOWN))
                .andExpect(jsonPath("$.category").value("GENERAL"))
                .andExpect(jsonPath("$.status").value("VISIBLE"));

        // The feed (the caller's OWN neighborhood — the membership IS the
        // scope) carries the post.
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Missing cat near the old market"))
                .andExpect(jsonPath("$.content[0].body").value("Orange tabby, answers to Simsim."));

        // and only that member's neighborhood — a member of ANOTHER
        // neighborhood reads THEIR own feed (not this one):
        UUID otherMember = joinedMember(QUDSAYYA_SUBURB);
        asCaller(otherMember);
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void criterion2_nonMemberReadsTheFeed_is403() throws Exception {
        UUID strangerId = asCaller(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion3_typeGateAnswers400BeforeAnyWrite_andNonMemberCommentIs403() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        joinOverHttp(authorId, QUDSAYYA_OLD_TOWN, 201);

        // Invalid category — the controller's own parse (the vocabulary is
        // listed; never an enum-binding 500).
        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "SPAM", "Title", "Body")
                .andExpect(status().isBadRequest());
        // Blank category — @NotBlank at the boundary (the CodeRabbit
        // round-1 adoption: @NotNull would have admitted "" through to
        // parseCategory's null and the entity's NOT NULL would answer a
        // 500-class integrity violation instead of the clean 400).
        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "  ", "Title", "Body")
                .andExpect(status().isBadRequest());
        // Blank title.
        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "GENERAL", "  ", "Body")
                .andExpect(status().isBadRequest());
        // Over-limit title (200 is the documented bound).
        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "GENERAL",
                        "x".repeat(201), "Body")
                .andExpect(status().isBadRequest());
        // Over-limit body (2000 is the documented bound).
        postOverHttp(authorId, QUDSAYYA_OLD_TOWN, "GENERAL",
                        "Title", "x".repeat(2001))
                .andExpect(status().isBadRequest());
        // The level gate (the L41 chain, before any write).
        postOverHttp(authorId, QUDSAYYA_CITY, "GENERAL", "Title", "Body")
                .andExpect(status().isBadRequest());
        // Unknown location — the port's own 404.
        postOverHttp(authorId, UUID.randomUUID().toString(), "GENERAL", "Title", "Body")
                .andExpect(status().isNotFound());

        // Criterion 3's closing assertion: nothing was written.
        assertThat(postRows(authorId)).isZero();

        // A member of ANOTHER neighborhood commenting on this
        // neighborhood's post: 403 (the same membership gate, in the
        // post's OWN location).
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID outsider = asCaller(joinedMember(QUDSAYYA_SUBURB));
        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Not my neighborhood\"}"))
                .andExpect(status().isForbidden());
        // and a stranger (no membership at all): 403 too.
        asCaller(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"No membership\"}"))
                .andExpect(status().isForbidden());
        assertThat(commentRows(seeded.postId())).isZero();
    }

    @Test
    void criterion4_commentNotifiesTheAuthor_selfCommentDoesNot() throws Exception {
        // The REAL publication chain: the comment's transaction commits the
        // registry entry, the notifications module's real listener runs
        // after commit, and the POST_COMMENTED row + WS push land.
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID commenterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Seen it near the bakery!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value(seeded.postId().toString()))
                .andExpect(jsonPath("$.body").value("Seen it near the bakery!"));

        awaitPostCommentedNotification(seeded.authorId(), 1);
        // The WS push behind the L22 preference (default on) — observed once.
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + seeded.authorId()),
                any(com.marketplace.notifications.WebSocketNotification.class));

        // The negative (criterion 4's own words): the AUTHOR commenting on
        // their own post — no notification row, no WS push, the event's
        // registry fact still honest.
        asCaller(seeded.authorId());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Thank you all!\"}"))
                .andExpect(status().isCreated());
        awaitEventPublicationSettled();
        assertThat(postCommentedRows(seeded.authorId())).isEqualTo(1);
        // still exactly ONE WS push — the self-comment never pushed.
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + seeded.authorId()),
                any(com.marketplace.notifications.WebSocketNotification.class));
    }

    @Test
    void criterion5_hiddenPostIsAbsentFromTheFeedAndItsComments() throws Exception {
        // A member of the neighborhood, a visible post, a comment on it.
        UUID memberId = joinedMember(QUDSAYYA_OLD_TOWN);
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        asCaller(memberId);
        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Comment before hiding\"}"))
                .andExpect(status().isCreated());

        // The moderation flip is L45's alone — the criterion plants the
        // state directly (raw SQL), exactly as the plan prescribes
        // ("حالة HIDDEN مزروعة مباشرة في الاختبار").
        jdbc.update("UPDATE neighborhood_posts SET status = 'HIDDEN_BY_MODERATOR' WHERE id = ?",
                seeded.postId());

        // The feed no longer carries the post...
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // ...and the comments read answers the honest 404 — a hidden
        // post's comments are absent exactly as the post itself is.
        mockMvc.perform(get("/api/v1/posts/{id}/comments", seeded.postId()).with(jwt()))
                .andExpect(status().isNotFound());
        // ...and a NEW comment on the hidden post is 404 too (the same
        // gate — a hidden post accepts no contributions).
        mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Comment after hiding\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void criterion6_authorDeleteHidesThePost_theEnversTrailStays() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID memberId = joinedMember(QUDSAYYA_OLD_TOWN);
        asCaller(seeded.authorId());

        mockMvc.perform(delete("/api/v1/posts/{id}", seeded.postId()).with(jwt()))
                .andExpect(status().isNoContent());

        // The soft delete: the row stays (b-5's retention)...
        assertThat(totalPostRows(seeded.postId())).isEqualTo(1);
        assertThat(postRows(seeded.authorId())).isZero();
        // ...the feed stops returning it...
        asCaller(memberId);
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // ...the comments read answers 404 (the aggregate is gone from the
        // reads)...
        mockMvc.perform(get("/api/v1/posts/{id}/comments", seeded.postId()).with(jwt()))
                .andExpect(status().isNotFound());
        // ...and another member cannot delete what the reads no longer
        // see (the honest 404 — only the author, and already soft-deleted).
        asCaller(memberId);
        mockMvc.perform(delete("/api/v1/posts/{id}", seeded.postId()).with(jwt()))
                .andExpect(status().isNotFound()); // already deleted for the reads — the honest 404

        // The Envers measurement stays (V24 convention): the creation AND
        // the author's soft delete are both revisions on the real mirror.
        Integer revisions = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts_aud WHERE id = ?",
                Integer.class, seeded.postId());
        assertThat(revisions).isGreaterThanOrEqualTo(2);
    }

    @Test
    void criterion8_sameSecondPosts_pageDeterministically() throws Exception {
        // Two posts planted with the SAME created_at (raw SQL — the only
        // deterministic way to pin "two rows in one second"), paged one at
        // a time: no duplicate, no gap, the complete sort key decides.
        UUID memberId = joinedMember(QUDSAYYA_OLD_TOWN);
        String sameSecond = "2026-09-17T09:30:00Z";
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID id : new UUID[]{first, second}) {
            jdbc.update("""
                    INSERT INTO neighborhood_posts
                        (id, author_id, location_id, category, title, body, status,
                         is_deleted, version, created_at, updated_at)
                    VALUES (?, ?, ?, 'GENERAL', ?, ?, 'VISIBLE', FALSE, 0,
                            ?::timestamptz, ?::timestamptz)
                    """, id, memberId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                    "T-" + id.toString().substring(0, 8), "B", sameSecond, sameSecond);
        }

        asCaller(memberId);
        String pageOne = mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt())
                        .queryParam("page", "0").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        String pageTwo = mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt())
                        .queryParam("page", "1").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();

        String idOne = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(pageOne).get("content").get(0).get("id").asText();
        String idTwo = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(pageTwo).get("content").get(0).get("id").asText();
        // No duplicate and no gap: the two pages are exactly the two rows.
        assertThat(java.util.Set.of(idOne, idTwo))
                .containsExactlyInAnyOrder(first.toString(), second.toString());

        // The category filter axis works on the REAL schema too.
        jdbc.update("""
                INSERT INTO neighborhood_posts
                    (id, author_id, location_id, category, title, body, status,
                     is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, 'LOST_FOUND', ?, ?, 'VISIBLE', FALSE, 0,
                        now(), now())
                """, UUID.randomUUID(), memberId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                "Lost keys", "B");
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt())
                        .queryParam("category", "LOST_FOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].category").value("LOST_FOUND"));
    }

    @Test
    void criterion9_purgeEmptiesTheTexts_theStructureStays_mirrorIncluded() {
        // A member with a post (with a comment by another member) and a
        // comment of their own on someone else's post.
        UUID authorId = UUID.randomUUID();
        UUID otherMember = UUID.randomUUID();
        membershipService.join(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        membershipService.join(otherMember, UUID.fromString(QUDSAYYA_OLD_TOWN));

        com.marketplace.community.NeighborhoodPostService postService = this.postService;
        var ownPost = postService.createPost(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                PostCategory.CLASSIFIED, "Bicycle for sale", "Good condition.");
        postService.comment(otherMember, ownPost.id(), "Still available?");
        var otherPost = postService.createPost(otherMember, UUID.fromString(QUDSAYYA_OLD_TOWN),
                PostCategory.GENERAL, "Other title", "Other body.");
        postService.comment(authorId, otherPost.id(), "Nice post!");

        // b-2: the export carries the subject's posts and comments (the
        // authored personal data), including the comment on someone
        // else's post.
        assertThat(exportAdapter.exportPostsForOwner(authorId)).hasSize(1);
        assertThat(exportAdapter.exportCommentsForOwner(authorId)).hasSize(1);

        // b-3: the purge empties the authored texts — title and body are
        // NOT NULL, so the shared tombstone lands — on the base tables AND
        // the Envers mirrors; the structure stays.
        int purged = purgeAdapter.purgeAuthoredTexts(authorId);
        assertThat(purged).isGreaterThanOrEqualTo(2); // the post (base + aud) at minimum
        java.util.Map<String, Object> purgedPost = jdbc.queryForMap(
                "SELECT title, body FROM neighborhood_posts WHERE id = ?", ownPost.id());
        assertThat(purgedPost.get("title"))
                .isEqualTo(com.marketplace.shared.api.AuthoredContentPurgePort.PURGED_MARKER);
        assertThat(purgedPost.get("body"))
                .isEqualTo(com.marketplace.shared.api.AuthoredContentPurgePort.PURGED_MARKER);
        // the subject's comment on the other member's post is purged...
        Integer purgedComments = jdbc.queryForObject(
                "SELECT count(*) FROM post_comments WHERE author_id = ? AND body = ?",
                Integer.class, authorId, com.marketplace.shared.api.AuthoredContentPurgePort.PURGED_MARKER);
        assertThat(purgedComments).isEqualTo(1);
        // ...the OTHER member's rows are untouched (provenance scoping)...
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts WHERE author_id = ? AND title = ?",
                Integer.class, otherMember, "Other title")).isEqualTo(1);
        // ...the mirror carries the tombstone too (a cosmetic purge would
        // have left the original text in the history)...
        Integer purgedMirror = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts_aud WHERE id = ? AND title = ? AND body = ?",
                Integer.class, ownPost.id(),
                com.marketplace.shared.api.AuthoredContentPurgePort.PURGED_MARKER,
                com.marketplace.shared.api.AuthoredContentPurgePort.PURGED_MARKER);
        assertThat(purgedMirror).isGreaterThanOrEqualTo(1);
        // ...and the idempotence: a re-run matches nothing new for the
        // already-purged rows (exact counts, no double counting).
        int repurged = purgeAdapter.purgeAuthoredTexts(authorId);
        assertThat(repurged).isZero();

        // The membership row survives byte-identically — the L41 reasoned
        // exception, narrowed to the membership row alone.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_memberships WHERE user_id = ?",
                Integer.class, authorId)).isEqualTo(1);
    }

    @Test
    void envers_everyCommentAndPostLeavesItsTrailOnTheRealMirror() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID commenterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        String responseBody = mockMvc.perform(post("/api/v1/posts/{id}/comments", seeded.postId())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Trail proof\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID commentId = UUID.fromString(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(responseBody).get("id").asText());

        // The V24/_aud convention guard: the post and the comment both
        // left their creation revisions on the real mirrors.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts_aud WHERE id = ?",
                Integer.class, seeded.postId())).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM post_comments_aud WHERE id = ?",
                Integer.class, commentId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anonymousSurface_is401ThroughTheRealResourceServerChain() throws Exception {
        // The security seam (the §15 smoke's own probe): every feed
        // endpoint is authenticated — 401 for the anonymous caller, zero
        // security-config change for this layer.
        mockMvc.perform(get("/api/v1/neighborhood/posts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + QUDSAYYA_OLD_TOWN + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/posts/{id}/comments", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/posts/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkConstraints_backTheEnumFloorsForRawWriters() {
        UUID authorId = UUID.randomUUID();
        membershipService.join(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        var ownPost = postService.createPost(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                PostCategory.GENERAL, "Title", "Body");

        // A raw SQL writer cannot invent a category (D-N7 — RECOMMENDATION
        // is L43's widening point)...
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE neighborhood_posts SET category = 'RECOMMENDATION' WHERE id = ?",
                ownPost.id()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("chk_neighborhood_posts_category");
        // ...nor a status outside the moderation vocabulary...
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE neighborhood_posts SET status = 'DELETED' WHERE id = ?",
                ownPost.id()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("chk_neighborhood_posts_status");
    }

    // ---------- helpers ----------

    private int postRows(UUID authorId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts WHERE author_id = ? AND is_deleted = FALSE",
                Integer.class, authorId);
    }

    private int totalPostRows(UUID postId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts WHERE id = ?",
                Integer.class, postId);
    }

    private int commentRows(UUID postId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM post_comments WHERE post_id = ? AND is_deleted = FALSE",
                Integer.class, postId);
    }

    private int postCommentedRows(UUID recipientId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'POST_COMMENTED'",
                Integer.class, recipientId);
    }

    private void awaitPostCommentedNotification(UUID recipientId, int expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (postCommentedRows(recipientId) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("POST_COMMENTED notification never landed for " + recipientId);
    }

    private void awaitEventPublicationSettled() throws InterruptedException {
        // The self-comment's registry entry: published atomically with the
        // comment's commit, completed by the listener's skip (the entry's
        // completion is the observable fact that the listener ran).
        for (int i = 0; i < 100; i++) {
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE event_type LIKE '%PostCommentedEvent%' "
                            + "AND completion_date IS NULL",
                    Integer.class);
            if (pending == null || pending == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("PostCommentedEvent publication never settled");
    }
}
