package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductCreateCommand;
import com.marketplace.catalog.spi.ProductBulkOutcome;
import com.marketplace.catalog.spi.ProductMutationError;
import com.marketplace.catalog.spi.ProductMutationErrorCode;
import com.marketplace.catalog.spi.ProductMutationResult;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.catalog.spi.ProductUpdateCommand;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productCreate}, {@code productUpdate}, {@code productDelete}
 * and {@code productBulkDelete} mutation roots, the agent-2 packet):
 * the GraphQL adapter's DGS proof — REAL mutation documents against
 * the REAL SDL (the agent-2 SDL fragment's own declared roots) and
 * the REAL DGS wiring with no web layer and no containers. The owning
 * write use case is mocked at the declared {@code ProductWriteSpi}
 * seam; the full-stack proof (the real product rows over the
 * Flyway-migrated schema, the real {@code @PreAuthorize} PROVIDER
 * gate, the real provider-ownership law and the real soft-delete SQL)
 * lives in {@code ProductGraphQlIntegrationTest}'s mutation legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code ProductCreateInput}/{@code ProductInput} bindings by name);
 * the input's {@code productType} reference decodes through the typed
 * global-ID codec; the payload shapes serialize exactly as Saleor
 * declares them; the domain error entries map onto the Saleor
 * {@code ProductError} vocabulary; the transport-level negatives (a
 * malformed or mistyped global ID, a keyless call, the
 * declared-but-unowned {@code externalReference} argument) answer the
 * classified VAL-001 GraphQL error family the read roots established
 * — never a silent no-op; and the bulk delete's count rides the
 * owner's own honest number.</p>
 */
@SpringBootTest(classes = ProductMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductMutationDataFetcherDgsTest {

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

    private static ProductView productView(UUID id, String name, UUID productTypeId, String status) {
        return new ProductView(id, name, productTypeId, status);
    }

    /** The typed global IDs the codec emits — base64("Product:<uuid>") / base64("ProductType:<uuid>"). */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String productTypeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by name
     * (including the platform-extension price facts), the input's
     * productType reference decodes through the typed global-ID codec,
     * the command reaches the owner's seam WITH the caller's
     * authentication, and the payload serializes the empty error list
     * with the created projection's typed global ID.
     */
    @Test
    void productCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID createdId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        when(productWriteSpi.createProduct(any(), any())).thenReturn(
                ProductMutationResult.success(productView(createdId, "Fresh Widget", typeId, "DRAFT")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productCreate(input: {name: "Fresh Widget", description: "Fresh",
                        storeCategoryCode: "fresh-cat", priceMinor: 1250, currency: "SAR",
                        productType: "%s"}) {
                    errors { field message code }
                    product { id name productType status }
                } }
                """.formatted(productTypeGlobalId(typeId)), "data.productCreate");

        verify(productWriteSpi).createProduct(argThat((ProductCreateCommand command) ->
                "Fresh Widget".equals(command.name()) && "Fresh".equals(command.description())
                        && "fresh-cat".equals(command.storeCategoryCode())
                        && command.priceMinor() == 1250L && "SAR".equals(command.currency())
                        && typeId.equals(command.productTypeId())),
                argThat(authentication -> "dgs-provider-subject".equals(authentication.getName())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) payload.get("product");
        assertThat(product.get("id")).isEqualTo(productGlobalId(createdId));
        assertThat(product.get("name")).isEqualTo("Fresh Widget");
        assertThat(product.get("productType")).isEqualTo(productTypeGlobalId(typeId));
        assertThat(product.get("status")).isEqualTo("DRAFT");
    }

    /**
     * The domain validation outcome rides the payload's own errors
     * field — the registered "Mutation payload errors" ADAPT divergence:
     * the domain error entries map onto the Saleor ProductError
     * vocabulary (field null included, exactly as Saleor declares it).
     */
    @Test
    void productCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(productWriteSpi.createProduct(any(), any())).thenReturn(
                ProductMutationResult.failure(List.of(
                        new ProductMutationError("storeCategoryCode",
                                ProductMutationErrorCode.NOT_FOUND,
                                "No store category found for the code"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productCreate(input: {name: "Fresh Widget", storeCategoryCode: "missing",
                        priceMinor: 1250, currency: "SAR"}) {
                    errors { field message code }
                    product { id }
                } }
                """, "data.productCreate");

        assertThat(payload.get("product")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("storeCategoryCode");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
    }

    /**
     * A mistyped productType reference (ANOTHER GraphQL type's tag)
     * answers the codec's classified VAL-001 family at the boundary —
     * never a silent null reference.
     */
    @Test
    void productCreateWithAMistypedProductTypeAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String categoryTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productCreate(input: {name: "Fresh Widget", storeCategoryCode: "fresh-cat",
                        priceMinor: 1250, currency: "SAR", productType: "%s"}) {
                    errors { code }
                    product { id }
                } }
                """.formatted(categoryTaggedId), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productWriteSpi);
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam
     * with the caller's authentication.
     */
    @Test
    void productUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID rowId = UUID.randomUUID();
        when(productWriteSpi.updateProduct(any(UUID.class), any(), any())).thenReturn(
                ProductMutationResult.success(productView(rowId, "Refreshed", null, "ACTIVE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { code }
                    product { id name status }
                } }
                """.formatted(productGlobalId(rowId)), "data.productUpdate");

        verify(productWriteSpi).updateProduct(argThat((UUID id) -> id.equals(rowId)),
                argThat((ProductUpdateCommand command) ->
                        "Refreshed".equals(command.name()) && command.description() == null
                                && command.priceMinor() == null),
                argThat(authentication -> "dgs-provider-subject".equals(authentication.getName())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) payload.get("product");
        assertThat(product.get("id")).isEqualTo(productGlobalId(rowId));
        assertThat(product.get("name")).isEqualTo("Refreshed");
        assertThat(product.get("status")).isEqualTo("ACTIVE");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the ownership law's safe absence mapped onto the
     * Saleor vocabulary.
     */
    @Test
    void productUpdateOfAStrangersRowAnswersTheSafeAbsencePayloadError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(productWriteSpi.updateProduct(any(UUID.class), any(), any())).thenReturn(
                ProductMutationResult.failure(List.of(new ProductMutationError(
                        "id", ProductMutationErrorCode.NOT_FOUND, "No product found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { field code }
                    product { id }
                } }
                """.formatted(productGlobalId(UUID.randomUUID())), "data.productUpdate");

        assertThat(payload.get("product")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
        assertThat(errors.get(0).get("field")).isEqualTo("id");
    }

    /**
     * The declared-but-unowned {@code externalReference} argument (no
     * external-ID column) answers the classified GraphQL error family,
     * never a silently ignored argument.
     */
    @Test
    void productUpdateWithAnExternalReferenceAnswersTheClassifiedError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productUpdate(externalReference: "ext-1", id: "%s", input: {name: "X"}) {
                    errors { code }
                    product { id }
                } }
                """.formatted(productGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");
        org.mockito.Mockito.verifyNoInteractions(productWriteSpi);
    }

    /**
     * A keyless update call answers the classified validation error —
     * never a fabricated absence.
     */
    @Test
    void productUpdateWithoutAnIdAnswersTheClassifiedError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productUpdate(input: {name: "X"}) {
                    errors { code }
                    product { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("requires the id");
        org.mockito.Mockito.verifyNoInteractions(productWriteSpi);
    }

    /**
     * A malformed global ID answers the codec's classified VAL-001
     * GraphQL error family — never a silent null and never a raw UUID
     * fallback (the read roots' own codec contract).
     */
    @Test
    void productUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    product { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void productDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID rowId = UUID.randomUUID();
        when(productWriteSpi.deleteProduct(eq(rowId), any())).thenReturn(
                ProductMutationResult.success(productView(rowId, "Gone", null, "ACTIVE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productDelete(id: "%s") {
                    errors { code }
                    product { id name }
                } }
                """.formatted(productGlobalId(rowId)), "data.productDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) payload.get("product");
        assertThat(product.get("id")).isEqualTo(productGlobalId(rowId));
        assertThat(product.get("name")).isEqualTo("Gone");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void productBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(productWriteSpi.deleteProducts(anyList(), any())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(productGlobalId(first), productGlobalId(second)),
                "data.productBulkDelete");

        verify(productWriteSpi).deleteProducts(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)),
                argThat(authentication -> "dgs-provider-subject".equals(authentication.getName())));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void productBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-provider-subject", "PROVIDER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productWriteSpi);
    }
    // ------------------------------------------------------------------
    // the bulk create root (the agent-2 follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void productBulkCreateRendersThePerItemResults() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID typeId = UUID.randomUUID();
        when(productWriteSpi.bulkCreateProducts(anyList(), any(), any())).thenReturn(List.of(
                new ProductBulkOutcome(
                        new ProductView(UUID.randomUUID(), "Bulk Widget", typeId, "ACTIVE"), List.of()),
                new ProductBulkOutcome(null, List.of(new ProductMutationError(
                        "storeCategoryCode", ProductMutationErrorCode.NOT_FOUND,
                        "No store category found for the code")))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkCreate(products: [
                            {name: "Bulk Widget", productType: "%s", storeCategoryCode: "widgets",
                             priceMinor: 1250, currency: "SAR"},
                            {name: "Bulk Missing", productType: "%s", storeCategoryCode: "missing",
                             priceMinor: 1250, currency: "SAR"}],
                        errorPolicy: IGNORE_FAILED) {
                    count
                    errors { code }
                    results { product { name } errors { path message code } }
                } }
                """.formatted(productTypeGlobalId(typeId), productTypeGlobalId(typeId)),
                "data.productBulkCreate");

        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
        assertThat(results).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) results.get(0).get("product");
        assertThat(first.get("name")).isEqualTo("Bulk Widget");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemErrors = (List<Map<String, Object>>) results.get(1).get("errors");
        assertThat(itemErrors).hasSize(1);
        assertThat(itemErrors.get(0).get("path")).isEqualTo("products.1.storeCategoryCode");
        assertThat(itemErrors.get(0).get("code")).isEqualTo("NOT_FOUND");
    }
}
