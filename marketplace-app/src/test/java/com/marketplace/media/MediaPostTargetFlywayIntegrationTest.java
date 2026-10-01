package com.marketplace.media;

import test.config.IntegrationContainers;

import com.marketplace.shared.api.PostLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The V77 regression guard — the production incident's own test class
 * (measured 2026-10-01, deployment 2d25ed95): V76 generalized the media
 * target but left V32's {@code listing_id NOT NULL} in place, so a post
 * photo's INSERT died with 'null value in column "listing_id" violates
 * not-null constraint' — while every test stayed GREEN, because the test
 * profile builds its schema from the ENTITY mappings (ddl-auto) and JPA
 * nullability never carried V32's column constraint. This test boots the
 * REAL Flyway chain instead ({@code flyway.enabled=true +
 * ddl-auto=none}, the MediaPublicReadIntegrationTest / membership-module
 * pattern) and drives the REAL {@code requestPostUpload} service path
 * through the REAL migrated schema: before V77 the not-null violation
 * surfaces exactly as it did in production; after V77 the POST-targeted
 * row lands with {@code listing_id IS NULL} and the one-target CHECK as
 * the sole authority.
 *
 * <p>The S3 presign channel runs for real but offline — presigning is a
 * LOCAL signature computation (no network round trip), and the storage
 * properties below satisfy {@code requireStorage()} exactly as the public
 * read test's own configuration does.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "marketplace.media.storage.endpoint=https://s3.test.example",
        "marketplace.media.storage.bucket=it-media-bucket",
        "marketplace.media.storage.access-key=it-media-access",
        "marketplace.media.storage.secret-key=it-media-secret",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class MediaPostTargetFlywayIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    /** The community seam — the post's author fact, resolved through the port. */
    @MockitoBean
    PostLookupPort postLookupPort;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID POST_AUTHOR = UUID.randomUUID();

    /**
     * The incident's exact path on the Flyway-built schema: declare a
     * post-targeted upload (the L48 contract) and assert the row lands
     * with listing_id NULL — the shape V32's not-null forbade and V77
     * releases. Position allocation, the object key's posts/ prefix, and
     * the one-target CHECK all assert the REAL persisted facts.
     */
    @Test
    @WithMockUser
    void postTargetedUpload_landsOnTheFlywaySchema_withListingIdNull() {
        UUID postId = UUID.randomUUID();
        when(postLookupPort.getPostInfo(postId)).thenReturn(
                new PostLookupPort.PostInfo(postId, POST_AUTHOR, true));
        Authentication auth = new TestingAuthenticationToken(POST_AUTHOR.toString(), "n/a");
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(POST_AUTHOR);

        MediaService.MediaUploadView view = mediaService.requestPostUpload(
                postId, "image/jpeg", 8486L, auth);

        assertThat(view.mediaId()).isNotNull();
        assertThat(view.objectKey()).startsWith("posts/" + postId + "/");

        var row = jdbcTemplate.queryForMap(
                "SELECT listing_id, post_id, owner_kind, provider_id, status, position "
                        + "FROM media_assets WHERE id = ?", view.mediaId());
        assertThat(row.get("listing_id"))
                .as("V77: the POST target carries no listing — the one-target CHECK's own demand")
                .isNull();
        assertThat(row.get("post_id")).isEqualTo(postId);
        assertThat(row.get("owner_kind")).isEqualTo("POST");
        assertThat(row.get("provider_id")).isEqualTo(POST_AUTHOR);
        assertThat(row.get("status")).isEqualTo("PENDING_UPLOAD");
        assertThat(((Number) row.get("position")).intValue()).isEqualTo(1);
    }
}
