package com.marketplace.app.graphql;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The bulk-mutation list bound's DGS proof (the G4 review on PR #723:
 * finding 19 — "add a configurable limit for bulk-operation list item
 * counts, apply it before creating commands or iterating items, reject
 * requests that exceed it, and test the at-limit and over-limit
 * cases").
 *
 * <p>The bound is {@code marketplace.graphql.bulk.max-list-items}
 * ({@code GraphQlProperties.Bulk}, default 200 — the write-surface
 * twin of the pagination hard maximum). This slice binds a SMALL
 * bound (2) through the test's own property so both sides of the
 * boundary are cheap to exercise: a request whose list EQUALS the
 * bound executes the owning seam (the honest at-limit case), and a
 * request one item beyond it is rejected BEFORE any command is built
 * or any item is iterated — the classified validation family the
 * other transport-level guards answer (never a silent truncation),
 * with the owning write seam provably untouched.</p>
 *
 * <p>The whole-package slice and the full mocked-seam set follow the
 * {@code ProductMutationDataFetcherDgsTest} precedent; the at-limit
 * execution semantics themselves are that test's own subject and are
 * not duplicated here beyond the one at-limit proof the review
 * asked for.</p>
 */
@SpringBootTest(classes = BulkMutationLimitsDgsTest.GraphQLAdapterSlice.class,
        properties = "marketplace.graphql.bulk.max-list-items=2")
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class BulkMutationLimitsDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the ProductMutationDataFetcherDgsTest precedent (the full set, so the
    // shared context boots).
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
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi variantReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;
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
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
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

    /** The typed global IDs the codec emits — base64("<Type>:<uuid>"). */
    private static String globalId(String type, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((type + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String productTypeGlobalId(UUID id) {
        return globalId("ProductType", id);
    }

    private static String productGlobalId(UUID id) {
        return globalId("Product", id);
    }

    private static String attributeGlobalId(UUID id) {
        return globalId("Attribute", id);
    }

    // ------------------------------------------------------------------
    // the at-limit case (the review's first required test)
    // ------------------------------------------------------------------

    /**
     * A list that EQUALS the configured bound is legitimate work, not an
     * attack: the owning seam executes it whole (the honest at-limit
     * case). Bound = 2 here; two items reach the owner as two commands.
     */
    @Test
    void productBulkCreateAtTheConfiguredBoundExecutesTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID typeId = UUID.randomUUID();
        when(productWriteSpi.bulkCreateProducts(anyList(), any(), any())).thenReturn(java.util.List.of(
                new com.marketplace.catalog.spi.ProductBulkOutcome(
                        new com.marketplace.catalog.spi.ProductView(
                                UUID.randomUUID(), "At Bound Widget", typeId, "ACTIVE"), List.of()),
                new com.marketplace.catalog.spi.ProductBulkOutcome(
                        new com.marketplace.catalog.spi.ProductView(
                                UUID.randomUUID(), "At Bound Widget 2", typeId, "ACTIVE"), List.of())));

        java.util.Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkCreate(products: [
                            {name: "At Bound Widget", productType: "%s", storeCategoryCode: "widgets",
                             priceMinor: 1250, currency: "SAR"},
                            {name: "At Bound Widget 2", productType: "%s", storeCategoryCode: "widgets",
                             priceMinor: 1250, currency: "SAR"}]) {
                    count
                    errors { code }
                } }
                """.formatted(productTypeGlobalId(typeId), productTypeGlobalId(typeId)),
                "data.productBulkCreate");

        assertThat(payload.get("count")).isEqualTo(2);
        verify(productWriteSpi).bulkCreateProducts(
                org.mockito.ArgumentMatchers.argThat(commands -> commands.size() == 2), any(), any());
    }

    // ------------------------------------------------------------------
    // the over-limit case across the bounded bulk roots (the review's
    // second required test, applied to productBulkCreate AND the
    // corresponding bulk operations in the other two fetchers)
    // ------------------------------------------------------------------

    /** productBulkCreate: three items against the bound of two. */
    @Test
    void productBulkCreateBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        UUID typeId = UUID.randomUUID();
        String item = """
                {name: "Over Bound", productType: "%s", storeCategoryCode: "widgets",
                 priceMinor: 1250, currency: "SAR"}""".formatted(productTypeGlobalId(typeId));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkCreate(products: [%s, %s, %s]) { count errors { code } } }
                """.formatted(item, item, item), "data.productBulkCreate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productBulkCreate rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(productWriteSpi);
    }

    /** productBulkDelete: three ids against the bound of two. */
    @Test
    void productBulkDeleteBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String ids = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> "\"" + productGlobalId(UUID.randomUUID()) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productBulkDelete(ids: [%s]) { count errors { code } } }
                """.formatted(ids), "data.productBulkDelete"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productBulkDelete rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(productWriteSpi);
    }

    /** attributeBulkCreate: three items against the bound of two. */
    @Test
    void attributeBulkCreateBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String item = "{name: \"Over Bound Attribute\", slug: \"over-bound-" + UUID.randomUUID()
                .toString().substring(0, 8) + "\"}";

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeBulkCreate(attributes: [%s, %s, %s]) { count errors { code } } }
                """.formatted(item, item, item), "data.attributeBulkCreate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("attributeBulkCreate rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(attributeWriteSpi);
    }

    /** attributeBulkUpdate: three items against the bound of two. */
    @Test
    void attributeBulkUpdateBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String item = "{id: \"" + attributeGlobalId(UUID.randomUUID())
                + "\", fields: {name: \"Over Bound\"}}";

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeBulkUpdate(attributes: [%s, %s, %s]) { count errors { code } } }
                """.formatted(item, item, item), "data.attributeBulkUpdate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("attributeBulkUpdate rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(attributeWriteSpi);
    }

    /** attributeBulkDelete: three ids against the bound of two. */
    @Test
    void attributeBulkDeleteBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String ids = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> "\"" + attributeGlobalId(UUID.randomUUID()) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeBulkDelete(ids: [%s]) { count errors { code } } }
                """.formatted(ids), "data.attributeBulkDelete"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("attributeBulkDelete rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(attributeWriteSpi);
    }

    /** productVariantBulkCreate: three variants against the bound of two. */
    @Test
    void productVariantBulkCreateBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String productId = productGlobalId(UUID.randomUUID());
        String item = "{sku: \"over-bound\", name: \"Over Bound Variant\"}";

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkCreate(product: "%s", variants: [%s, %s, %s]) {
                    count errors { code } } }
                """.formatted(productId, item, item, item), "data.productVariantBulkCreate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productVariantBulkCreate rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(productVariantWriteSpi);
    }

    /** productVariantBulkUpdate: three variants against the bound of two. */
    @Test
    void productVariantBulkUpdateBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String productId = productGlobalId(UUID.randomUUID());
        String item = "{id: \"" + globalId("ProductVariant", UUID.randomUUID())
                + "\", sku: \"over-bound\", name: \"Over Bound Variant\"}";

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkUpdate(product: "%s", variants: [%s, %s, %s]) {
                    count errors { code } } }
                """.formatted(productId, item, item, item), "data.productVariantBulkUpdate"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productVariantBulkUpdate rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(productVariantWriteSpi);
    }

    /** productVariantBulkDelete: three ids against the bound of two. */
    @Test
    void productVariantBulkDeleteBeyondTheBoundIsRejectedBeforeTheOwner() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        String ids = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> "\"" + globalId("ProductVariant", UUID.randomUUID()) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productVariantBulkDelete(ids: [%s]) { count errors { code } } }
                """.formatted(ids), "data.productVariantBulkDelete"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productVariantBulkDelete rejects a list of 3 items")
                .hasMessageContaining("bulk list bound is 2");
        verifyNoInteractions(productVariantWriteSpi);
    }
}
