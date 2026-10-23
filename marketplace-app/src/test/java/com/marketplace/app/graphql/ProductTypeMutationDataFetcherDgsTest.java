package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeView;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
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
 * {@code productTypeCreate}, {@code productTypeUpdate},
 * {@code productTypeDelete} and {@code productTypeBulkDelete} mutation
 * roots, the agent-2 packet): the GraphQL adapter's DGS proof — REAL
 * mutation documents against the REAL SDL (the agent-2 SDL fragment's
 * own declared roots) and the REAL DGS wiring with no web layer and no
 * containers. The owning write use case is mocked at the declared
 * {@code ProductTypeWriteSpi} seam; the full-stack proof (the real
 * registry writes over the Flyway-migrated schema, the real
 * {@code @PreAuthorize} ADMIN gate and the real soft-delete SQL) lives
 * in {@code ProductTypeGraphQlIntegrationTest}'s mutation legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code ProductTypeInput} binding by name); the payload shapes
 * serialize exactly as Saleor declares them ({@code errors} with
 * field/message/code plus the product-type projection carrying the
 * typed global ID); the domain error entries map onto the Saleor
 * {@code ProductError} vocabulary; the transport-level negatives (a
 * malformed or mistyped global ID, a keyless call) answer the
 * classified VAL-001 GraphQL error family the read roots established —
 * never a silent no-op; and the bulk delete's count rides the owner's
 * own honest number.</p>
 */
@SpringBootTest(classes = ProductTypeMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductTypeMutationDataFetcherDgsTest {

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
    private com.marketplace.catalog.spi.CatalogSpi catalogSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductReadSpi productReadSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi variantReadSpi;

    // The product-variant mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
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

    // The voucher mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    // The product-type and product mutation roots' owning write seams
    // (agent-2 packet) — the package slice wires every fetcher, so
    // these seams get their mocks (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;


    // The attribute mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;

    // The attribute-value mutation roots' owning write seam (agent-2
    // packet) — the package slice wires every fetcher, so this seam
    // gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;

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

    private static ProductTypeView typeView(UUID id, String name, String slug) {
        return new ProductTypeView(id, name, slug);
    }

    /** The typed global ID the codec emits — base64("ProductType:<uuid>"). */
    private static String productTypeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by name,
     * the command reaches the owner's seam, and the payload serializes
     * the empty error list with the created projection's typed global ID.
     */
    @Test
    void productTypeCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID createdId = UUID.randomUUID();
        when(productTypeWriteSpi.createProductType(any())).thenReturn(
                ProductTypeMutationResult.success(typeView(createdId, "Widget", "widget")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeCreate(input: {name: "Widget", slug: "widget"}) {
                    errors { field message code }
                    productType { id name slug }
                } }
                """, "data.productTypeCreate");

        verify(productTypeWriteSpi).createProductType(argThat(command ->
                "Widget".equals(command.name()) && "widget".equals(command.slug())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> productType = (Map<String, Object>) payload.get("productType");
        assertThat(productType.get("id")).isEqualTo(productTypeGlobalId(createdId));
        assertThat(productType.get("name")).isEqualTo("Widget");
        assertThat(productType.get("slug")).isEqualTo("widget");
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor ProductError vocabulary.
     */
    @Test
    void productTypeCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(productTypeWriteSpi.createProductType(any())).thenReturn(
                ProductTypeMutationResult.failure(List.of(
                        new ProductTypeMutationError("slug", ProductTypeMutationErrorCode.ALREADY_EXISTS,
                                "A product type with the slug already exists"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeCreate(input: {name: "Widget", slug: "taken"}) {
                    errors { field message code }
                    productType { id }
                } }
                """, "data.productTypeCreate");

        assertThat(payload.get("productType")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("slug");
        assertThat(errors.get(0).get("message")).isEqualTo("A product type with the slug already exists");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam.
     */
    @Test
    void productTypeUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(productTypeWriteSpi.updateProductType(any(UUID.class), any())).thenReturn(
                ProductTypeMutationResult.success(typeView(rowId, "Refreshed", "kept")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { code }
                    productType { id name slug }
                } }
                """.formatted(productTypeGlobalId(rowId)), "data.productTypeUpdate");

        verify(productTypeWriteSpi).updateProductType(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "Refreshed".equals(command.name()) && command.slug() == null));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> productType = (Map<String, Object>) payload.get("productType");
        assertThat(productType.get("id")).isEqualTo(productTypeGlobalId(rowId));
        assertThat(productType.get("name")).isEqualTo("Refreshed");
        assertThat(productType.get("slug")).isEqualTo("kept");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the code mapped onto the Saleor vocabulary.
     */
    @Test
    void productTypeUpdateOfAnUnknownRowAnswersThePayloadError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(productTypeWriteSpi.updateProductType(any(UUID.class), any())).thenReturn(
                ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                        "id", ProductTypeMutationErrorCode.NOT_FOUND, "No product type found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { field code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(UUID.randomUUID())), "data.productTypeUpdate");

        assertThat(payload.get("productType")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
        assertThat(errors.get(0).get("field")).isEqualTo("id");
    }

    /**
     * A malformed global ID answers the codec's classified VAL-001
     * GraphQL error family — never a silent null and never a raw UUID
     * fallback (the read roots' own codec contract).
     */
    @Test
    void productTypeUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    productType { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productTypeWriteSpi);
    }

    /**
     * A mistyped but well-formed global ID (ANOTHER GraphQL type's tag)
     * answers the same classified family — the codec's per-callsite
     * expected-type check.
     */
    @Test
    void productTypeUpdateWithAMistypedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String categoryTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeUpdate(id: "%s", input: {name: "X"}) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(categoryTaggedId), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productTypeWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void productTypeDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(productTypeWriteSpi.deleteProductType(rowId)).thenReturn(
                ProductTypeMutationResult.success(typeView(rowId, "Gone", "gone")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeDelete(id: "%s") {
                    errors { code }
                    productType { id name slug }
                } }
                """.formatted(productTypeGlobalId(rowId)), "data.productTypeDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> productType = (Map<String, Object>) payload.get("productType");
        assertThat(productType.get("id")).isEqualTo(productTypeGlobalId(rowId));
        assertThat(productType.get("name")).isEqualTo("Gone");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void productTypeBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(productTypeWriteSpi.deleteProductTypes(anyList())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(productTypeGlobalId(first), productTypeGlobalId(second)),
                "data.productTypeBulkDelete");

        verify(productTypeWriteSpi).deleteProductTypes(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void productTypeBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productTypeWriteSpi);
    }
}
