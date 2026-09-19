package com.marketplace.community;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer)
 * — the acceptance criteria over the REAL chain: HTTP → the
 * resource-server chain (the /api/v1/admin/** rule for the moderation
 * surface) → the target gate → V64's real schema (the CHECKs, the queue
 * index, the partial unique index, the Envers mirror) → the REAL event
 * publication registry → the notifications module's real listener →
 * V65's widened preference CHECK.
 *
 * <p>Every test uses its OWN random user ids (no test-level transaction
 * — each service call commits its own, the L41/L42 convention), so the
 * shared container's state never bleeds between tests; the @BeforeEach
 * clears the queue and the posts because both reads are count-sensitive
 * (the queue is GLOBAL — one report feeds every later queue read).
 *
 * <p>Acceptance criteria: (1) a member reports a post ⇒ 201 and the
 * report lands OPEN in the administrative queue; (2) HIDE_CONTENT ⇒ the
 * post is absent from the feed, the author is notified through the REAL
 * listener, and the report closes RESOLVED — one atomic command; (3)
 * DISMISS ⇒ the content stays and the report closes DISMISSED; (4) a
 * non-admin on the administrative surface ⇒ 403 (the mandatory
 * negative); (5) a duplicate live report ⇒ 409 — and the own-content
 * 409 with it; (6) every state flip leaves its Envers trail; plus the
 * house guards: the comment-target flow, the documented skip
 * (already-hidden ⇒ RESOLVED without a second alert), closed-stays-
 * closed 409, the type gates, the 401-anonymous seam, and the DB CHECK
 * floors for raw writers.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ContentReportModuleIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsya): levels 0..3 of the
    // REAL administrative tree the service gates against.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

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

    /**
     * Cross-test isolation (the L42 convention): the queue is GLOBAL and
     * the feed counts by NEIGHBORHOOD — both reads are count-sensitive,
     * so every test starts from a clean queue and a clean feed.
     * Comments delete BEFORE posts (V61's internal FK); the membership
     * rows stay (they never affect queue or feed counts — every test
     * uses its own random users).
     */
    @BeforeEach
    void isolateQueueAndFeed() {
        jdbc.update("DELETE FROM content_reports");
        jdbc.update("DELETE FROM post_comments");
        jdbc.update("DELETE FROM neighborhood_posts");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    /** A member + their stored VISIBLE post id, created over the REAL chain. */
    private record SeededPost(UUID authorId, UUID postId) {
    }

    private SeededPost visiblePostIn(String locationId) {
        UUID authorId = UUID.randomUUID();
        membershipService.join(authorId, UUID.fromString(locationId));
        var view = postService.createPost(authorId, UUID.fromString(locationId),
                PostCategory.GENERAL, "Seeded title", "Seeded body");
        return new SeededPost(authorId, view.id());
    }

    /** One member's report on a VISIBLE post over the REAL HTTP chain. */
    private UUID reportOverHttp(UUID reporterId, UUID targetId, String targetType,
                                String reason, int expectedStatus) throws Exception {
        String body = mockMvc.perform(post("/api/v1/reports")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"" + targetType + "\", \"targetId\": \""
                                + targetId + "\", \"reason\": \"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        if (expectedStatus != 201) {
            return null;
        }
        return UUID.fromString(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(body).get("id").asText());
    }

    /**
     * The admin resolve over the REAL HTTP chain — the caller's admin id
     * rides the CurrentUserProvider seam (asCaller set by the test
     * before the call), the ADMIN authority rides the token.
     */
    private org.springframework.test.web.servlet.ResultActions resolveOverHttp(
            UUID reportId, String action, String note) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                .with(jwt().jwt(j -> j.subject("l45-admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"" + action + "\", \"note\": " + (note == null ? null
                        : "\"" + note + "\"") + "}"));
    }

    // ---------- criterion 1: the queue intake ----------

    @Test
    void criterion1_memberReportsAPost_201AndTheQueueCarriesItOpen() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        UUID reportId = reportOverHttp(reporterId, seeded.postId(), "POST", "SPAM", 201);

        // The administrative queue (the REAL admin surface, admin token)
        // carries the report OPEN with the stored facts.
        mockMvc.perform(get("/api/v1/admin/reports")
                        .with(jwt().jwt(j -> j.subject("l45-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.content[0].reporterId").value(reporterId.toString()))
                .andExpect(jsonPath("$.content[0].targetId").value(seeded.postId().toString()))
                .andExpect(jsonPath("$.content[0].targetType").value("POST"))
                .andExpect(jsonPath("$.content[0].reason").value("SPAM"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    // ---------- criterion 2: HIDE_CONTENT is one atomic command ----------

    @Test
    void criterion2_hideContent_hidesFromTheFeedNotifiesTheAuthorAndCloses() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        UUID reportId = reportOverHttp(reporterId, seeded.postId(), "POST", "SPAM", 201);

        // The author reads their feed (the post is there before the
        // moderation).
        asCaller(seeded.authorId());
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // The admin resolves with HIDE_CONTENT — the one atomic command.
        UUID adminId = asCaller(UUID.randomUUID()); // resolved_by's fact
        resolveOverHttp(reportId, "HIDE_CONTENT", "Spam confirmed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Spam confirmed"))
                .andExpect(jsonPath("$.resolvedBy").value(adminId.toString()));

        // The post is absent from the feed (the L42 read floor)...
        asCaller(seeded.authorId());
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // ...the author's CONTENT_MODERATED notification landed through
        // the REAL listener (the publication registry committed with the
        // resolve, the listener ran after commit)...
        awaitContentModeratedRows(seeded.authorId(), 1);
        verify(messagingTemplate, timeout(5000).times(1)).convertAndSend(
                eq("/topic/notifications/" + seeded.authorId()),
                any(com.marketplace.notifications.WebSocketNotification.class));

        // ...the queue's OPEN filter no longer carries it...
        mockMvc.perform(get("/api/v1/admin/reports")
                        .with(jwt().jwt(j -> j.subject("l45-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .queryParam("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // ...and the stored post row itself is flipped (the row stays —
        // b-5's retention; the reads stop returning it).
        assertThat(jdbc.queryForObject(
                "SELECT status FROM neighborhood_posts WHERE id = ?",
                String.class, seeded.postId())).isEqualTo("HIDDEN_BY_MODERATOR");
    }

    // ---------- criterion 3: DISMISS touches nothing but the report ----------

    @Test
    void criterion3_dismiss_theContentStaysAndTheReportCloses() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        UUID reportId = reportOverHttp(reporterId, seeded.postId(), "POST", "OTHER", 201);

        asCaller(UUID.randomUUID());
        resolveOverHttp(reportId, "DISMISS", "Not actionable")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        // The post stays exactly as it is...
        asCaller(seeded.authorId());
        mockMvc.perform(get("/api/v1/neighborhood/posts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM neighborhood_posts WHERE id = ?",
                String.class, seeded.postId())).isEqualTo("VISIBLE");
        // ...and NO moderation alert ever landed (the notification is the
        // HIDE_CONTENT action's own fact).
        awaitEventPublicationSettled();
        assertThat(contentModeratedRows(seeded.authorId())).isZero();
    }

    // ---------- criterion 4: the administrative gate ----------

    @Test
    void criterion4_nonAdminOnTheAdministrativeSurface_is403() throws Exception {
        // The chain's own /api/v1/admin/** rule: a plain authenticated
        // token (no ADMIN authority) never reaches the controller.
        mockMvc.perform(get("/api/v1/admin/reports").with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", UUID.randomUUID())
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"DISMISS\"}"))
                .andExpect(status().isForbidden());
        // and the queue read as admin works through the same rule.
        mockMvc.perform(get("/api/v1/admin/reports")
                        .with(jwt().jwt(j -> j.subject("l45-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    // ---------- criterion 5: the duplicate and own-content gates ----------

    @Test
    void criterion5_duplicateAndOwnContentAre409() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        reportOverHttp(reporterId, seeded.postId(), "POST", "SPAM", 201);
        // The duplicate live report: 409.
        reportOverHttp(reporterId, seeded.postId(), "POST", "OTHER", 409);

        // Another reporter's live report on the same target is FINE (the
        // index is per reporter+target, not per target).
        UUID otherReporter = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        reportOverHttp(otherReporter, seeded.postId(), "POST", "SPAM", 201);

        // The own-content gate: the author reporting their own post, 409.
        asCaller(seeded.authorId());
        reportOverHttp(seeded.authorId(), seeded.postId(), "POST", "SPAM", 409);

        // and an unknown target is the honest 404.
        reportOverHttp(reporterId, UUID.randomUUID(), "POST", "SPAM", 404);
    }

    // ---------- criterion 6: the Envers trail ----------

    @Test
    void criterion6_everyFlipLeavesItsEnversTrail() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        UUID reportId = reportOverHttp(reporterId, seeded.postId(), "POST", "SPAM", 201);

        asCaller(UUID.randomUUID());
        resolveOverHttp(reportId, "HIDE_CONTENT", null)
                .andExpect(status().isOk());

        // The report's own trail: the creation AND the resolve flip are
        // revisions on the real mirror (V24 convention).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM content_reports_aud WHERE id = ?",
                Integer.class, reportId)).isGreaterThanOrEqualTo(2);
        // The post's trail: the creation AND the moderation flip.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts_aud WHERE id = ? AND status = 'HIDDEN_BY_MODERATOR'",
                Integer.class, seeded.postId())).isGreaterThanOrEqualTo(1);
    }

    // ---------- the comment-target flow ----------

    @Test
    void hideContent_onAComment_softDeletesItAndAlertsTheCommentAuthor() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID commenterId = joinedMember(QUDSAYYA_OLD_TOWN);
        var comment = postService.comment(commenterId, seeded.postId(), "Reportable comment");
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        UUID reportId = reportOverHttp(reporterId, comment.id(), "COMMENT", "HARASSMENT", 201);

        asCaller(UUID.randomUUID());
        resolveOverHttp(reportId, "HIDE_CONTENT", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // The comment is soft-deleted (absent from the post's reads, the
        // row stays — b-5's retention)...
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM post_comments WHERE id = ? AND is_deleted = FALSE",
                Integer.class, comment.id())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM post_comments WHERE id = ?",
                Integer.class, comment.id())).isEqualTo(1);
        // ...the COMMENT author (not the post's) got the alert...
        awaitContentModeratedRows(commenterId, 1);
        assertThat(contentModeratedRows(seeded.authorId())).isZero();
    }

    // ---------- the documented skip + closed-stays-closed ----------

    @Test
    void alreadyHiddenTarget_resolvesWithoutADuplicateAlert_andClosedStaysClosed() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID firstReporter = joinedMember(QUDSAYYA_SUBURB);
        UUID secondReporter = joinedMember(QUDSAYYA_SUBURB);
        UUID firstReport = reportOverHttp(asCaller(firstReporter),
                seeded.postId(), "POST", "SPAM", 201);
        UUID secondReport = reportOverHttp(asCaller(secondReporter),
                seeded.postId(), "POST", "INAPPROPRIATE", 201);

        // The first resolve hides the post and alerts the author once.
        asCaller(UUID.randomUUID());
        resolveOverHttp(firstReport, "HIDE_CONTENT", null)
                .andExpect(status().isOk());
        awaitContentModeratedRows(seeded.authorId(), 1);

        // The second report on the now-hidden target: the documented skip
        // — the report still drains RESOLVED, but no second flip and NO
        // second author alert (one real hide = one alert).
        resolveOverHttp(secondReport, "HIDE_CONTENT", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        awaitEventPublicationSettled();
        assertThat(contentModeratedRows(seeded.authorId())).isEqualTo(1);

        // Closed history stays closed: a second resolve answers 409.
        resolveOverHttp(secondReport, "DISMISS", null)
                .andExpect(status().isConflict());
    }

    // ---------- the type gates + the anonymous seam ----------

    @Test
    void typeGateAnswers400BeforeAnyWrite_andTheAnonymousSeamIs401() throws Exception {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        // Invalid targetType / reason — the controller's own parse (the
        // vocabulary is listed; never an enum-binding 500).
        reportOverHttp(reporterId, seeded.postId(), "LISTING", "SPAM", 400);
        reportOverHttp(reporterId, seeded.postId(), "POST", "RUDENESS", 400);
        assertThat(reportRows(reporterId)).isZero();

        // Invalid action on the admin surface — same convention.
        UUID reportId = reportOverHttp(reporterId, seeded.postId(), "POST", "SPAM", 201);
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .with(jwt().jwt(j -> j.subject("l45-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"DELETE\"}"))
                .andExpect(status().isBadRequest());
        // and an invalid status filter on the queue read.
        mockMvc.perform(get("/api/v1/admin/reports")
                        .with(jwt().jwt(j -> j.subject("l45-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .queryParam("status", "DONE"))
                .andExpect(status().isBadRequest());

        // The security seam (the §15 smoke's own probe): both surfaces are
        // authenticated — 401 for the anonymous caller.
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"POST\", \"targetId\": \""
                                + seeded.postId() + "\", \"reason\": \"SPAM\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkConstraints_backTheEnumFloorsForRawWriters() {
        SeededPost seeded = visiblePostIn(QUDSAYYA_OLD_TOWN);
        UUID reporterId = UUID.randomUUID();

        // A raw SQL writer cannot invent a target type (D-N7)...
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO content_reports
                            (id, reporter_id, target_type, target_id, reason, status,
                             is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, 'LISTING', ?, 'SPAM', 'OPEN', FALSE, 0, now(), now())
                        """, UUID.randomUUID(), reporterId, seeded.postId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("chk_content_reports_target_type");
        // ...nor a reason outside the plan's vocabulary...
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO content_reports
                            (id, reporter_id, target_type, target_id, reason, status,
                             is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, 'POST', ?, 'RUDENESS', 'OPEN', FALSE, 0, now(), now())
                        """, UUID.randomUUID(), reporterId, seeded.postId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("chk_content_reports_reason");
        // ...nor a status outside the state machine...
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO content_reports
                            (id, reporter_id, target_type, target_id, reason, status,
                             is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, 'POST', ?, 'SPAM', 'DONE', FALSE, 0, now(), now())
                        """, UUID.randomUUID(), reporterId, seeded.postId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("chk_content_reports_status");

        // and the partial unique index is the duplicate backstop: a second
        // LIVE row for the same reporter+target answers 23505 (the house
        // 409 translation's own root).
        jdbc.update("""
                        INSERT INTO content_reports
                            (id, reporter_id, target_type, target_id, reason, status,
                             is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, 'POST', ?, 'SPAM', 'OPEN', FALSE, 0, now(), now())
                        """, UUID.randomUUID(), reporterId, seeded.postId());
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO content_reports
                            (id, reporter_id, target_type, target_id, reason, status,
                             is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, 'POST', ?, 'OTHER', 'OPEN', FALSE, 0, now(), now())
                        """, UUID.randomUUID(), reporterId, seeded.postId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("uq_content_reports_reporter_target");
    }

    // ---------- helpers ----------

    private int reportRows(UUID reporterId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE reporter_id = ? AND is_deleted = FALSE",
                Integer.class, reporterId);
    }

    private int contentModeratedRows(UUID recipientId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'CONTENT_MODERATED'",
                Integer.class, recipientId);
    }

    private void awaitContentModeratedRows(UUID recipientId, int expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (contentModeratedRows(recipientId) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("CONTENT_MODERATED notification never landed for " + recipientId);
    }

    private void awaitEventPublicationSettled() throws InterruptedException {
        // The registry's completion is the observable fact that the
        // listener ran (or skipped by policy) for every published event.
        for (int i = 0; i < 100; i++) {
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE event_type LIKE '%ContentModeratedEvent%' "
                            + "AND completion_date IS NULL",
                    Integer.class);
            if (pending == null || pending == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("ContentModeratedEvent publication never settled");
    }
}
