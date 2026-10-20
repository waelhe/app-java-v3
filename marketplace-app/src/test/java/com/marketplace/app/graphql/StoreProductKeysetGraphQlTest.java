package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductAdminSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.security.core.Authentication;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Exercises the real DGS/HTTP connection adapter with keyset Window results.
 * The owning SPI is stubbed so this test isolates wire-cursor encode/decode;
 * the separate catalog test exercises the real Spring Data JPA keyset query.
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
            query($first: Int, $after: String) {
                storeProducts(first: $first, after: $after) {
                    edges { node { id } cursor }
                    pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
            }
            """;

    @LocalServerPort
    private int port;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private HttpGraphQlTester graphQlTester;
    private final List<ProductAdminSpi.ProductView> products = new ArrayList<>();
    private final Map<UUID, Instant> createdAtById = new HashMap<>();
    private final Map<UUID, Integer> ordinalById = new HashMap<>();

    @BeforeEach
    void setUp() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-provider")
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode("test-token")).thenReturn(testJwt);

        UUID providerId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.nameUUIDFromBytes(("store-keyset-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            products.add(new ProductAdminSpi.ProductView(
                    id, "home-appliances", "Product " + i, "Keyset fixture",
                    1000L + i, "SAR", providerId, null, "ACTIVE"));
            createdAtById.put(id, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
            ordinalById.put(id, i);
        }

        when(productAdminSpi.scrollOwnedProducts(any(Authentication.class),
                any(KeysetScrollPosition.class), any(Limit.class))).thenAnswer(invocation -> {
            KeysetScrollPosition position = invocation.getArgument(1);
            int limit = invocation.getArgument(2, Limit.class).max();
            int start = 0;
            if (!position.isInitial()) {
                Object rawId = position.getKeys().get("id");
                UUID id = rawId instanceof UUID value ? value : UUID.fromString(rawId.toString());
                Integer ordinal = ordinalById.get(id);
                if (ordinal == null) {
                    throw new IllegalArgumentException("Unknown fixture keyset id");
                }
                start = ordinal + 1;
            }
            int end = Math.min(start + limit, products.size());
            List<ProductAdminSpi.ProductView> page = List.copyOf(products.subList(start, end));
            return Window.from(page, index -> {
                ProductAdminSpi.ProductView product = page.get(index);
                return ScrollPosition.forward(Map.of(
                        "createdAt", createdAtById.get(product.id()),
                        "id", product.id()));
            }, end < products.size());
        });

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
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

        assertThat(firstIds).containsExactly(products.get(0).id().toString(), products.get(1).id().toString());
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

        assertThat(secondIds).containsExactly(products.get(2).id().toString(), products.get(3).id().toString());
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }
}
