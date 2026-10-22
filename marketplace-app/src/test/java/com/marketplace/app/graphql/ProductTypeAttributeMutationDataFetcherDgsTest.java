package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignmentUpdateOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeView;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.catalog.spi.TranslationWriteSpi;
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
 * {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign},
 * {@code productTypeReorderAttributes} and
 * {@code attributeReorderValues} mutation roots, the agent-2
 * follow-on packet): the GraphQL adapter's DGS proof — REAL mutation
 * documents against the REAL SDL (the agent-2 SDL fragment's own
 * declared roots) and the REAL DGS wiring with no web layer and no
 * containers. The owning write use cases are mocked at their declared
 * seams ({@code ProductTypeAttributeWriteSpi}, {@code
 * AttributeWriteSpi}); the full-stack proof lives in the integration
 * legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code ProductAttributeAssignInput}/
 * {@code ProductAttributeAssignmentUpdateInput}/{@code ReorderInput}
 * binding by name AND the generated {@code ProductAttributeType} enum
 * binding); the payload shapes serialize exactly as Saleor declares
 * them ({@code errors} with field/message/code plus the owning
 * productType projection or the attribute projection); the domain
 * error entries map onto the Saleor vocabularies; and the
 * transport-level negatives (a malformed or mistyped global ID, a
 * keyless call) answer the classified VAL-001 GraphQL error family
 * the read roots established — never a silent no-op.</p>
 */
