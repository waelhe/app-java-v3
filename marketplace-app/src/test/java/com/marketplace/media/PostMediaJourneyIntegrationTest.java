package com.marketplace.media;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 / J04 (unified end-to-end plan §3, the journeys register row J04) — the
 * post-with-media composite journey on the REAL chain: the membership join
 * → the post creation → the SIGNED upload request for the post's own id →
 * the client's byte PUT (the storage boundary is the one honest mock —
 * S3MediaStorage at the SDK seam, the ProductMediaJourney precedent) → the
 * upload CONFIRM (the HeadObject verify) → the FEED read carrying the
 * post's media with a fresh presigned download — plus the failure
 * branches: a failed upload confirm leaves the asset re-confirmable, and a
 * non-author's upload against someone else's post answers 403.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostMediaJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    // One location per test method (the house isolation pattern): the main
    // journey's feed read carries an absolute content[0] contract, so the
    // sibling method's post must live in a DISTINCT location — it can
    // never leak into the journey's membership-scoped feed.
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    /** The storage boundary — the one honest mock (the ProductMediaJourney precedent). */
    @MockitoBean
    com.marketplace.media.S3MediaStorage storage;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID authorId = UUID.randomUUID();

    @BeforeEach
    void seedTheIdentityAndStorageSeams() {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(authorId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://signed-put");
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");
        when(storage.verifyUploaded(anyString(), anyString(), eq(2048L))).thenReturn(true);
        when(storage.verifyUploaded(anyString(), anyString(), eq(4096L))).thenReturn(false);
    }

    /**
     * The journey's linking test (plan §5): create the post → request the
     * signed upload bound to it → the failed confirm branch (the object
     * never landed) → the successful confirm → the feed carries the media.
     */
    @Test
    void thePostMediaJourneyFromCreationThroughSignedUploadToTheFeed() throws Exception {
        // (1) The membership and the post.
        mockMvc.perform(put("/api/v1/me/neighborhood",
                        "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}"))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J04 media journey\",\"body\":\"The post that will carry media.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // (2) The signed upload request FOR the post — the author's own.
        String uploadBody = mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + postId + "\",\"contentType\":\"image/jpeg\",\"sizeBytes\":2048}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").exists())
                .andExpect(jsonPath("$.uploadUrl").value("https://signed-put"))
                .andReturn().getResponse().getContentAsString();
        String mediaId = nodeIdOf(uploadBody, "mediaId");

        // (3) The failure branch first (the honest ordering the journey
        // itself can hit): the client PUTs to a 4096-byte object the
        // storage stub answers "not verified" for — the confirm answers
        // 400 and the asset stays re-confirmable (no state corruption).
        String failedUpload = mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + postId + "\",\"contentType\":\"image/jpeg\",\"sizeBytes\":4096}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String failedMediaId = nodeIdOf(failedUpload, "mediaId");
        mockMvc.perform(post("/api/v1/media/{id}/complete", failedMediaId)
                        .with(jwt()))
                .andExpect(status().isBadRequest());

        // (4) The successful confirm — the asset lands UPLOADED.
        mockMvc.perform(post("/api/v1/media/{id}/complete", mediaId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.postId").value(postId));

        // (5) The feed read-back: the post now carries its media with the
        // fresh presigned download — one source of truth (the post's own
        // rows), no copy.
        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(postId))
                .andExpect(jsonPath("$.content[0].media.length()").value(1))
                .andExpect(jsonPath("$.content[0].media[0].mediaId").value(mediaId))
                .andExpect(jsonPath("$.content[0].media[0].url").value("https://signed-get"));
    }

    /** The non-author branch: someone else's post is not an upload target — the honest 403.
     * Isolation: this branch owns QUDSAYYA_SUBURB. */
    @Test
    void aNonAuthorCannotRequestMediaForSomeoneElsesPost() throws Exception {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(authorId);
        mockMvc.perform(put("/api/v1/me/neighborhood",
                        "{\"locationId\":\"" + QUDSAYYA_SUBURB + "\"}"))
                .andExpect(status().isCreated());
        String postBody = mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_SUBURB + "\",\"category\":\"GENERAL\","
                                + "\"title\":\"J04 other author\",\"body\":\"Another member's post.\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String postId = idOf(postBody);

        // A DIFFERENT caller — the stranger on the upload surface.
        UUID strangerId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(strangerId);
        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + postId + "\",\"contentType\":\"image/jpeg\",\"sizeBytes\":2048}")
                        .with(jwt()))
                .andExpect(status().isForbidden());

        // No media row was ever written for the refused request.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM media_assets WHERE post_id = ?", Integer.class, UUID.fromString(postId));
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
    }

    private static String idOf(String body) {
        return nodeIdOf(body, "id");
    }

    private static String nodeIdOf(String body, String field) {
        int start = body.indexOf("\"" + field + "\":\"") + field.length() + 4;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /** The house's jwt() post-processor rides every PUT — one seam, no repetition. */
    private org.springframework.test.web.servlet.RequestBuilder put(String path, String json) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt());
    }
}
