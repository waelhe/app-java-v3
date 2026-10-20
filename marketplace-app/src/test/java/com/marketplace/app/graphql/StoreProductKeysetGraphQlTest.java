package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.pagination.ScrollPositionCursorStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Exercises the provider product keyset cursor through the real PostgreSQL
 * repository, secured service, DGS fetcher, and GraphQL HTTP connection adapter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StoreProductKeysetGraphQlTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String CONNECTION_QUERY = """
            query(
                $first: Int,
                $after: String,
                $filter: StoreProductFilterInput,
                $sortBy: StoreProductSortInput
            ) {
                storeProducts(first: $first, after: $after, filter: $filter, sortBy: $sortBy) {
                    edges { node { id title priceMinor status } cursor }
                    pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private ProductRepository products;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private HttpGraphQlTester graphQlTester;

    // The SAME codec the fetchers inject — used only to CRAFT the
    // well-named/unparseable cursor the value-normalization gate must
    // reject (the real round-trips above use the connection's own
    // emitted cursors).
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy =
            CursorStrategy.withEncoder(new ScrollPositionCursorStrategy(), CursorEncoder.base64());
    private UUID providerId;
    private String categoryCode;
    private final List<UUID> productIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        providerId = UUID.randomUUID();
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", providerId.toString())
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode("test-token")).thenReturn(testJwt);
        users.save(new User(providerId, providerId.toString(), null, "Keyset test provider", UserRole.PROVIDER));

        categoryCode = "keyset-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), categoryCode, "Keyset test", "اختبار التصفح");

        List<Product> fixtures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Product product = products.save(Product.register(
                    categoryCode, "Product " + i, "Keyset fixture",
                    1000L + i, "SAR", providerId));
            fixtures.add(product);
            productIds.add(product.getId());
        }
        // Descending timestamps make the fixture order deterministic even
        // when the database clock gives adjacent inserts the same timestamp.
        for (int i = 0; i < fixtures.size(); i++) {
            Instant createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(fixtures.size() - i);
            jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = ?",
                    Timestamp.from(createdAt), fixtures.get(i).getId());
        }
        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    @Test
    void searchAndPriceAndStatusFiltersAreAppliedByTheDomainQuery() {
        // A matching product owned by another provider must never leak into the roster.
        products.save(Product.register(
                categoryCode, "Product 2", "Matching search but another owner",
                1002L, "SAR", UUID.randomUUID()));
        products.save(Product.register(
                categoryCode, "Product 2 USD", "Matching search but a different currency",
                1002L, "USD", providerId));

        List<String> titles = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 10)
                .variable("filter", java.util.Map.of(
                        "search", "pRoDuCt 2",
                        "storeCategoryCode", categoryCode,
                        "status", "ACTIVE",
                        "currency", "SAR",
                        "minPriceMinor", "1002",
                        "maxPriceMinor", "1002"))
                .variable("sortBy", java.util.Map.of("field", "TITLE", "direction", "ASC"))
                .execute()
                .errors().verify()
                .path("storeProducts.edges[*].node.title").entityList(String.class)
                .satisfies(titles::addAll);

        assertThat(titles).containsExactly("Product 2");
    }

    @Test
    void nonDefaultSortUsesItsOwnKeysetForContinuation() {
        java.util.Map<String, Object> filter = java.util.Map.of(
                "storeCategoryCode", categoryCode,
                "currency", "SAR",
                "minPriceMinor", "1000",
                "maxPriceMinor", "1004");
        java.util.Map<String, Object> sort = java.util.Map.of("field", "PRICE_MINOR", "direction", "ASC");
        List<String> firstIds = new ArrayList<>();
        List<String> cursors = new ArrayList<>();

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("filter", filter)
                .variable("sortBy", sort)
                .execute()
                .errors().verify()
                .path("storeProducts.edges[*].node.id").entityList(String.class)
                .satisfies(firstIds::addAll)
                .path("storeProducts.pageInfo.endCursor").entity(String.class)
                .satisfies(cursors::add);

        assertThat(firstIds).containsExactly(productIds.get(0).toString(), productIds.get(1).toString());

        List<String> secondIds = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("after", cursors.getFirst())
                .variable("filter", filter)
                .variable("sortBy", sort)
                .execute()
                .errors().verify()
                .path("storeProducts.edges[*].node.id").entityList(String.class)
                .satisfies(secondIds::addAll);

        assertThat(secondIds).containsExactly(productIds.get(2).toString(), productIds.get(3).toString());
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }

    @Test
    void priceSortRequiresCurrencyFilter() {
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("sortBy", java.util.Map.of("field", "PRICE_MINOR", "direction", "ASC"))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void aSortKeyCursorWithWellNamedButUnparseableValuesIsRejected() {
        // The #677 gate adoption generalized for the roster's sort
        // vocabulary: the key-set check alone let a client-crafted cursor
        // with matching names but unparseable VALUES travel to the JPA
        // keyset predicate (an internal error). The shared gate now
        // normalizes each property's value to its declared type, so the
        // same craft answers the VAL-001 taxonomy on this connection too.
        UUID id = UUID.nameUUIDFromBytes("unparseable-sort-cursor".getBytes(StandardCharsets.UTF_8));
        String cursor = cursorStrategy.toCursor(
                ScrollPosition.forward(java.util.Map.of("priceMinor", "x", "id", id.toString())));

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("after", cursor)
                .variable("sortBy", java.util.Map.of("field", "PRICE_MINOR", "direction", "ASC"))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void aCursorFromAnotherSortIsRejected() {
        List<String> cursors = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .execute()
                .errors().verify()
                .path("storeProducts.pageInfo.endCursor").entity(String.class)
                .satisfies(cursors::add);

        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("after", cursors.getFirst())
                .variable("sortBy", java.util.Map.of("field", "TITLE", "direction", "ASC"))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void opaqueEndCursorResumesTheNextKeysetWindowOverHttp() {
        List<String> firstIds = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .execute()
                .errors().verify()
                .path("storeProducts.edges[*].node.id").entityList(String.class)
                .satisfies(firstIds::addAll)
                .path("storeProducts.pageInfo.endCursor").entity(String.class)
                .satisfies(cursors::add);

        assertThat(firstIds).containsExactly(productIds.get(0).toString(), productIds.get(1).toString());
        assertThat(cursors).hasSize(1);
        assertThat(cursors.getFirst()).isNotBlank();

        List<String> secondIds = new ArrayList<>();
        graphQlTester.document(CONNECTION_QUERY)
                .variable("first", 2)
                .variable("after", cursors.getFirst())
                .execute()
                .errors().verify()
                .path("storeProducts.edges[*].node.id").entityList(String.class)
                .satisfies(secondIds::addAll)
                .path("storeProducts.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true);

        assertThat(secondIds).containsExactly(productIds.get(2).toString(), productIds.get(3).toString());
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }
}
