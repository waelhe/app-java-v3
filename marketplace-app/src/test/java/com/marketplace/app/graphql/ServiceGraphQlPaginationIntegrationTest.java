package com.marketplace.app.graphql;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
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
import static org.mockito.Mockito.when;

/**
 * The bounded pagination contract of the {@code services} connection over
 * the REAL transport — the design plan Phase 1's first closure (the field
 * resolved {@code Pageable.unpaged()} before it). Every leg executes the
 * real SDL through the real spring-graphql argument binding, the real
 * Boot-registered {@code Subrange} resolver, the real connection adapter
 * and the real HTTP stack, against a mocked catalog SPI that returns
 * deterministic pages — the transport contract is the object under test,
 * not the catalog query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "marketplace.graphql.pagination.default-page-size=3",
                "marketplace.graphql.pagination.max-page-size=5"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ServiceGraphQlPaginationIntegrationTest {

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

    @MockitoBean
    private com.marketplace.catalog.CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private HttpGraphQlTester graphQlTester;

    private static final String CONNECTION_QUERY = """
            query($first: Int, $after: String) {
                services(first: $first, after: $after) {
                    edges { node { id name } cursor }
                    pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
            }
            """;

    @BeforeEach
    void setUpGraphQlTester() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(testJwt);

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * The catalog stub honors the contract the controller must uphold: the
     * page requested IS the page returned — bounded size, deterministic
     * order, and Spring Data's own page metadata.
     */
    private static PageImpl<com.marketplace.shared.api.ProviderListingView> pageOf(
            Pageable pageable, int totalItems) {
        int from = (int) Math.min(pageable.getOffset(), totalItems);
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), totalItems);
        List<com.marketplace.shared.api.ProviderListingView> content = new ArrayList<>();
        for (long i = from; i < to; i++) {
            content.add(listing((int) i));
        }
        return new PageImpl<>(content, pageable, totalItems);
    }

    private static com.marketplace.shared.api.ProviderListingView listing(int ordinal) {
        return new com.marketplace.shared.api.ProviderListingView(
                UUID.nameUUIDFromBytes(("svc-" + ordinal).getBytes()),
                "Service " + ordinal, "d", "general", 1000L + ordinal, "SAR",
                UUID.randomUUID(), "ACTIVE", null, null, null);
    }

    /** The default page: omitted first → the configured default size (3 here). */
    @Test
    void firstPageDefaultsToTheConfiguredPageSize() {
        when(catalogService.findAll(any(Pageable.class))).thenAnswer(inv -> pageOf(inv.getArgument(0), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(3)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("services.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
    }

    /** The connection cursor round trip: page 1's endCursor drives page 2 — no overlap, no gaps. */
    @Test
    void theEndCursorDrivesTheExactNextPage() {
        List<Pageable> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(catalogService.findAll(any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            requested.add(pageable);
            return pageOf(pageable, 9);
        });

        String endCursor = captureEndCursor();

        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("services.edges.node.name").entityList(String.class)
                .isEqualTo(List.of("Service 3", "Service 4", "Service 5"))
                .path("services.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);

        // The cursor landed exactly: page 0 (items 0-2) then page 1
        // (items 3-5) — the aligned-cursor contract of the adapter's own
        // O_<offset> cursors.
        assertThat(requested).hasSize(2);
        assertThat(requested.get(1).getPageNumber()).isEqualTo(1);
        assertThat(requested.get(1).getPageSize()).isEqualTo(3);
    }

    /**
     * The hard maximum: a first beyond the configured ceiling is clamped to
     * it (5 here) — the transport can never widen the read.
     */
    @Test
    void firstBeyondTheHardMaximumIsClamped() {
        org.mockito.Mockito.when(catalogService.findAll(any(Pageable.class)))
                .thenAnswer(inv -> pageOf(inv.getArgument(0), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 5_000)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(5);

        org.mockito.Mockito.verify(catalogService).findAll(
                org.mockito.ArgumentMatchers.argThat((Pageable p) -> p.getPageSize() == 5));
    }

    /** Relay spec: first=0 asks for an empty page — answered honestly. */
    @Test
    void firstZeroAnswersAnEmptyConnection() {
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 0)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(0)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);

        org.mockito.Mockito.verifyNoInteractions(catalogService);
    }

    /**
     * Deterministic order across pages: the connection's content follows
     * the server's declared order (createdAt DESC, id DESC) on every page —
     * the stub returns pages whose ordinals descend, and the wire carries
     * them unchanged.
     */
    @Test
    void pagesCarryTheDeterministicServerOrder() {
        when(catalogService.findAll(any(Pageable.class))).thenAnswer(inv -> pageOf(inv.getArgument(0), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 4)
                .execute()
                .errors().verify()
                .path("services.edges.node.name").entityList(String.class)
                .isEqualTo(List.of("Service 0", "Service 1", "Service 2", "Service 3"))
                .path("services.pageInfo.endCursor").hasValue();
    }

    /**
     * The final page: hasNextPage goes false exactly when the set is
     * exhausted — page metadata honest to the slice, not guessed.
     */
    @Test
    void theFinalPageReportsNoNextPage() {
        when(catalogService.findAll(any(Pageable.class))).thenAnswer(inv -> pageOf(inv.getArgument(0), 3));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(3)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    /**
     * A foreign keyset cursor (K_-shaped, decodable by the framework's
     * strategy but never emitted by this surface) is a validation error
     * (VAL-001), never a silent mis-pagination.
     */
    @Test
    void aKeysetCursorIsRejectedAsAValidationError() {
        // base64("K_{\"createdAt\":\"2026-01-01\"}") — the framework's
        // keyset cursor wire form, hand-crafted.
        String keysetCursor = java.util.Base64.getEncoder()
                .encodeToString("K_{\"createdAt\":\"2026-01-01\"}".getBytes());

        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", keysetCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getMessage()).contains("offset cursors");
                });
    }

    /**
     * An undecodable after-cursor is rejected by the framework's own
     * argument decoding (IllegalArgumentException through the resolver —
     * VAL-001), the same validation taxonomy.
     */
    @Test
    void garbageCursorIsRejectedAsAValidationError() {
        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", "not-a-cursor-at-all")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    private String captureEndCursor() {
        List<String> captured = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY).execute()
                .errors().verify()
                .path("services.pageInfo.endCursor")
                .entity(String.class)
                .satisfies(captured::add);
        assertThat(captured).isNotEmpty();
        return captured.getFirst();
    }
}
