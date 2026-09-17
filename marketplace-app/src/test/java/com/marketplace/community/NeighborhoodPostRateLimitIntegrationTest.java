package com.marketplace.community;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L42 (neighborhood community plan §5) acceptance criterion 7: spending
 * the window answers 429 — for BOTH write limiters, deterministically.
 *
 * <p><b>The instance-window proof (HTTP level, tiny instance per the
 * {@code RateLimitProblemDetailIntegrationTest} convention — including
 * its 60s refresh window, which CANNOT expire during the test):</b> with
 * {@code limit-for-period=2 / refresh=60s / timeout=0} (the fail-fast
 * production design for write endpoints — no queueing), the first two
 * rapid posts pass and the third is deterministically rejected; the same
 * arithmetic for comments. The rejection carries the RL-001
 * problem+json contract.
 *
 * <p><b>The two instances are independent by design</b> (the plan's own
 * words: "نمطلتان مسماتان مستقلتان"): a spent postComment window does
 * not lock the postCreate window — pinned by spending one and using the
 * other in the same test.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "resilience4j.ratelimiter.instances.postCreate.limit-for-period=2",
        "resilience4j.ratelimiter.instances.postCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.postCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.postComment.limit-for-period=2",
        "resilience4j.ratelimiter.instances.postComment.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.postComment.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodPostRateLimitIntegrationTest {

    // The fixed geo seed id (R__seed_geo_qudsaya): a REAL level-3 node.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    /**
     * The limiter-state isolation seam (the CodeRabbit round-1 adoption,
     * verified against the actual 2.4.0 bytecode): the three tests share
     * ONE Spring context, so the in-memory permits a test consumes would
     * bleed into the next (a spent postCreate window would answer 429 for
     * the independence test's final post). {@code registry.remove(name)}
     * drops the INSTANCE only — the aspect's own lookup
     * ({@code getConfiguration(name)} → {@code rateLimiter(name, config)},
     * measured in the decompiled RateLimiterAspect) re-creates it with the
     * properties-declared config on the next annotated call, so every
     * test starts with a full window. The surgical alternative to
     * {@code @DirtiesContext} — no context recreation, no container
     * restart.
     */
    @Autowired
    private io.github.resilience4j.ratelimiter.RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @Autowired
    private NeighborhoodPostService postService;

    private UUID authorId;

    @BeforeEach
    void seed() {
        // Full windows for every test (see the field's javadoc — the
        // registry drop is the isolation; the aspect re-creates with the
        // test properties' tiny config).
        rateLimiterRegistry.remove("postCreate");
        rateLimiterRegistry.remove("postComment");
        // A REAL member of the REAL seed node — the membership gate must
        // not mask the limiter's own answer (the RateLimitProblemDetail
        // convention: the first well-formed call runs the business logic).
        authorId = UUID.randomUUID();
        membershipService.join(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(authorId);
    }

    @Test
    @DisplayName("postCreate: the third post inside the window answers 429 RL-001 problem+json")
    void postCreate_thirdCallIsRateLimited() throws Exception {
        String body = "{\"locationId\": \"" + QUDSAYYA_OLD_TOWN + "\", \"category\": \"GENERAL\", "
                + "\"title\": \"T\", \"body\": \"B\"}";

        // First two: the business logic itself answers (201 — the member
        // posts in their own neighborhood).
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Third: rejected BEFORE any business code runs — the window is
        // spent, and the rejection carries the documented ProblemDetail
        // contract (RL-001, rate-limit category, RFC 7807 media type).
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RL-001"));
        // and nothing was written by the rejected call
        Integer posts = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_posts WHERE author_id = ?",
                Integer.class, authorId);
        org.assertj.core.api.Assertions.assertThat(posts).isEqualTo(2);
    }

    @Test
    @DisplayName("postComment: the third comment inside the window answers 429 RL-001 problem+json")
    void postComment_thirdCallIsRateLimited() throws Exception {
        // A visible post to comment on (the service call bypasses the
        // postCreate controller annotation entirely — only postComment's
        // window is under test here).
        var ownPost = postService.createPost(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                PostCategory.GENERAL, "T", "B");
        String body = "{\"body\": \"c\"}";

        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RL-001"));
        Integer comments = jdbc.queryForObject(
                "SELECT count(*) FROM post_comments WHERE post_id = ?",
                Integer.class, ownPost.id());
        org.assertj.core.api.Assertions.assertThat(comments).isEqualTo(2);
    }

    @Test
    @DisplayName("the two windows are independent: a spent comment window leaves the post window open")
    void theTwoWindowsAreIndependent() throws Exception {
        // Spend the comment window on the author's own post (self-comments
        // are honest events the listener skips — no notification noise).
        var ownPost = postService.createPost(authorId, UUID.fromString(QUDSAYYA_OLD_TOWN),
                PostCategory.GENERAL, "T", "B");
        String comment = "{\"body\": \"c\"}";
        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(comment))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(comment))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/posts/{id}/comments", ownPost.id())
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON).content(comment))
                .andExpect(status().isTooManyRequests());

        // The post window is untouched — the plan's "two distinct named
        // instances" is structural, not aspirational.
        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + QUDSAYYA_OLD_TOWN + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"T\", \"body\": \"B\"}"))
                .andExpect(status().isCreated());
    }
}
