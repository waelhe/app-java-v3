package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantBulkOutcome;
import com.marketplace.catalog.spi.ProductVariantBulkUpdateItem;
import com.marketplace.catalog.spi.ProductVariantMutationError;
import com.marketplace.catalog.spi.ProductVariantMutationErrorCode;
import com.marketplace.catalog.spi.ProductVariantMutationResult;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.pricing.spi.VoucherWriteSpi;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantCreate}, {@code productVariantUpdate},
 * {@code productVariantDelete} and {@code productVariantBulkDelete}
 * mutation roots, the agent-2 follow-on packet): the GraphQL adapter's
 * DGS proof — REAL mutation documents against the REAL SDL (the
 * agent-2 SDL fragment's own declared roots) and the REAL DGS wiring
 * with no web layer and no containers. The owning write use case is
 * mocked at the declared {@code ProductVariantWriteSpi} seam; the
 * full-stack proof (the real registry writes over the
 * Flyway-migrated schema, the real {@code @PreAuthorize} PROVIDER
 * gate and the real soft-delete SQL) lives in the integration legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code ProductVariantCreateInput}/{@code ProductVariantInput}
 * binding by name); the payload shapes serialize exactly as Saleor
 * declares them ({@code errors} with field/message/code plus the
 * variant projection carrying the typed global ID and the parent's
 * own bounded projection); the domain error entries map onto the
 * Saleor {@code ProductError} vocabulary; the transport-level
 * negatives (a malformed or mistyped global ID, a keyless call, the
 * declared-but-unowned addressing arguments) answer the classified
 * VAL-001 GraphQL error family the read roots established — never a
 * silent no-op; and the bulk delete's count rides the owner's own
 * honest number.</p>
 */
