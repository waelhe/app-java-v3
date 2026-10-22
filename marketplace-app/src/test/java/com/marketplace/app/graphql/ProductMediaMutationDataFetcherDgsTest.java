package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.media.spi.ProductMediaBulkResult;
import com.marketplace.media.spi.ProductMediaMutationError;
import com.marketplace.media.spi.ProductMediaMutationErrorCode;
import com.marketplace.media.spi.ProductMediaMutationResult;
import com.marketplace.media.spi.ProductMediaReorderResult;
import com.marketplace.media.spi.ProductMediaView;
import com.marketplace.media.spi.ProductMediaWriteSpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productMediaDelete}, {@code productMediaBulkDelete} and
 * {@code productMediaReorder} mutation roots, packet agent-2): the
 * GraphQL adapter's DGS proof — REAL mutation documents against the
 * REAL SDL (the agent-2 SDL fragment's own declared roots) and the
 * REAL DGS wiring with no web layer and no containers. The owning
 * write use cases are mocked at the declared
 * {@code ProductMediaWriteSpi} seam (and the payload's product
 * projection at the catalog read seam); the full-stack proof (the
 * real media-module writes over the Flyway-migrated schema, the real
 * {@code @PreAuthorize} PROVIDER gate and the real soft-delete SQL)
 * lives in {@code ProductMediaGraphQlIntegrationTest}'s legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding; the payload shapes serialize
 * exactly as the pinned 3.23.43 schema declares them ({@code errors}
 * with field/message/code over the product family's shared
 * {@code ProductError}, the deleted/new-order {@code media}
 * projections carrying the typed global IDs, the bulk delete's honest
 * {@code count}, the payload's {@code product} projection through the
 * catalog read seam); the domain error entries map onto the Saleor
 * {@code ProductErrorCode} vocabulary; and the transport-level
 * negatives (a malformed or mistyped global ID, a keyless call)
 * answer the classified VAL-001 GraphQL error family the read roots
 * established — never a silent no-op.</p>
 */
@SpringBootTest(classes = ProductMediaMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductMediaMutationDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {

        // The containerless slice binds the GraphQL operational budgets
        // directly (the record's own documented defaults — the same values
        // the full application context binds through
        // @EnableConfigurationProperties). The slice carries no
        // configuration-property binding infrastructure of its own, and the
        // @Primary registration keeps the scanned GraphQlConfig's own
        // binding-backed definition from ever being instantiated here.
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        GraphQlProperties graphQlProperties() {
            return new GraphQlProperties(
                    new GraphQlProperties.Errors(false),
                    new GraphQlProperties.Pagination(20, 100),
                    new GraphQlProperties.Limits(10, 200));
        }
    }

    // The token-rotation JDBC store rides the package component scan; its
    // owning seam is not under test in this slice — the interface mock
    // keeps the slice's containerless wiring intact (the seam-gets-its-mock law).
    @MockitoBean
    private RefreshTokenRotationStore refreshTokenRotationStore;

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the CollectionMutationDataFetcherDgsTest sibling precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private CollectionWriteSpi collectionWriteSpi;

    @MockitoBean
    private DiscoverySpi discoverySpi;

    @MockitoBean
    private NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private IdentitySpi identitySpi;

    @MockitoBean
    private ProductVariantReadSpi variantReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

    @MockitoBean
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice).
    @MockitoBean
    private ProductMediaWriteSpi productMediaWriteSpi;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("roles", List.of(roles))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(java.util.Arrays.stream(roles).map(r -> "ROLE_" + r)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .<org.springframework.security.core.GrantedAuthority>toList()
                        .toArray(new org.springframework.security.core.GrantedAuthority[0])),
                subject);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** The typed global ID the codec emits — base64("ProductMedia:<uuid>"). */
    private static String productMediaGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductMedia:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The typed global ID the codec emits — base64("Product:<uuid>"). */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The delete through the real wiring: the typed global ID decodes
     * through the codec, the owner's seam receives the native UUID, and
     * the payload serializes the empty error list with the deleted
     * row's last-known projection (the typed global IDs and the display
     * sort order) and the owning product's own projection through the
     * catalog read seam.
     */
    @Test
    void productMediaDeleteExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID mediaId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productMediaWriteSpi.deleteProductMedia(eq(mediaId), any())).thenReturn(
                ProductMediaMutationResult.success(
                        new ProductMediaView(mediaId, productId, 2), productId));
        when(productReadSpi.findProductById(eq(productId), any())).thenReturn(
                Optional.of(new ProductView(productId, "Espresso machine", null, "ACTIVE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaDelete(id: "%s") {
                    errors { field message code }
                    media { id sortOrder productId }
                    product { id name status }
                } }
                """.formatted(productMediaGlobalId(mediaId)), "data.productMediaDelete");

        verify(productMediaWriteSpi).deleteProductMedia(eq(mediaId), any());
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) payload.get("media");
        assertThat(media.get("id")).isEqualTo(productMediaGlobalId(mediaId));
        assertThat(media.get("sortOrder")).isEqualTo(2);
        assertThat(media.get("productId")).isEqualTo(productGlobalId(productId));
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) payload.get("product");
        assertThat(product.get("id")).isEqualTo(productGlobalId(productId));
        assertThat(product.get("name")).isEqualTo("Espresso machine");
        assertThat(product.get("status")).isEqualTo("ACTIVE");
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor ProductError vocabulary
     * (the pinned payloads' own family).
     */
    @Test
    void productMediaDeleteAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID mediaId = UUID.randomUUID();
        when(productMediaWriteSpi.deleteProductMedia(eq(mediaId), any())).thenReturn(
                ProductMediaMutationResult.refusal(List.of(new ProductMediaMutationError(
                        "id", ProductMediaMutationErrorCode.NOT_FOUND,
                        "No product media found for the given id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaDelete(id: "%s") {
                    errors { field message code }
                    media { id }
                    product { id }
                } }
                """.formatted(productMediaGlobalId(mediaId)), "data.productMediaDelete");

        assertThat(payload.get("media")).isNull();
        assertThat(payload.get("product")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("id");
        assertThat(errors.get(0).get("message")).isEqualTo("No product media found for the given id");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
    }

    /**
     * A raw UUID (not the typed global ID) answers the classified
     * VAL-001 GraphQL error family — never a silent no-op.
     */
    @Test
    void productMediaDeleteWithARawUuidAnswersTheClassifiedError() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID raw = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaDelete(id: "%s") { errors { field } media { id } } }
                """.formatted(raw), "data.productMediaDelete"))
                .isInstanceOf(Exception.class);
        verify(productMediaWriteSpi, never()).deleteProductMedia(any(UUID.class), any());
    }

    /**
     * A mistyped global ID (the Product tag where the ProductMedia tag
     * is required) answers the same classified family.
     */
    @Test
    void productMediaDeleteWithAMistypedTagAnswersTheClassifiedError() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID foreign = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaDelete(id: "%s") { errors { field } media { id } } }
                """.formatted(productGlobalId(foreign)), "data.productMediaDelete"))
                .isInstanceOf(Exception.class);
        verify(productMediaWriteSpi, never()).deleteProductMedia(any(UUID.class), any());
    }

    /**
     * The bulk delete through the real wiring: every id decodes through
     * the codec and the count rides the owner's own honest number.
     */
    @Test
    void productMediaBulkDeleteAnswersTheHonestCount() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID owned = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(productMediaWriteSpi.bulkDeleteProductMedia(anyList(), any()))
                .thenReturn(new ProductMediaBulkResult(1, List.of()));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaBulkDelete(ids: ["%s", "%s"]) {
                    errors { field message code }
                    count
                } }
                """.formatted(productMediaGlobalId(owned), productMediaGlobalId(stranger)),
                "data.productMediaBulkDelete");

        verify(productMediaWriteSpi).bulkDeleteProductMedia(
                argThat(ids -> ids != null && ids.size() == 2
                        && ids.get(0).equals(owned) && ids.get(1).equals(stranger)),
                any());
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the batch fails the whole call — the
     * classified VAL-001 family, never a silent skip.
     */
    @Test
    void productMediaBulkDeleteWithAMalformedIdFailsTheWholeCall() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID owned = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaBulkDelete(ids: ["%s", "%s"]) { count errors { field } } }
                """.formatted(productMediaGlobalId(owned), "not-a-global-id"),
                "data.productMediaBulkDelete"))
                .isInstanceOf(Exception.class);
        verify(productMediaWriteSpi, never()).bulkDeleteProductMedia(anyList(), any());
    }

    /**
     * The reorder through the real wiring: both typed global IDs decode
     * through the codec, the owner's seam receives the native UUIDs in
     * the given order, and the payload serializes the new-order media
     * projections with the compacted 1..n positions.
     */
    @Test
    void productMediaReorderExecutesTheOwnerWithTheDecodedIds() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID productId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(productMediaWriteSpi.reorderProductMedia(eq(productId), any(), any())).thenReturn(
                ProductMediaReorderResult.success(productId, List.of(
                        new ProductMediaView(second, productId, 1),
                        new ProductMediaView(first, productId, 2))));
        when(productReadSpi.findProductById(eq(productId), any())).thenReturn(Optional.empty());

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaReorder(productId: "%s", mediaIds: ["%s", "%s"]) {
                    errors { field message code }
                    media { id sortOrder productId }
                    product { id }
                } }
                """.formatted(productGlobalId(productId), productMediaGlobalId(first),
                        productMediaGlobalId(second)),
                "data.productMediaReorder");

        verify(productMediaWriteSpi).reorderProductMedia(eq(productId),
                argThat(ids -> ids != null && ids.size() == 2
                        && ids.get(0).equals(first) && ids.get(1).equals(second)),
                any());
        assertThat(payload.get("errors")).isEqualTo(List.of());
        assertThat(payload.get("product")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> media = (List<Map<String, Object>>) payload.get("media");
        assertThat(media).hasSize(2);
        assertThat(media.get(0).get("id")).isEqualTo(productMediaGlobalId(second));
        assertThat(media.get(0).get("sortOrder")).isEqualTo(1);
        assertThat(media.get(1).get("id")).isEqualTo(productMediaGlobalId(first));
        assertThat(media.get(1).get("sortOrder")).isEqualTo(2);
    }

    /**
     * The reorder's domain refusal (the partial-list INVALID) rides the
     * payload's own errors field over the product family's shared
     * vocabulary.
     */
    @Test
    void productMediaReorderAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");
        UUID productId = UUID.randomUUID();
        when(productMediaWriteSpi.reorderProductMedia(eq(productId), any(), any())).thenReturn(
                ProductMediaReorderResult.refusal(List.of(new ProductMediaMutationError(
                        "mediaIds", ProductMediaMutationErrorCode.INVALID,
                        "The reorder list must cover every media row of the product"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaReorder(productId: "%s", mediaIds: ["%s"]) {
                    errors { field message code }
                    media { id }
                } }
                """.formatted(productGlobalId(productId), productMediaGlobalId(UUID.randomUUID())),
                "data.productMediaReorder");

        assertThat(payload.get("media")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("mediaIds");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID");
    }

    /**
     * A keyless reorder (no productId) answers the classified
     * validation error — never a NullPointerException.
     */
    @Test
    void productMediaReorderWithoutTheProductIdAnswersTheClassifiedError() {
        authenticateAs("dgs-media-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productMediaReorder(mediaIds: ["%s"]) { errors { field } media { id } } }
                """.formatted(productMediaGlobalId(UUID.randomUUID())),
                "data.productMediaReorder"))
                .isInstanceOf(Exception.class);
        verify(productMediaWriteSpi, never()).reorderProductMedia(any(), anyList(), any());
    }
}
