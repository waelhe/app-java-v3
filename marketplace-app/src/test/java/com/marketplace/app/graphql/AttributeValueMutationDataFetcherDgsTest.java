package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueView;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductWriteSpi;
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
 * {@code attributeValueCreate}, {@code attributeValueUpdate},
 * {@code attributeValueDelete} and {@code attributeValueBulkDelete}
 * mutation roots, the agent-2 packet): the GraphQL adapter's DGS proof
 * — REAL mutation documents against the REAL SDL (the agent-2 SDL
 * fragment's own declared roots) and the REAL DGS wiring with no web
 * layer and no containers. The owning write use case is mocked at the
 * declared {@code AttributeValueWriteSpi} seam; the full-stack proof
 * (the real registry writes over the Flyway-migrated schema, the real
 * {@code @PreAuthorize} ADMIN gate and the real soft-delete SQL) lives
 * in the app module's Testcontainers integration legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code AttributeValueCreateInput} binding by name); the payloads
 * serialize exactly as Saleor declares them — the DUAL projection
 * ({@code attributeValue} AND the owning {@code attribute}) with
 * typed global IDs and the empty {@code errors} list; the domain
 * error entries map onto the Saleor {@code AttributeError}
 * vocabulary; the transport-level negatives (a malformed or mistyped
 * global ID, a keyless call, the declared-but-unowned
 * {@code externalReference} argument) answer the classified VAL-001
 * GraphQL error family the read roots established — never a silent
 * no-op; and the bulk delete's count rides the owner's own honest
 * number.</p>
 */
@SpringBootTest(classes = AttributeValueMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class AttributeValueMutationDataFetcherDgsTest {

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
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private DiscountReadSpi discountReadSpi;

    @MockitoBean
    private VoucherWriteSpi voucherWriteSpi;

    @MockitoBean
    private ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private ProductWriteSpi productWriteSpi;

    // The attribute mutation roots' owning write seams (agent-2 packet) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the b5-a sibling-test precedent).
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

    private static AttributeView parentView(UUID id) {
        return new AttributeView(id, "Material", "material", CatalogAttributeType.CHOICE);
    }

    private static AttributeValueView valueView(UUID id, String name, String slug) {
        return new AttributeValueView(id, name, slug);
    }

    /** The typed global ID the codec emits — base64("Attribute:<uuid>"). */
    private static String attributeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The typed global ID the codec emits — base64("AttributeValue:<uuid>"). */
    private static String attributeValueGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("AttributeValue:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the owning attribute's
     * typed global ID decodes at the boundary, the input binds by name,
     * the command reaches the owner's seam, and the payload serializes
     * BOTH projections — the value's typed global ID and the owning
     * attribute's own projection.
     */
    @Test
    void attributeValueCreateExecutesTheOwnerAndSerializesTheDualProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID parentId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        when(attributeValueWriteSpi.createAttributeValue(any(UUID.class), any())).thenReturn(
                AttributeValueMutationResult.success(parentView(parentId),
                        valueView(createdId, "Small", "small")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueCreate(attribute: "%s", input: {name: "Small", slug: "small"}) {
                    errors { field message code }
                    attribute { id name slug }
                    attributeValue { id name slug }
                } }
                """.formatted(attributeGlobalId(parentId)), "data.attributeValueCreate");

        verify(attributeValueWriteSpi).createAttributeValue(argThat((UUID id) -> id.equals(parentId)),
                argThat(command -> "Small".equals(command.name()) && "small".equals(command.slug())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attributeValue = (Map<String, Object>) payload.get("attributeValue");
        assertThat(attributeValue.get("id")).isEqualTo(attributeValueGlobalId(createdId));
        assertThat(attributeValue.get("name")).isEqualTo("Small");
        assertThat(attributeValue.get("slug")).isEqualTo("small");
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(parentId));
        assertThat(attribute.get("slug")).isEqualTo("material");
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor AttributeError
     * vocabulary.
     */
    @Test
    void attributeValueCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(attributeValueWriteSpi.createAttributeValue(any(UUID.class), any())).thenReturn(
                AttributeValueMutationResult.failure(List.of(
                        new AttributeMutationError("attribute", AttributeMutationErrorCode.INVALID,
                                "Allowed values can only be added to CHOICE attributes"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueCreate(attribute: "%s", input: {name: "Small", slug: "small"}) {
                    errors { field message code }
                    attributeValue { id }
                } }
                """.formatted(attributeGlobalId(UUID.randomUUID())), "data.attributeValueCreate");

        assertThat(payload.get("attributeValue")).isNull();
        assertThat(payload.get("attribute")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("attribute");
        assertThat(errors.get(0).get("message")).isEqualTo("Allowed values can only be added to CHOICE attributes");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID");
    }

    /**
     * The happy update through the real wiring: the value's typed global
     * ID decodes at the boundary and the partial command reaches the
     * seam; the payload serializes both projections.
     */
    @Test
    void attributeValueUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID parentId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        when(attributeValueWriteSpi.updateAttributeValue(any(UUID.class), any())).thenReturn(
                AttributeValueMutationResult.success(parentView(parentId),
                        valueView(rowId, "Refreshed", "kept")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { code }
                    attribute { slug }
                    attributeValue { id name slug }
                } }
                """.formatted(attributeValueGlobalId(rowId)), "data.attributeValueUpdate");

        verify(attributeValueWriteSpi).updateAttributeValue(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "Refreshed".equals(command.name()) && command.slug() == null));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attributeValue = (Map<String, Object>) payload.get("attributeValue");
        assertThat(attributeValue.get("id")).isEqualTo(attributeValueGlobalId(rowId));
        assertThat(attributeValue.get("name")).isEqualTo("Refreshed");
        assertThat(attributeValue.get("slug")).isEqualTo("kept");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the code mapped onto the Saleor vocabulary.
     */
    @Test
    void attributeValueUpdateOfAnUnknownRowAnswersThePayloadError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(attributeValueWriteSpi.updateAttributeValue(any(UUID.class), any())).thenReturn(
                AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                        "id", AttributeMutationErrorCode.NOT_FOUND,
                        "No attribute value found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { field code }
                    attributeValue { id }
                } }
                """.formatted(attributeValueGlobalId(UUID.randomUUID())), "data.attributeValueUpdate");

        assertThat(payload.get("attributeValue")).isNull();
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
    void attributeValueUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    attributeValue { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeValueWriteSpi);
    }

    /**
     * A mistyped but well-formed global ID (the ATTRIBUTE definition
     * type's tag where the VALUE type is expected) answers the same
     * classified family — the codec's per-callsite expected-type check.
     */
    @Test
    void attributeValueUpdateWithAMistypedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueUpdate(id: "%s", input: {name: "X"}) {
                    errors { code }
                    attributeValue { id }
                } }
                """.formatted(attributeGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeValueWriteSpi);
    }

    /**
     * The declared-but-unowned {@code externalReference} argument
     * answers the explicit classified error when sent — never a
     * silently ignored argument.
     */
    @Test
    void attributeValueDeleteWithAnExternalReferenceAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueDelete(externalReference: "ext-1") {
                    errors { code }
                    attributeValue { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");
        org.mockito.Mockito.verifyNoInteractions(attributeValueWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload with the owning
     * attribute's own projection.
     */
    @Test
    void attributeValueDeleteSerializesTheLastKnownDualProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID parentId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        when(attributeValueWriteSpi.deleteAttributeValue(rowId)).thenReturn(
                AttributeValueMutationResult.success(parentView(parentId),
                        valueView(rowId, "Gone", "gone")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueDelete(id: "%s") {
                    errors { code }
                    attribute { slug }
                    attributeValue { id name slug }
                } }
                """.formatted(attributeValueGlobalId(rowId)), "data.attributeValueDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attributeValue = (Map<String, Object>) payload.get("attributeValue");
        assertThat(attributeValue.get("id")).isEqualTo(attributeValueGlobalId(rowId));
        assertThat(attributeValue.get("name")).isEqualTo("Gone");
        assertThat(attributeValue.get("slug")).isEqualTo("gone");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void attributeValueBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(attributeValueWriteSpi.deleteAttributeValues(anyList())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(attributeValueGlobalId(first), attributeValueGlobalId(second)),
                "data.attributeValueBulkDelete");

        verify(attributeValueWriteSpi).deleteAttributeValues(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void attributeValueBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeValueWriteSpi);
    }
}
