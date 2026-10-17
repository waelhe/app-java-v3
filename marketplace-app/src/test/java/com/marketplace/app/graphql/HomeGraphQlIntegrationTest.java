package com.marketplace.app.graphql;

import java.time.Instant;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first composite user journey over the REAL transport (the
 * corrected plan §11-Phase-6 + the inventory #599 §7 stage 4) — the
 * journey's acceptance gate, measured family by family: «مقارنة مع
 * REST للرحلة نفسها، واختبارات النطاق والترتيب والصلاحية والفراغ
 * والأخطاء والكلفة».
 *
 * <p>Every leg executes the real SDL through the real spring-graphql
 * argument binding, the real DGS wiring, the real connection adapter,
 * the real security chain and the real HTTP stack — against the owning
 * modules' real services mocked at their own seams
 * ({@code DiscoveryService} behind the declared SPI, and the community
 * {@code NeighborhoodPostService} behind the REAL feed adapter, so the
 * port mapping and the SPI delegation ride the execution end to end).
 * The composite itself is the object under test: ONE document, ONE
 * HTTP round-trip, both sections — the measured REST comparison (the
 * same journey over REST is two HTTP requests: GET /api/v1/discovery +
 * GET /api/v1/neighborhood/posts).</p>
 *
 * <p><b>The six families:</b> scope (the principal seam's resolved
 * caller is exactly the caller both owners consume — there is no
 * client-side scope vocabulary anywhere on the journey); ordering (the
 * feed page arrives in the owner's complete sort key; the rails in the
 * assembler's fixed §1.4 order); authorization (anonymous never reaches
 * the adapter — the endpoint's own 401; the membership-less caller
 * answers the feed owner's own denial at the field level while
 * discovery resolves its honest empty list — the partial-data contract
 * the nullable connection carries); emptiness (no membership = honest
 * empties, a non-positive {@code first} = the relay spec's empty page
 * without repository work); errors (garbage and misaligned cursors
 * answer the VAL-001 taxonomy — the services field's own measured
 * contract, never a silent clamp); cost (each owning seam consulted
 * exactly once per execution; selection-driven — a discovery-only
 * document never touches the feed seam, and vice versa).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "marketplace.graphql.pagination.default-page-size=3",
                "marketplace.graphql.pagination.max-page-size=5"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class HomeGraphQlIntegrationTest {

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

    /** The discovery owner behind its declared SPI (the CatalogService mocking precedent). */
    @MockitoBean
    private com.marketplace.discovery.DiscoveryService discoveryService;

    /** The community owner behind the REAL adapter — the port mapping rides the execution. */
    @MockitoBean
    private com.marketplace.community.NeighborhoodPostService postService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** The principal seam — the fetchers consume its resolution; mocked so the synthetic member exists. */
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private HttpGraphQlTester graphQlTester;

    private static final UUID CALLER_ID = UUID.nameUUIDFromBytes("home-caller".getBytes());

    private static final String COMPOSITE_QUERY = """
            query Home($first: Int, $after: String) {
                discovery {
                    row
                    cards { sourceType sourceId updatedAt title snippet state reason paid }
                    totalEligible
                }
                neighborhoodFeed(first: $first, after: $after) {
                    edges { node { id authorId category title body status declaredCommercial
                                  lostFoundState reactionsCount reactedByMe createdAt updatedAt } cursor }
                    pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
            }
            """;

    /**
     * Boots the real HTTP transport against the test containers with an
     * authenticated member Jwt — every composite test below rides the
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

    private static com.marketplace.shared.api.DiscoveryRowView rail(
            com.marketplace.shared.api.DiscoveryRowType type, int cards, long total) {
        List<com.marketplace.shared.api.DiscoveryCardView> views =
                java.util.stream.IntStream.range(0, cards)
                        .mapToObj(i -> new com.marketplace.shared.api.DiscoveryCardView(
                                "NEIGHBORHOOD_POST",
                                UUID.nameUUIDFromBytes(("card-" + type + "-" + i).getBytes()),
                                Instant.parse("2026-10-17T10:00:00Z"),
                                "Card " + i,
                                "An honest bounded excerpt",
                                "ACTIVE",
                                null,
                                "في نطاق حيّك",
                                null))
                        .toList();
        return new com.marketplace.shared.api.DiscoveryRowView(type, views, total);
    }

    private static com.marketplace.community.NeighborhoodPostView feedPost(int ordinal) {
        // The owner's complete sort key (createdAt DESC, id DESC) renders as
        // newest-first: the ordinal series is authored so a LOWER ordinal
        // is NEWER (a later createdAt) — the deterministic order the
        // transport must preserve page after page.
        return new com.marketplace.community.NeighborhoodPostView(
                UUID.nameUUIDFromBytes(("post-" + ordinal).getBytes()),
                UUID.nameUUIDFromBytes(("author-" + ordinal).getBytes()),
                UUID.nameUUIDFromBytes("neighborhood".getBytes()),
                "GENERAL",
                "Post " + ordinal,
                "The body " + ordinal,
                "VISIBLE",
                false,
                null,
                3L + ordinal,
                ordinal % 2 == 0,
                List.of(),
                Instant.parse("2026-10-17T09:00:00Z").plusSeconds(100 - ordinal),
                Instant.parse("2026-10-17T09:00:00Z").plusSeconds(100 - ordinal));
    }

    /** The feed stub honors the owner's contract: the page requested IS the page returned. */
    private static PageImpl<com.marketplace.community.NeighborhoodPostView> feedPage(
            Pageable pageable, int totalItems) {
        int from = (int) Math.min(pageable.getOffset(), totalItems);
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), totalItems);
        List<com.marketplace.community.NeighborhoodPostView> content = new ArrayList<>();
        for (long i = from; i < to; i++) {
            content.add(feedPost((int) i));
        }
        return new PageImpl<>(content, pageable, totalItems);
    }

    /**
     * Captures the feed connection's real end cursor for the
     * cursor-contract tests (the caller must have stubbed the feed seam —
     * this helper only executes page one and reads its end cursor).
     */
    private String captureEndCursor() {
        return graphQlTester.document("""
                        query Feed($first: Int) {
                            neighborhoodFeed(first: $first) {
                                edges { node { title } }
                                pageInfo { endCursor }
                            }
                        }
                        """)
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("neighborhoodFeed.pageInfo.endCursor").entity(String.class).get();
    }

    // ------------------------------------------------------------------
    // Scope + ordering + cost: the composite resolves both sections in
    // the owners' own deterministic orders, the caller both owners
    // consume is the principal seam's own resolution, and each owning
    // seam is consulted exactly once — one document, one round-trip.
    // ------------------------------------------------------------------

    /** THE composite: one document, one round-trip, both sections, each seam once. */
    @Test
    void theCompositeServesTheWholeJourneyInOneRoundTrip() {
        when(discoveryService.homeRows(any())).thenReturn(List.of(
                rail(com.marketplace.shared.api.DiscoveryRowType.URGENT_ALERTS, 1, 1),
                rail(com.marketplace.shared.api.DiscoveryRowType.LOST_FOUND, 2, 7),
                rail(com.marketplace.shared.api.DiscoveryRowType.FOR_YOU, 1, 1)));
        when(postService.getFeed(any(), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> feedPage(inv.getArgument(2), 9));

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("discovery[*].row").entityList(String.class)
                .containsExactly("URGENT_ALERTS", "LOST_FOUND", "FOR_YOU")
                .path("discovery[1].totalEligible").entity(Integer.class).isEqualTo(7)
                .path("neighborhoodFeed.edges[*].node.title").entityList(String.class)
                .containsExactly("Post 0", "Post 1", "Post 2")
                .path("neighborhoodFeed.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true);

        // The scope + cost contracts together: the principal seam's caller is
        // exactly the caller both owners consume, and each owner exactly
        // once per execution — no N+1, no double assembly, one round-trip.
        verify(discoveryService, times(1)).homeRows(CALLER_ID);
        verify(postService, times(1)).getFeed(eq(CALLER_ID), isNull(), any(Pageable.class));
    }

    /** The cursor round trip: page one's endCursor drives page two exactly — no overlap, no gaps. */
    @Test
    void theEndCursorDrivesTheExactNextPage() {
        List<Pageable> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(discoveryService.homeRows(any())).thenReturn(List.of());
        when(postService.getFeed(any(), isNull(), any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(2);
            requested.add(pageable);
            return feedPage(pageable, 9);
        });

        String endCursor = captureEndCursor();

        graphQlTester.document("""
                        query Feed($first: Int, $after: String) {
                            neighborhoodFeed(first: $first, after: $after) {
                                edges { node { title } }
                                pageInfo { hasNextPage }
                            }
                        }
                        """)
                .variable("first", 3)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("neighborhoodFeed.edges[*].node.title").entityList(String.class)
                .containsExactly("Post 3", "Post 4", "Post 5");

        // The cursor landed exactly: page 0 (items 0-2) then page 1
        // (items 3-5) — the aligned-cursor contract of the adapter's own
        // O_<offset> cursors.
        assertThat(requested).hasSize(2);
        assertThat(requested.get(1).getPageNumber()).isEqualTo(1);
        assertThat(requested.get(1).getPageSize()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Authorization: anonymous never reaches the adapter; the
    // membership-less caller answers the feed owner's own denial at the
    // field level while discovery resolves its honest empty list.
    // ------------------------------------------------------------------

    /** The endpoint's own gate: an anonymous caller is rejected before any adapter runs. */
    @Test
    void anAnonymousCallerIsRejectedBeforeAnyAdapterRuns() {
        WebTestClient anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        anonymous.post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ discovery { row } }\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("AUTHN-001");

        verify(discoveryService, never()).homeRows(any());
        verify(postService, never()).getFeed(any(), any(), any());
    }

    /**
     * The field-level denial (the partial-data contract the nullable
     * connection carries): the feed owner's own AccessDeniedException
     * resolves the feed field to null with ONE AUTHZ-classified error
     * while discovery still resolves its honest empty list — the
     * composite never destroys the sibling section and never fabricates
     * a feed. Asserted on the raw transport envelope (data + errors)
     * exactly as the GraphQL spec delivers it.
     */
    @Test
    void aMembershipLessCallerAnswersTheFeedsOwnDenialWhileDiscoveryResolvesEmpty() {
        when(discoveryService.homeRows(any())).thenReturn(List.of());
        when(postService.getFeed(any(), isNull(), any(Pageable.class))).thenThrow(
                new org.springframework.security.access.AccessDeniedException(
                        "Join a neighborhood before reading its feed (PUT /api/v1/me/neighborhood)"));

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-token")
                .build()
                .post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ discovery { row } neighborhoodFeed { edges { node { id } } } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHZ-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.errors[0].path[0]").isEqualTo("neighborhoodFeed")
                .jsonPath("$.data.discovery").isArray()
                .jsonPath("$.data.discovery.length()").isEqualTo(0)
                .jsonPath("$.data.neighborhoodFeed").isEmpty();
    }

    // ------------------------------------------------------------------
    // Emptiness: the honest empties the owners answer.
    // ------------------------------------------------------------------

    /** A non-positive first is the relay spec's empty page — no repository work. */
    @Test
    void aNonPositiveFirstAnswersTheEmptyPageWithoutRepositoryWork() {
        when(discoveryService.homeRows(any())).thenReturn(List.of());

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("first", 0)
                .execute()
                .errors().verify()
                .path("discovery").entityList(Object.class).hasSize(0)
                .path("neighborhoodFeed.edges").entityList(Object.class).hasSize(0)
                .path("neighborhoodFeed.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);

        verify(discoveryService, times(1)).homeRows(CALLER_ID);
        verify(postService, never()).getFeed(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Errors: the cursor contract — the services field's own measured
    // taxonomy (VAL-001), never a silent clamp.
    // ------------------------------------------------------------------

    /** An undecodable cursor is rejected as a validation error. */
    @Test
    void anUndecodableCursorIsRejectedAsAValidationError() {
        when(discoveryService.homeRows(any())).thenReturn(List.of());

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("first", 3)
                .variable("after", "!!!not-a-cursor!!!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
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
        when(discoveryService.homeRows(any())).thenReturn(List.of());
        when(postService.getFeed(any(), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> feedPage(inv.getArgument(2), 9));

        String endCursor = captureEndCursor(); // first=3 page one → O_2

        graphQlTester.document(COMPOSITE_QUERY)
                .variable("first", 4)
                .variable("after", endCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getMessage()).contains("not aligned");
                });
    }

    // ------------------------------------------------------------------
    // Cost: selection-driven — the composite pays only for what the
    // operation selects.
    // ------------------------------------------------------------------

    /** A discovery-only document never consults the feed seam. */
    @Test
    void aDiscoveryOnlyDocumentNeverConsultsTheFeedSeam() {
        when(discoveryService.homeRows(any())).thenReturn(List.of(
                rail(com.marketplace.shared.api.DiscoveryRowType.FOR_YOU, 1, 1)));

        graphQlTester.document("""
                        query DiscoveryOnly { discovery { row totalEligible } }
                        """)
                .execute()
                .errors().verify()
                .path("discovery[0].row").entity(String.class).isEqualTo("FOR_YOU");

        verify(discoveryService, times(1)).homeRows(CALLER_ID);
        verify(postService, never()).getFeed(any(), any(), any());
    }

    /** A feed-only document never consults the discovery seam. */
    @Test
    void aFeedOnlyDocumentNeverConsultsTheDiscoverySeam() {
        when(postService.getFeed(any(), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> feedPage(inv.getArgument(2), 9));

        graphQlTester.document("""
                        query FeedOnly($first: Int) {
                            neighborhoodFeed(first: $first) {
                                edges { node { title } cursor }
                                pageInfo { hasNextPage endCursor }
                            }
                        }
                        """)
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("neighborhoodFeed.edges[*].node.title").entityList(String.class)
                .containsExactly("Post 0", "Post 1", "Post 2");

        verify(discoveryService, never()).homeRows(any());
        verify(postService, times(1)).getFeed(eq(CALLER_ID), isNull(), any(Pageable.class));
    }
}
