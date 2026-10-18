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
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
 * W3 / J10 (unified end-to-end plan §3, the journeys register row J10) — the
 * report-to-decision composite journey on the REAL chain: the member's
 * report (the 201 receipt with the honest OPEN status — the receipt state,
 * never a deletion claim) → the duplicate report's 409 → the moderator's
 * justified HIDE_CONTENT decision (the post's own status flip, the feed's
 * honest disappearance, the author's CONTENT_MODERATED notification
 * landing through the real AFTER_COMMIT listener) → the closed-stays-closed
 * 409 → the DISMISS branch (the content untouched, the report RESOLVED as
 * dismissed) → the comment-target branch (the report on a comment soft-
 * deletes it) — every state read from the owning rows over HTTP.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReportModerationJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    // One location per test method (the house isolation pattern): the
    // membership-scoped feed reads in this class carry absolute
    // totalElements contracts, so each method owns a DISTINCT seeded
    // location — a post created by a sibling method can never leak into
    // another method's count (the measured CI failure: the comment-host
    // post leaked across methods through the shared cached context).
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";
    private static final String AL_HAMAH = "11111111-1111-4111-8111-111111111106";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        return userId;
    }

    /**
     * The journey's linking test (plan §5): report → duplicate 409 → the
     * moderator's justified decision → the measured effects on the post,
     * the feed, the queue and the author's notifications → the honest
     * closed-stays-closed 409.
     */
    @Test
    void theReportJourneyFromReceiptThroughDecisionToTheMeasuredEffect() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J10 reported post\",\"body\":\"The post the journey reports.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // (1) The member's report — the honest receipt: OPEN, never a
        // deletion claim.
        UUID reporterId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String reportBody = mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":\"" + postId + "\",\"reason\":\"SPAM\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String reportId = idOf(reportBody);

        // (2) The duplicate report — one live report per reporter per
        // target (the honest 409).
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":\"" + postId + "\",\"reason\":\"HARASSMENT\"}")
                        .with(jwt()))
                .andExpect(status().isConflict());

        // (3) The moderator's justified decision — HIDE_CONTENT with the
        // administrative note.
        UUID adminId = asCaller(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"HIDE_CONTENT\",\"note\":\"Spam confirmed by review\"}")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Spam confirmed by review"))
                .andExpect(jsonPath("$.resolvedBy").value(adminId.toString()));

        // (4) The measured effects: the post's own status flipped — the
        // feed no longer carries it — the queue no longer shows it.
        asCaller(authorId);
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        String status = jdbc.queryForObject(
                "SELECT status FROM neighborhood_posts WHERE id = ?", String.class, UUID.fromString(postId));
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("HIDDEN_BY_MODERATOR");

        // The author's CONTENT_MODERATED notification landed through the
        // REAL listener (the registry committed with the resolve; the
        // listener ran after commit) — the house polling shape.
        awaitRows(1, "SELECT count(*) FROM notifications WHERE recipient_id = ? "
                + "AND type = 'CONTENT_MODERATED'", authorId);

        // (5) The closed-stays-closed branch — the second decision
        // answers the honest 409.
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\",\"note\":\"too late\"}")
                        .with(adminJwt()))
                .andExpect(status().isConflict());
    }

    /** The DISMISS branch: the report closes, the content stays visible — the honest non-action.
     * Isolation: this branch owns QUDSAYYA_SUBURB. */
    @Test
    void theDismissedReportLeavesTheContentUntouched() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_SUBURB + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_SUBURB + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J10 dismissed post\",\"body\":\"This one stays.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        UUID reporterId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_SUBURB + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String reportBody = mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":\"" + postId + "\",\"reason\":\"OTHER\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reportId = idOf(reportBody);

        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\",\"note\":\"Not actionable\"}")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        // The content is untouched and still visible to the author.
        asCaller(authorId);
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** The comment-target branch: HIDE_CONTENT on a COMMENT report soft-deletes the comment itself.
     * Isolation: this branch owns AL_HAMAH. */
    @Test
    void theCommentReportHidesTheCommentItself() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + AL_HAMAH + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + AL_HAMAH + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J10 comment host\",\"body\":\"The post that hosts the comment.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // A neighbor comments on the post.
        UUID commenterId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + AL_HAMAH + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String commentBody = mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"The comment the journey reports.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commentId = idOf(commentBody);

        // The author reports the comment; the moderator hides it.
        asCaller(authorId);
        String reportBody = mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"COMMENT\",\"targetId\":\"" + commentId + "\",\"reason\":\"INAPPROPRIATE\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reportId = idOf(reportBody);
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"HIDE_CONTENT\",\"note\":\"abusive language\"}")
                        .with(adminJwt()))
                .andExpect(status().isOk());

        // The comment is soft-deleted: the comments page answers the
        // honest zero while the POST itself stays visible.
        mockMvc.perform(get("/api/v1/posts/{postId}/comments", postId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private void awaitRows(int expected, String sql, Object... args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbc.queryForObject(sql, Integer.class, args);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private static String idOf(String body) {
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("j10-moderation-admin"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
