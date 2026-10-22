package com.marketplace.app.graphql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The composite post/source detail journey over the REAL transport (the
 * corrected plan §11-Phase-7 + the journey inventory #599 §5 item 3 +
 * the readiness doc's P0-1 closure) — the journey's acceptance gate,
 * measured family by family: «مقارنة مع REST للرحلة نفسها، واختبارات
 * النطاق والترتيب والصلاحية والفراغ والأخطاء والكلفة».
 *
 * <p>Every leg executes the real SDL through the real spring-graphql
 * argument binding, the real DGS wiring, the real connection adapter,
 * the real security chain and the real HTTP stack — against the owning
 * module's real service mocked at its own seam (the community
 * {@code NeighborhoodPostService} behind the REAL detail adapter, so
 * the port mapping and the SPI delegation ride the execution end to
 * end — the {@code HomeGraphQlIntegrationTest} pattern verbatim). The
 * composite itself is the object under test: ONE document, ONE HTTP
 * round-trip, both legs — the measured REST comparison (the same
 * journey over REST is two HTTP requests: GET /api/v1/posts/{postId} +
 * GET /api/v1/posts/{postId}/comments, both contracts this wave's P0-1
 * closure owns on the REST side).</p>
 *
 * <p><b>The six families:</b> scope (the principal seam's resolved
 * caller is exactly the caller both legs consume — there is no
 * client-side scope vocabulary anywhere on the journey; the post id is
 * re-validated at open time, never trusted); ordering (the comments
 * page arrives in the owner's complete sort key createdAt ASC, id ASC);
 * authorization (anonymous never reaches the adapter — the endpoint's
 * own 401; the unknown post answers the owner's honest 404 at the field
 * level while the sibling leg still resolves — the partial-data
 * contract the nullable envelope carries; the membership-less caller
 * answers the owner's own denial on both legs); emptiness (a post with
 * no comments = the honest empty connection, a non-positive {@code
 * first} = the relay spec's empty page without repository work); errors
 * (garbage and misaligned cursors answer the VAL-001 taxonomy — the
 * services field's own measured contract, never a silent clamp); cost
 * (each owning leg consulted exactly once per execution;
 * selection-driven — a post-only document never touches the comments
 * seam, and vice versa).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "marketplace.graphql.pagination.default-page-size=3",
                "marketplace.graphql.pagination.max-page-size=5"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostDetailGraphQlIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @LocalServerPort
    private int port;

    /** The community owner behind the REAL adapter — the port mapping rides the execution. */
    @MockitoBean
    private com.marketplace.community.NeighborhoodPostService postService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** The principal seam — the fetchers consume its resolution; mocked so the synthetic member exists. */
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private HttpGraphQlTester graphQlTester;

    private static final UUID CALLER_ID = UUID.nameUUIDFromBytes("detail-caller".getBytes());
    private static final UUID POST_ID = UUID.nameUUIDFromBytes("detail-post".getBytes());
    private static final UUID NEIGHBORHOOD_ID = UUID.nameUUIDFromBytes("detail-neighborhood".getBytes());

    private static final String COMPOSITE_QUERY = """
            query PostDetail($id: ID!, $first: Int, $after: String) {
                post(id: $id) {
                    id authorId locationId category title body status declaredCommercial
                    lostFoundState reactionsCount reactedByMe
                    media { mediaId url thumbUrl contentType position }
                    createdAt updatedAt
                }
                postComments(postId: $id, first: $first, after: $after) {
                    edges { node { id postId authorId body createdAt updatedAt } cursor }
                    pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
            }
            """;

    /**
     * Boots the real HTTP transport against the test containers with an
     * authenticated member Jwt — every detail-journey test below rides the
     * real security chain, the real DGS wiring and the real connection
     * adapter.
     */
    @BeforeEach
    void setUpGraphQlTester() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", CALLER_ID.toString())
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("USER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(testJwt);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(CALLER_ID);

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    // ------------------------------------------------------------------
    // The family helpers
    // ------------------------------------------------------------------

    private static com.marketplace.community.NeighborhoodPostView detailPost() {
        return new com.marketplace.community.NeighborhoodPostView(
                POST_ID,
                UUID.nameUUIDFromBytes("detail-author".getBytes()),
                NEIGHBORHOOD_ID,
                "GENERAL",
                "The post's title",
                "The post's body",
                "VISIBLE",
                false,
                null,
                3L,
                true,
                List.of(),
                null,
                Instant.parse("2026-10-18T09:00:00Z"),
                Instant.parse("2026-10-18T09:00:00Z"));
    }

    /** One comment as the owner's complete sort key orders it (createdAt ASC, id ASC). */
    private static com.marketplace.community.PostCommentView comment(int ordinal) {
        return new com.marketplace.community.PostCommentView(
                UUID.nameUUIDFromBytes(("comment-" + ordinal).getBytes()),
                POST_ID,
                UUID.nameUUIDFromBytes(("commenter-" + ordinal).getBytes()),
                "Comment " + ordinal,
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal),
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal));
    }

    /** The comments stub honors the owner's contract: the page requested IS the page returned. */
    private static PageImpl<com.marketplace.community.PostCommentView> commentPage(
            Pageable pageable, int totalItems) {
        int from = (int) Math.min(pageable.getOffset(), totalItems);
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), totalItems);
        List<com.marketplace.community.PostCommentView> content = new java.util.ArrayList<>();
        for (long i = from; i < to; i++) {
            content.add(comment((int) i));
        }
        return new PageImpl<>(content, pageable, totalItems);
    }

    /**
     * Captures the comments connection's real end cursor for the
     * cursor-contract tests (the caller must have stubbed the comments
     * seam — this helper only executes page one and reads its end cursor).
     */
    private String captureEndCursor() {
        return graphQlTester.document("""
                        query Comments($id: ID!, $first: Int) {
                            postComments(postId: $id, first: $first) {
                                edges { node { body } }
                                pageInfo { endCursor }
                            }
                        }
                        """)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("postComments.pageInfo.endCursor").entity(String.class).get();
    }

    // ------------------------------------------------------------------
    // Scope + ordering + cost: the composite resolves both legs in the
    // owner's own deterministic order, the caller both legs consume is
    // the principal seam's own resolution, and each owning leg is
    // consulted exactly once — one document, one round-trip.
    // ------------------------------------------------------------------

    /** THE composite: one document, one round-trip, both legs, each seam once. */
    @Test
    void theCompositeServesTheWholeJourneyInOneRoundTrip() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 9));

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("post.id").entity(String.class).isEqualTo(POST_ID.toString())
                .path("post.title").entity(String.class).isEqualTo("The post's title")
                .path("post.reactionsCount").entity(Integer.class).isEqualTo(3)
                .path("post.reactedByMe").entity(Boolean.class).isEqualTo(true)
                .path("postComments.edges[*].node.body").entityList(String.class)
                .containsExactly("Comment 0", "Comment 1", "Comment 2")
                .path("postComments.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true);

        // The scope + cost contracts together: the principal seam's caller is
        // exactly the caller both legs consume, and each leg exactly once per
        // execution — no N+1, no double assembly, one round-trip.
        verify(postService, times(1)).getPost(CALLER_ID, POST_ID);
        verify(postService, times(1)).getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class));
    }

    /** The cursor round trip: page one's endCursor drives page two exactly — no overlap, no gaps. */
    @Test
    void theEndCursorDrivesTheExactNextPage() {
        List<Pageable> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable pageable = inv.getArgument(2);
                    requested.add(pageable);
                    return commentPage(pageable, 9);
                });

        String endCursor = captureEndCursor();

        graphQlTester.document("""
                        query Comments($id: ID!, $first: Int, $after: String) {
                            postComments(postId: $id, first: $first, after: $after) {
                                edges { node { body } }
                                pageInfo { hasNextPage }
                            }
                        }
                        """)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("postComments.edges[*].node.body").entityList(String.class)
                .containsExactly("Comment 3", "Comment 4", "Comment 5");

        // The cursor landed exactly: page 0 (items 0-2) then page 1
        // (items 3-5) — the aligned-cursor contract of the adapter's own
        // O_<offset> cursors.
        assertThat(requested).hasSize(2);
        assertThat(requested.get(1).getPageNumber()).isEqualTo(1);
        assertThat(requested.get(1).getPageSize()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Authorization: anonymous never reaches the adapter; the unknown
    // post answers the owner's honest 404 at the field level while the
    // sibling leg still resolves; the membership-less caller answers
    // the owner's own denial on both legs.
    // ------------------------------------------------------------------

    /**
     * The endpoint's own gate: an anonymous caller is rejected before any
     * adapter runs — PreLoginGraphQlGate answers the classified AUTHN-001
     * GraphQL error BEFORE dispatch (HTTP 200, ONE error with the canonical
     * taxonomy pair, NO data key — nothing leaked), and the Mockito proofs
     * keep the "no adapter ran" fact measured, not inferred.
     */
    @Test
    void anAnonymousCallerIsRejectedBeforeAnyAdapterRuns() {
        WebTestClient anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        anonymous.post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ post(id: \\\"00000000-0000-0000-0000-000000000000\\\") { id } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.errors[0].extensions.classification").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.data").doesNotExist();

        verify(postService, never()).getPost(any(), any());
        verify(postService, never()).getComments(any(), any(), any());
    }

    /**
     * The field-level denial (the partial-data contract the nullable
     * envelope carries) — the P0-1 discipline's OWN measured scenario:
     * each leg re-validates the post's CURRENT state at open time
     * («إعادة التحقق من الحالة الحالية» — never trust the card), so the
     * detail leg resolves the post and the comments leg — running an
     * instant later on the same document — finds the post deleted in
     * between and answers the honest 404: ONE NOT_FOUND-classified error
     * resolves the comments field to null while the resolved detail
     * section survives untouched (on a NON-NULL field the error would
     * propagate and destroy the whole composite's data — measured
     * against the GraphQL spec's null propagation; strictly worse than
     * the REST journey it replaces). Asserted on the raw transport
     * envelope (data + errors) exactly as the GraphQL spec delivers it.
     */
    @Test
    void aCommentsLegDenialDoesNotDestroyTheResolvedDetailSection() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class))).thenThrow(
                new com.marketplace.shared.api.ResourceNotFoundException("Post", POST_ID));

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-token")
                .build()
                .post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ post(id: \\\"" + POST_ID + "\\\") { id title reactionsCount } "
                        + "postComments(postId: \\\"" + POST_ID + "\\\") { edges { node { id } } } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("NF-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("not-found")
                .jsonPath("$.errors[0].path[0]").isEqualTo("postComments")
                .jsonPath("$.data.post.title").isEqualTo("The post's title")
                .jsonPath("$.data.post.reactionsCount").isEqualTo(3)
                .jsonPath("$.data.postComments").isEmpty();
    }

    /**
     * The unknown-post composite: BOTH legs run the same post gate, so an
     * unknown id answers the honest 404 on both — two classified field
     * errors, both fields null, never a fabricated post and never a
     * fabricated comment page.
     */
    @Test
    void anUnknownPostAnswersTheHonest404OnBothLegs() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenThrow(
                new com.marketplace.shared.api.ResourceNotFoundException("Post", POST_ID));
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class))).thenThrow(
                new com.marketplace.shared.api.ResourceNotFoundException("Post", POST_ID));

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-token")
                .build()
                .post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ post(id: \\\"" + POST_ID + "\\\") { id } "
                        + "postComments(postId: \\\"" + POST_ID + "\\\") { edges { node { id } } } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("NF-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("not-found")
                .jsonPath("$.data.post").isEmpty()
                .jsonPath("$.data.postComments").isEmpty();
    }

    /**
     * The membership-less caller answers the owner's own denial on BOTH
     * legs (each denied field one AUTHZ-classified error) — the REST
     * read's own 403 contract (G-N3), never a widened scope.
     */
    @Test
    void aMembershipLessCallerAnswersTheOwnersOwnDenialOnBothLegs() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenThrow(
                new org.springframework.security.access.AccessDeniedException(
                        "Join a neighborhood before reading a post (PUT /api/v1/me/neighborhood)"));
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class))).thenThrow(
                new org.springframework.security.access.AccessDeniedException(
                        "Join a neighborhood before reading comments (PUT /api/v1/me/neighborhood)"));

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-token")
                .build()
                .post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ post(id: \\\"" + POST_ID + "\\\") { id } "
                        + "postComments(postId: \\\"" + POST_ID + "\\\") { edges { node { id } } } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHZ-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.data.post").isEmpty()
                .jsonPath("$.data.postComments").isEmpty();
    }

    // ------------------------------------------------------------------
    // Emptiness: the honest empties the owner answers.
    // ------------------------------------------------------------------

    /** A non-positive first is the relay spec's empty page — no repository work. */
    @Test
    void aNonPositiveFirstAnswersTheEmptyPageWithoutRepositoryWork() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("id", POST_ID.toString())
                .variable("first", 0)
                .execute()
                .errors().verify()
                .path("post.title").entity(String.class).isEqualTo("The post's title")
                .path("postComments.edges").entityList(Object.class).hasSize(0)
                .path("postComments.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);

        verify(postService, times(1)).getPost(CALLER_ID, POST_ID);
        verify(postService, never()).getComments(any(), any(), any());
    }

    /** A post with no comments answers the honest EMPTY connection — never null, never fabricated. */
    @Test
    void aPostWithNoCommentsAnswersTheHonestEmptyConnection() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 0));

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("post.title").entity(String.class).isEqualTo("The post's title")
                .path("postComments.edges").entityList(Object.class).hasSize(0)
                .path("postComments.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Errors: the cursor contract — the services field's own measured
    // taxonomy (VAL-001), never a silent clamp.
    // ------------------------------------------------------------------

    /** An undecodable cursor is rejected as a validation error. */
    @Test
    void anUndecodableCursorIsRejectedAsAValidationError() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .variable("after", "!!!not-a-cursor!!!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    var error = errors.getFirst();
                    assertThat(error.getParsedPath()).containsExactly("postComments");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    /**
     * Changing {@code first} between pages is rejected, never clamped (the
     * services field's own measured example): page one with
     * {@code first: 3} ends at {@code O_2}; the follow-up with
     * {@code first: 4} is misaligned and answers VAL-001 — clamping would
     * replay or skip items, the exact ambiguity the connection contract
     * forbids.
     */
    @Test
    void changingFirstBetweenPagesIsRejectedAsAValidationError() {
        // The composite selects post BEFORE postComments — the detail leg
        // must resolve cleanly so the ONE error on the wire is exactly the
        // cursor contract's (the CodeRabbit round's measured catch: an
        // unstubbed getPost would NPE inside the adapter and add an
        // unrelated post error before the expected one).
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 9));

        String endCursor = captureEndCursor(); // first=3 page one → O_2

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("id", POST_ID.toString())
                .variable("first", 4)
                .variable("after", endCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    var error = errors.getFirst();
                    assertThat(error.getParsedPath()).containsExactly("postComments");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getMessage()).contains("not aligned");
                });
    }

    // ------------------------------------------------------------------
    // Cost: selection-driven — the composite pays only for what the
    // operation selects.
    // ------------------------------------------------------------------

    /** A post-only document never consults the comments seam. */
    @Test
    void aPostOnlyDocumentNeverConsultsTheCommentsSeam() {
        when(postService.getPost(CALLER_ID, POST_ID)).thenReturn(detailPost());

        graphQlTester.document("""
                        query PostOnly($id: ID!) {
                            post(id: $id) { id title reactionsCount reactedByMe createdAt updatedAt }
                        }
                        """)
                .variable("id", POST_ID.toString())
                .execute()
                .errors().verify()
                .path("post.title").entity(String.class).isEqualTo("The post's title");

        verify(postService, times(1)).getPost(CALLER_ID, POST_ID);
        verify(postService, never()).getComments(any(), any(), any());
    }

    /** A comments-only document never consults the detail seam. */
    @Test
    void aCommentsOnlyDocumentNeverConsultsTheDetailSeam() {
        when(postService.getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 9));

        graphQlTester.document("""
                        query CommentsOnly($id: ID!, $first: Int) {
                            postComments(postId: $id, first: $first) {
                                edges { node { body } cursor }
                                pageInfo { hasNextPage endCursor }
                            }
                        }
                        """)
                .variable("id", POST_ID.toString())
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("postComments.edges[*].node.body").entityList(String.class)
                .containsExactly("Comment 0", "Comment 1", "Comment 2");

        verify(postService, never()).getPost(any(), any());
        verify(postService, times(1)).getComments(eq(CALLER_ID), eq(POST_ID), any(Pageable.class));
    }
}
