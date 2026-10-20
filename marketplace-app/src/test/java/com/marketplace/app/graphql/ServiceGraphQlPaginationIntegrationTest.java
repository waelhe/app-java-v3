package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ProviderListingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.query.ScrollPositionCursorStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises the services GraphQL Connection over the real HTTP transport.
 * The catalog owner is stubbed with Spring Data Window values so this test
 * verifies cursor encoding/decoding, bounds and continuation at the wire.
 * The catalog integration suite separately verifies the real PostgreSQL query.
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
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @LocalServerPort
    private int port;

    @MockitoBean
    private CatalogService catalogService;

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

    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy =
            CursorStrategy.withEncoder(new ScrollPositionCursorStrategy(), CursorEncoder.base64());

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

    private static UUID fixtureId(int ordinal) {
        return UUID.nameUUIDFromBytes(("svc-" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderListingView listing(int ordinal) {
        return new ProviderListingView(
                fixtureId(ordinal), "Service " + ordinal, "d", "general",
                1000L + ordinal, "SAR", UUID.randomUUID(), "ACTIVE", null, null, null);
    }

    private static Window<ProviderListingView> windowOf(
            KeysetScrollPosition position, Limit limit, int totalItems) {
        int start = 0;
        if (!position.isInitial()) {
            Object rawId = position.getKeys().get("id");
            UUID id = rawId instanceof UUID value ? value : UUID.fromString(rawId.toString());
            boolean found = false;
            for (int i = 0; i < totalItems; i++) {
                if (fixtureId(i).equals(id)) {
                    start = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Unknown service keyset fixture");
            }
        }
        final int windowStart = start;
        int end = Math.min(windowStart + limit.max(), totalItems);
        List<ProviderListingView> items = new ArrayList<>();
        for (int i = windowStart; i < end; i++) items.add(listing(i));
        return Window.from(List.copyOf(items), index -> {
            ProviderListingView item = items.get(index);
            int ordinal = windowStart + index;
            return ScrollPosition.forward(Map.of(
                    "createdAt", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(totalItems - ordinal),
                    "id", item.id()));
        }, end < totalItems);
    }

    private String captureEndCursor() {
        List<String> captured = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY).execute()
                .errors().verify()
                .path("services.pageInfo.endCursor").entity(String.class).satisfies(captured::add);
        assertThat(captured).isNotEmpty();
        return captured.getFirst();
    }

    @Test
    void firstPageDefaultsToTheConfiguredPageSize() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(3)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("services.pageInfo.startCursor").hasValue();
    }

    @Test
    void opaqueEndCursorDrivesTheExactNextKeysetWindow() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 9));

        String endCursor = captureEndCursor();

        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("services.edges[*].node.name").entityList(String.class)
                .isEqualTo(List.of("Service 3", "Service 4", "Service 5"))
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void changingWindowSizeBetweenPagesDoesNotReplayOrSkipItems() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 9));

        String endCursor = captureEndCursor();

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 4)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("services.edges[*].node.name").entityList(String.class)
                .isEqualTo(List.of("Service 3", "Service 4", "Service 5", "Service 6"));
    }

    @Test
    void firstBeyondTheHardMaximumIsClamped() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 5_000)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(5);
    }

    @Test
    void firstZeroAnswersAnEmptyConnectionWithoutReadingCatalog() {
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 0)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(0)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);

        org.mockito.Mockito.verifyNoInteractions(catalogService);
    }

    @Test
    void pageContentFollowsTheStableCreatedAtAndIdOrder() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 9));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 4)
                .execute()
                .errors().verify()
                .path("services.edges[*].node.name").entityList(String.class)
                .isEqualTo(List.of("Service 0", "Service 1", "Service 2", "Service 3"));
    }

    @Test
    void finalWindowReportsNoNextPage() {
        when(catalogService.scrollActiveListings(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenAnswer(inv -> windowOf(inv.getArgument(0), inv.getArgument(1), 3));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 3)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(3)
                .path("services.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void aForeignOffsetCursorIsRejectedAsAValidationError() {
        String offsetCursor = cursorStrategy.toCursor(ScrollPosition.offset(2));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", offsetCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void aForeignKeysetCursorWithWrongSortKeysIsRejected() {
        String cursor = cursorStrategy.toCursor(ScrollPosition.forward(Map.of("wrongKey", "value")));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", cursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void garbageCursorIsRejectedAsAValidationError() {
        graphQlTester.document(CONNECTION_QUERY)
                .variable("after", "not-a-cursor-at-all")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }
}
