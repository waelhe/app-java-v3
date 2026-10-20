package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.media.S3MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 catalog/media proof through the real GraphQL HTTP transport, catalog
 * and media services, and Flyway PostgreSQL schema. Object storage and the
 * external provider directory are the only mocked boundaries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StoreCatalogMediaGraphQlIntegrationTest {

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

    @Autowired
    private ProductRepository products;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @MockitoBean
    private S3MediaStorage storage;

    private HttpGraphQlTester graphQlTester;
    private UUID ownerId;
    private UUID productId;
    private String categoryCode;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        categoryCode = "media-journey-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        users.save(new User(ownerId, ownerId.toString(), null, "P1 media journey owner", UserRole.PROVIDER));
        jdbc.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 4, FALSE, 0, now(), now())
                """, UUID.randomUUID(), categoryCode, "Home essentials", "أساسيات المنزل");

        products.save(Product.register(
                categoryCode, "P1 media journey product", "Seeded for GraphQL media lifecycle",
                149900L, "SAR", ownerId));

        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", ownerId.toString())
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode("test-token")).thenReturn(testJwt);
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(ownerId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findByUserId(ownerId))
                .thenReturn(Optional.of(new ProviderSummary(UUID.randomUUID(), "P1 provider", "VERIFIED", ownerId)));
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://storage.test/signed-put");
        when(storage.verifyUploaded(anyString(), anyString(), eq(1024L))).thenReturn(true);
        when(storage.presignDownload(anyString())).thenReturn("https://storage.test/signed-get");

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    @Test
    void categoryReadAndSignedProductMediaLifecycleWorkThroughGraphQl() {
        List<String> categories = new ArrayList<>();
        graphQlTester.document("""
                        query {
                            storeCategories { code nameEn nameAr position }
                        }
                        """)
                .execute()
                .errors().verify()
                .path("storeCategories[*].code").entityList(String.class)
                .satisfies(categories::addAll);

        assertThat(categories).contains(categoryCode);

        List<String> mediaIds = new ArrayList<>();
        graphQlTester.document("""
                        mutation($input: StoreProductMediaUploadInput!) {
                            requestStoreProductMediaUpload(input: $input) {
                                mediaId objectKey uploadUrl urlLifetimeSeconds
                            }
                        }
                        """)
                .variable("input", java.util.Map.of(
                        "productId", productId.toString(),
                        "contentType", "image/jpeg",
                        "sizeBytes", "1024"))
                .execute()
                .errors().verify()
                .path("requestStoreProductMediaUpload.mediaId").entity(String.class)
                .satisfies(mediaIds::add)
                .path("requestStoreProductMediaUpload.objectKey").entity(String.class)
                .matches(key -> key.startsWith("products/" + productId + "/"))
                .path("requestStoreProductMediaUpload.uploadUrl").entity(String.class)
                .isEqualTo("https://storage.test/signed-put")
                .path("requestStoreProductMediaUpload.urlLifetimeSeconds").entity(Integer.class)
                .satisfies(seconds -> assertThat(seconds).isPositive());

        String mediaId = mediaIds.getFirst();
        graphQlTester.document("""
                        mutation($mediaId: ID!) {
                            confirmStoreProductMediaUpload(mediaId: $mediaId) {
                                id productId status downloadUrl
                            }
                        }
                        """)
                .variable("mediaId", mediaId)
                .execute()
                .errors().verify()
                .path("confirmStoreProductMediaUpload.id").entity(String.class).isEqualTo(mediaId)
                .path("confirmStoreProductMediaUpload.productId").entity(String.class)
                .isEqualTo(productId.toString())
                .path("confirmStoreProductMediaUpload.status").entity(String.class).isEqualTo("UPLOADED")
                .path("confirmStoreProductMediaUpload.downloadUrl").entity(String.class)
                .isEqualTo("https://storage.test/signed-get");

        graphQlTester.document("""
                        query($productId: ID!) {
                            storeProductMedia(productId: $productId) {
                                id productId status position downloadUrl
                            }
                        }
                        """)
                .variable("productId", productId.toString())
                .execute()
                .errors().verify()
                .path("storeProductMedia").entityList(Object.class).hasSize(1)
                .path("storeProductMedia[0].id").entity(String.class).isEqualTo(mediaId);

        graphQlTester.document("""
                        mutation($mediaId: ID!) {
                            deleteStoreProductMedia(mediaId: $mediaId)
                        }
                        """)
                .variable("mediaId", mediaId)
                .execute()
                .errors().verify()
                .path("deleteStoreProductMedia").entity(Boolean.class).isEqualTo(true);

        graphQlTester.document("""
                        query($productId: ID!) {
                            storeProductMedia(productId: $productId) { id }
                        }
                        """)
                .variable("productId", productId.toString())
                .execute()
                .errors().verify()
                .path("storeProductMedia").entityList(Object.class).hasSize(0);
    }

    @Test
    void productMediaUploadCannotMintAUrlForAForeignProvider() {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(UUID.randomUUID());

        graphQlTester.document("""
                        mutation($input: StoreProductMediaUploadInput!) {
                            requestStoreProductMediaUpload(input: $input) { mediaId uploadUrl }
                        }
                        """)
                .variable("input", java.util.Map.of(
                        "productId", productId.toString(),
                        "contentType", "image/jpeg",
                        "sizeBytes", "1024"))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                });

        verify(storage, never()).presignUpload(anyString(), anyString());
    }
}