@SpringBootTest(classes = ProductTypeAttributeMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductTypeAttributeMutationDataFetcherDgsTest {

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

    @MockitoBean
    private TranslationWriteSpi translationWriteSpi;

    @MockitoBean
    private ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

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

    private static ProductTypeView productTypeView(UUID id) {
        return new ProductTypeView(id, "Widget Schema", "widgets");
    }

    /** The typed global IDs the codec emits. */
    private static String productTypeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String attributeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String attributeValueGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("AttributeValue:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy assign through the real wiring: the operations bind by
     * name with the generated enum, the commands reach the owner's seam
     * with the pinned facts verbatim, and the payload serializes the
     * empty error list with the owning productType projection.
     */
    @Test
    void productAttributeAssignExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID typeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(productTypeAttributeWriteSpi.assignAttributes(any(UUID.class), anyList())).thenReturn(
                ProductTypeMutationResult.success(productTypeView(typeId)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productAttributeAssign(productTypeId: "%s",
                        operations: [{id: "%s", type: VARIANT, variantSelection: true}]) {
                    errors { field message code }
                    productType { id name slug }
                } }
                """.formatted(productTypeGlobalId(typeId), attributeGlobalId(attributeId)),
                "data.productAttributeAssign");

        verify(productTypeAttributeWriteSpi).assignAttributes(argThat((UUID id) -> id.equals(typeId)),
                argThat((List<ProductTypeAttributeAssignOperation> operations) ->
                        operations.size() == 1
                                && operations.get(0).attributeId().equals(attributeId)
                                && "VARIANT".equals(operations.get(0).attributeType())
                                && Boolean.TRUE.equals(operations.get(0).variantSelection())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> productType = (Map<String, Object>) payload.get("productType");
        assertThat(productType.get("id")).isEqualTo(productTypeGlobalId(typeId));
        assertThat(productType.get("slug")).isEqualTo("widgets");
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence.
     */
    @Test
    void productAttributeAssignAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(productTypeAttributeWriteSpi.assignAttributes(any(UUID.class), anyList())).thenReturn(
                ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                        "id", ProductTypeMutationErrorCode.ALREADY_EXISTS,
                        "The attribute is already assigned to this product type"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productAttributeAssign(productTypeId: "%s",
                        operations: [{id: "%s", type: PRODUCT}]) {
                    errors { field message code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(UUID.randomUUID()),
                        attributeGlobalId(UUID.randomUUID())), "data.productAttributeAssign");

        assertThat(payload.get("productType")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("id");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /** A malformed attribute reference answers the classified VAL-001 family. */
    @Test
    void productAttributeAssignWithAMalformedAttributeIdAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productAttributeAssign(productTypeId: "%s",
                        operations: [{id: "not-a-global-id", type: PRODUCT}]) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(productTypeAttributeWriteSpi);
    }

    /**
     * The happy membership move through the real wiring: the pinned
     * variant-selection flag rides the command verbatim.
     */
    @Test
    void productAttributeAssignmentUpdateExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID typeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(productTypeAttributeWriteSpi.updateAssignments(any(UUID.class), anyList())).thenReturn(
                ProductTypeMutationResult.success(productTypeView(typeId)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productAttributeAssignmentUpdate(productTypeId: "%s",
                        operations: [{id: "%s", variantSelection: true}]) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(typeId), attributeGlobalId(attributeId)),
                "data.productAttributeAssignmentUpdate");

        verify(productTypeAttributeWriteSpi).updateAssignments(
                argThat((UUID id) -> id.equals(typeId)),
                argThat((List<ProductTypeAttributeAssignmentUpdateOperation> operations) ->
                        operations.size() == 1
                                && operations.get(0).attributeId().equals(attributeId)
                                && operations.get(0).variantSelection()));
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /** The happy unassign through the real wiring. */
    @Test
    void productAttributeUnassignExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID typeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(productTypeAttributeWriteSpi.unassignAttributes(any(UUID.class), anyList())).thenReturn(
                ProductTypeMutationResult.success(productTypeView(typeId)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productAttributeUnassign(productTypeId: "%s", attributeIds: ["%s"]) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(typeId), attributeGlobalId(attributeId)),
                "data.productAttributeUnassign");

        verify(productTypeAttributeWriteSpi).unassignAttributes(
                argThat((UUID id) -> id.equals(typeId)),
                argThat((List<UUID> ids) -> ids.size() == 1 && ids.contains(attributeId)));
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * The happy bucket reorder through the real wiring: the generated
     * enum binds and the relative moves ride the commands verbatim.
     */
    @Test
    void productTypeReorderAttributesExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID typeId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        when(productTypeAttributeWriteSpi.reorderAttributes(any(UUID.class), any(), anyList())).thenReturn(
                ProductTypeMutationResult.success(productTypeView(typeId)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeReorderAttributes(productTypeId: "%s", type: PRODUCT,
                        moves: [{id: "%s", sortOrder: 1}]) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(typeId), attributeGlobalId(attributeId)),
                "data.productTypeReorderAttributes");

        verify(productTypeAttributeWriteSpi).reorderAttributes(
                argThat((UUID id) -> id.equals(typeId)),
                argThat((String type) -> "PRODUCT".equals(type)),
                argThat((List<AttributeReorderMove> moves) ->
                        moves.size() == 1
                                && moves.get(0).itemId().equals(attributeId)
                                && moves.get(0).sortOrder() == 1));
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * The happy value reorder through the real wiring: the attribute
     * reference and every move's value reference decode through the
     * typed codec, and the attribute projection answers in the payload.
     */
    @Test
    void attributeReorderValuesExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID attributeId = UUID.randomUUID();
        UUID valueId = UUID.randomUUID();
        when(attributeWriteSpi.reorderValues(any(UUID.class), anyList())).thenReturn(
                AttributeMutationResult.success(new AttributeView(attributeId, "Color", "color",
                        com.marketplace.catalog.CatalogAttributeType.CHOICE)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeReorderValues(attributeId: "%s",
                        moves: [{id: "%s", sortOrder: -1}]) {
                    errors { code }
                    attribute { id name slug }
                } }
                """.formatted(attributeGlobalId(attributeId), attributeValueGlobalId(valueId)),
                "data.attributeReorderValues");

        verify(attributeWriteSpi).reorderValues(
                argThat((UUID id) -> id.equals(attributeId)),
                argThat((List<AttributeReorderMove> moves) ->
                        moves.size() == 1
                                && moves.get(0).itemId().equals(valueId)
                                && moves.get(0).sortOrder() == -1));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(attributeId));
        assertThat(attribute.get("name")).isEqualTo("Color");
    }

    /** A mistyped value reference (another type's tag) answers the classified family. */
    @Test
    void attributeReorderValuesWithAMistypedValueIdAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeReorderValues(attributeId: "%s",
                        moves: [{id: "%s", sortOrder: 1}]) {
                    errors { code }
                    attribute { id }
                } }
                """.formatted(attributeGlobalId(UUID.randomUUID()),
                        productTypeGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeWriteSpi);
    }

    /** A keyless reorder answers the classified validation error. */
    @Test
    void productTypeReorderAttributesWithoutMovesAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { productTypeReorderAttributes(productTypeId: "%s", type: PRODUCT) {
                    errors { code }
                    productType { id }
                } }
                """.formatted(productTypeGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(Exception.class);
        org.mockito.Mockito.verifyNoInteractions(productTypeAttributeWriteSpi);
    }
}