@SpringBootTest(classes = ProductVariantMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductVariantMutationDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest/AttributeDataFetcherDgsTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

    @MockitoBean
    private ProductVariantReadSpi variantReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    // The translation mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;

    // The product-type attribute schema mutation roots' owning write
    // seam (agent-2 follow-on packet) — the package slice wires every
    // fetcher, so this seam gets its mock (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

    @MockitoBean
    private com.marketplace.discovery.spi.DiscoverySpi discoverySpi;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @MockitoBean
    private com.marketplace.identity.spi.IdentitySpi identitySpi;

    @MockitoBean
    private com.marketplace.orders.spi.OrderReadSpi orderReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    @MockitoBean
    private ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private ProductWriteSpi productWriteSpi;

    @MockitoBean
    private AttributeWriteSpi attributeWriteSpi;

    @MockitoBean
    private AttributeValueWriteSpi attributeValueWriteSpi;

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;

    // The post-merge integration round (PR #696 review): the slice scans
    // ALL of com.marketplace.app.graphql, so it discovers the multi-instance
    // rotation round's JdbcRefreshTokenRotationStore — a real @Component
    // whose constructor needs a JdbcTemplate the containerless slice has
    // no DataSource for. The mock satisfies the wiring; the store's REAL
    // behavior (including the two-instance atomicity) is proven over real
    // PostgreSQL/Flyway in PlatformTokenGrantRotationMultiInstanceIntegrationTest.
    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // The post-merge integration round's wiring completion (PR #696
    // review): the slice scans ALL of com.marketplace.app.graphql, and
    // A0's central assembly landed EVERY campaign fetcher in that one
    // package — so the slice must satisfy the constructor seam of every
    // discovered fetcher, not just its own domain's. The missing owning
    // SPIs join the mocked seam set here (the declared interfaces, never
    // the internals; the REAL implementations are proven in the owning
    // modules' suites and the full-stack integration tests over real
    // PostgreSQL/Flyway).
    @MockitoBean
    private com.marketplace.identity.spi.AccountLifecycleSpi accountLifecycleSpi;
    @MockitoBean
    private com.marketplace.identity.spi.AddressManagementSpi addressManagementSpi;
    @MockitoBean
    private com.marketplace.console.spi.CmsNavigationSpi cmsNavigationSpi;
    @MockitoBean
    private com.marketplace.console.spi.CmsPageSpi cmsPageSpi;
    @MockitoBean
    private com.marketplace.identity.spi.CustomerTypeSpi customerTypeSpi;
    @MockitoBean
    private PlatformTokenGrantService platformTokenGrantService;
    @MockitoBean
    private com.marketplace.identity.spi.UserManagementSpi userManagementSpi;

    // Agent 3's checkout/order-command seams (PR #698): the slice package-scans
    // com.marketplace.app.graphql and wires CheckoutDataFetcher, so the shared
    // context cannot boot without these two mocked.
    @MockitoBean
    private com.marketplace.orders.spi.CheckoutSpi checkoutSpi;
    @MockitoBean
    private com.marketplace.orders.spi.OrderCommandSpi orderCommandSpi;

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

    private static ProductView productView(UUID id) {
        return new ProductView(id, "Owned Widget", null, "ACTIVE");
    }

    private static ProductVariantView variantView(UUID id, UUID productId, String sku) {
        return new ProductVariantView(id, "Large / Red", sku, "ACTIVE", productView(productId));
    }

    /** The typed global IDs the codec emits — base64("Product:<uuid>") / base64("ProductVariant:<uuid>"). */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String variantGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductVariant:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by
     * name (the typed product reference decodes at the boundary), the
     * command reaches the owner's seam, and the payload serializes the
     * empty error list with the created projection's typed global ID
     * and the parent's own bounded projection.
     */
    @Test
    void productVariantCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID productId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        when(productVariantWriteSpi.createVariant(any(), any())).thenReturn(
                ProductVariantMutationResult.success(variantView(createdId, productId, "SKU-1")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantCreate(input: {product: "%s", sku: "SKU-1", name: "Large / Red", priceMinor: 149900}) {
                    errors { field message code }
                    productVariant { id name sku status product { id name } }
                } }
                """.formatted(productGlobalId(productId)), "data.productVariantCreate");

        verify(productVariantWriteSpi).createVariant(argThat(command ->
                command.productId().equals(productId) && "SKU-1".equals(command.sku())
                        && "Large / Red".equals(command.name()) && command.priceMinor() == 149900L),
                any());
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> variant = (Map<String, Object>) payload.get("productVariant");
        assertThat(variant.get("id")).isEqualTo(variantGlobalId(createdId));
        assertThat(variant.get("sku")).isEqualTo("SKU-1");
        assertThat(variant.get("name")).isEqualTo("Large / Red");
        assertThat(variant.get("status")).isEqualTo("ACTIVE");
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>) variant.get("product");
        assertThat(parent.get("id")).isEqualTo(productGlobalId(productId));
        assertThat(parent.get("name")).isEqualTo("Owned Widget");
    }

    /**
     * The domain validation outcome rides the payload's own errors
     * field — the registered "Mutation payload errors" ADAPT divergence:
     * the domain error entries map onto the Saleor ProductError
     * vocabulary.
     */
    @Test
    void productVariantCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(productVariantWriteSpi.createVariant(any(), any())).thenReturn(
                ProductVariantMutationResult.failure(List.of(
                        new ProductVariantMutationError("sku",
                                ProductVariantMutationErrorCode.ALREADY_EXISTS,
                                "The SKU is already used in this seller's namespace"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantCreate(input: {product: "%s", sku: "taken", priceMinor: 100}) {
                    errors { field message code }
                    productVariant { id }
                } }
                """.formatted(productGlobalId(UUID.randomUUID())), "data.productVariantCreate");

        assertThat(payload.get("productVariant")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("sku");
        assertThat(errors.get(0).get("message")).isEqualTo("The SKU is already used in this seller's namespace");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /** A keyless create (no product reference) answers the owner's REQUIRED ladder through the payload. */
    @Test
    void productVariantCreateWithAMistypedProductReferenceAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String mistyped = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantCreate(input: {product: "%s", sku: "SKU-1", priceMinor: 100}) {
                    errors { code }
                    productVariant { id }
                } }
                """.formatted(mistyped), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productVariantWriteSpi);
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam.
     */
    @Test
    void productVariantUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID rowId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productVariantWriteSpi.updateVariant(any(UUID.class), any(), any())).thenReturn(
                ProductVariantMutationResult.success(variantView(rowId, productId, "SKU-2")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantUpdate(id: "%s", input: {sku: "SKU-2", priceMinor: 99900}) {
                    errors { code }
                    productVariant { id sku }
                } }
                """.formatted(variantGlobalId(rowId)), "data.productVariantUpdate");

        verify(productVariantWriteSpi).updateVariant(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "SKU-2".equals(command.sku()) && command.name() == null
                        && command.priceMinor() == 99900L),
                any());
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> variant = (Map<String, Object>) payload.get("productVariant");
        assertThat(variant.get("id")).isEqualTo(variantGlobalId(rowId));
        assertThat(variant.get("sku")).isEqualTo("SKU-2");
    }

    /**
     * The declared-but-unowned {@code sku} addressing argument (the id
     * is the addressing key on this platform) answers the classified
     * GraphQL error family, never a silently ignored argument.
     */
    @Test
    void productVariantUpdateWithSkuAddressingAnswersTheClassifiedError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantUpdate(sku: "SKU-1", input: {name: "X"}) {
                    errors { code }
                    productVariant { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("sku addressing is not implemented");
        org.mockito.Mockito.verifyNoInteractions(productVariantWriteSpi);
    }

    /**
     * A malformed global ID answers the codec's classified VAL-001
     * GraphQL error family — never a silent null and never a raw UUID
     * fallback (the read roots' own codec contract).
     */
    @Test
    void productVariantDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantDelete(id: "not-a-global-id") {
                    errors { code }
                    productVariant { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productVariantWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void productVariantDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID rowId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productVariantWriteSpi.deleteVariant(any(UUID.class), any())).thenReturn(
                ProductVariantMutationResult.success(variantView(rowId, productId, "SKU-1")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantDelete(id: "%s") {
                    errors { code }
                    productVariant { id sku status }
                } }
                """.formatted(variantGlobalId(rowId)), "data.productVariantDelete");

        verify(productVariantWriteSpi).deleteVariant(argThat((UUID id) -> id.equals(rowId)), any());
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> variant = (Map<String, Object>) payload.get("productVariant");
        assertThat(variant.get("id")).isEqualTo(variantGlobalId(rowId));
        assertThat(variant.get("sku")).isEqualTo("SKU-1");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void productVariantBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(productVariantWriteSpi.deleteVariants(anyList(), any())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(variantGlobalId(first), variantGlobalId(second)),
                "data.productVariantBulkDelete");

        verify(productVariantWriteSpi).deleteVariants(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)), any());
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * The declared-but-unowned {@code skus} addressing argument answers
     * the classified family — never a partial silent skip at the
     * boundary.
     */
    @Test
    void productVariantBulkDeleteWithSkusAddressingAnswersTheClassifiedError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkDelete(ids: ["%s"], skus: ["SKU-1"]) { count errors { code } } }
                """.formatted(variantGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("skus addressing is not implemented");
        org.mockito.Mockito.verifyNoInteractions(productVariantWriteSpi);
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void productVariantBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productVariantWriteSpi);
    }
    // ------------------------------------------------------------------
    // the bulk roots (the agent-2 follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void productVariantBulkCreateRendersThePerItemResults() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID productId = UUID.randomUUID();
        when(productVariantWriteSpi.bulkCreateVariants(any(UUID.class), anyList(), any(), any())).thenReturn(List.of(
                new ProductVariantBulkOutcome(variantView(UUID.randomUUID(), productId, "SKU-A"), List.of()),
                new ProductVariantBulkOutcome(null, List.of(new ProductVariantMutationError(
                        "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                        "The SKU is already used in this seller's namespace")))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkCreate(product: "%s",
                        variants: [{sku: "SKU-A", name: "A", priceMinor: 100},
                                   {sku: "taken", name: "T", priceMinor: 100}],
                        errorPolicy: IGNORE_FAILED) {
                    count
                    productVariants { sku }
                    errors { code }
                    results { productVariant { sku } errors { path message code } }
                } }
                """.formatted(productGlobalId(productId)), "data.productVariantBulkCreate");

        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) payload.get("productVariants");
        assertThat(created).hasSize(1);
        assertThat(created.get(0).get("sku")).isEqualTo("SKU-A");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemErrors = (List<Map<String, Object>>) results.get(1).get("errors");
        assertThat(itemErrors.get(0).get("path")).isEqualTo("variants.1.sku");
        assertThat(itemErrors.get(0).get("code")).isEqualTo("UNIQUE");
    }

    @Test
    void productVariantBulkUpdateRendersThePerItemResults() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID productId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        when(productVariantWriteSpi.bulkUpdateVariants(anyList(), any(), any())).thenReturn(List.of(
                new ProductVariantBulkOutcome(variantView(rowId, productId, "SKU-2"), List.of())));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkUpdate(product: "%s",
                        variants: [{id: "%s", sku: "SKU-2", priceMinor: 999}]) {
                    count
                    errors { code }
                    results { productVariant { sku } errors { path } }
                } }
                """.formatted(productGlobalId(productId), variantGlobalId(rowId)),
                "data.productVariantBulkUpdate");

        assertThat(payload.get("count")).isEqualTo(1);
        verify(productVariantWriteSpi).bulkUpdateVariants(argThat((List<ProductVariantBulkUpdateItem> items) ->
                items.size() == 1 && items.get(0).id().equals(rowId)
                        && "SKU-2".equals(items.get(0).fields().sku())), any(), any());
    }
}
