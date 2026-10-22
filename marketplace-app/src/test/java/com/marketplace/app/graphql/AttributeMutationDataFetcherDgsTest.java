package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
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
 * {@code attributeCreate}, {@code attributeUpdate},
 * {@code attributeDelete} and {@code attributeBulkDelete} mutation
 * roots, the agent-2 packet): the GraphQL adapter's DGS proof — REAL
 * mutation documents against the REAL SDL (the agent-2 SDL fragment's
 * own declared roots) and the REAL DGS wiring with no web layer and no
 * containers. The owning write use case is mocked at the declared
 * {@code AttributeWriteSpi} seam; the full-stack proof (the real
 * registry writes over the Flyway-migrated schema, the real
 * {@code @PreAuthorize} ADMIN gate and the real soft-delete SQL) lives
 * in the app module's Testcontainers integration legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code AttributeCreateInput} binding by name, the Saleor
 * {@code inputType} widget vocabulary riding the read mapper's own
 * inverse onto the closed domain value shape); the payload shapes
 * serialize exactly as Saleor declares them ({@code errors} with
 * field/message/code plus the attribute projection carrying the typed
 * global ID and the {@code inputType}/{@code withChoices} renderings);
 * the domain error entries map onto the Saleor {@code AttributeError}
 * vocabulary; the transport-level negatives (a malformed or mistyped
 * global ID, a keyless call, the declared-but-unowned
 * {@code externalReference} argument) answer the classified VAL-001
 * GraphQL error family the read roots established — never a silent
 * no-op; and the bulk delete's count rides the owner's own honest
 * number.</p>
 */
@SpringBootTest(classes = AttributeMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class AttributeMutationDataFetcherDgsTest {

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

    // The attribute mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private AttributeWriteSpi attributeWriteSpi;

    // The attribute-value mutation roots' owning write seam (agent-2
    // packet) — the package slice wires every fetcher, so this seam
    // gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;

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

    private static AttributeView attributeView(UUID id, String name, String slug,
                                               CatalogAttributeType valueType) {
        return new AttributeView(id, name, slug, valueType);
    }

    /** The typed global ID the codec emits — base64("Attribute:<uuid>"). */
    private static String attributeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by
     * name, the Saleor {@code inputType} widget vocabulary rides the
     * read mapper's own inverse onto the closed domain value shape,
     * the command reaches the owner's seam, and the payload serializes
     * the empty error list with the created projection's typed global
     * ID and the read-side renderings ({@code inputType}/
     * {@code withChoices}).
     */
    @Test
    void attributeCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID createdId = UUID.randomUUID();
        when(attributeWriteSpi.createAttribute(any())).thenReturn(
                AttributeMutationResult.success(
                        attributeView(createdId, "Material", "material", CatalogAttributeType.CHOICE)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeCreate(input: {name: "Material", slug: "material", inputType: DROPDOWN}) {
                    errors { field message code }
                    attribute { id name slug inputType withChoices }
                } }
                """, "data.attributeCreate");

        verify(attributeWriteSpi).createAttribute(argThat(command ->
                "Material".equals(command.name()) && "material".equals(command.slug())
                        && command.valueType() == CatalogAttributeType.CHOICE));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(createdId));
        assertThat(attribute.get("name")).isEqualTo("Material");
        assertThat(attribute.get("slug")).isEqualTo("material");
        assertThat(attribute.get("inputType")).isEqualTo("DROPDOWN");
        assertThat(attribute.get("withChoices")).isEqualTo(true);
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor AttributeError
     * vocabulary.
     */
    @Test
    void attributeCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(attributeWriteSpi.createAttribute(any())).thenReturn(
                AttributeMutationResult.failure(List.of(
                        new AttributeMutationError("slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                                "An attribute with the slug already exists"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeCreate(input: {name: "Material", slug: "taken", inputType: PLAIN_TEXT}) {
                    errors { field message code }
                    attribute { id }
                } }
                """, "data.attributeCreate");

        assertThat(payload.get("attribute")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("slug");
        assertThat(errors.get(0).get("message")).isEqualTo("An attribute with the slug already exists");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam.
     */
    @Test
    void attributeUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(attributeWriteSpi.updateAttribute(any(UUID.class), any())).thenReturn(
                AttributeMutationResult.success(
                        attributeView(rowId, "Refreshed", "kept", CatalogAttributeType.NUMBER)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { code }
                    attribute { id name slug inputType }
                } }
                """.formatted(attributeGlobalId(rowId)), "data.attributeUpdate");

        verify(attributeWriteSpi).updateAttribute(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "Refreshed".equals(command.name()) && command.slug() == null));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(rowId));
        assertThat(attribute.get("name")).isEqualTo("Refreshed");
        assertThat(attribute.get("slug")).isEqualTo("kept");
        assertThat(attribute.get("inputType")).isEqualTo("NUMERIC");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the code mapped onto the Saleor vocabulary.
     */
    @Test
    void attributeUpdateOfAnUnknownRowAnswersThePayloadError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(attributeWriteSpi.updateAttribute(any(UUID.class), any())).thenReturn(
                AttributeMutationResult.failure(List.of(new AttributeMutationError(
                        "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { field code }
                    attribute { id }
                } }
                """.formatted(attributeGlobalId(UUID.randomUUID())), "data.attributeUpdate");

        assertThat(payload.get("attribute")).isNull();
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
    void attributeUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    attribute { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeWriteSpi);
    }

    /**
     * A mistyped but well-formed global ID (ANOTHER GraphQL type's tag)
     * answers the same classified family — the codec's per-callsite
     * expected-type check.
     */
    @Test
    void attributeUpdateWithAMistypedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String categoryTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeUpdate(id: "%s", input: {name: "X"}) {
                    errors { code }
                    attribute { id }
                } }
                """.formatted(categoryTaggedId), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeWriteSpi);
    }

    /**
     * The declared-but-unowned {@code externalReference} argument
     * answers the explicit classified error when sent — never a
     * silently ignored argument.
     */
    @Test
    void attributeDeleteWithAnExternalReferenceAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeDelete(externalReference: "ext-1") {
                    errors { code }
                    attribute { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");
        org.mockito.Mockito.verifyNoInteractions(attributeWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void attributeDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(attributeWriteSpi.deleteAttribute(rowId)).thenReturn(
                AttributeMutationResult.success(
                        attributeView(rowId, "Gone", "gone", CatalogAttributeType.BOOLEAN)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeDelete(id: "%s") {
                    errors { code }
                    attribute { id name slug inputType }
                } }
                """.formatted(attributeGlobalId(rowId)), "data.attributeDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(rowId));
        assertThat(attribute.get("name")).isEqualTo("Gone");
        assertThat(attribute.get("inputType")).isEqualTo("BOOLEAN");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void attributeBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(attributeWriteSpi.deleteAttributes(anyList())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(attributeGlobalId(first), attributeGlobalId(second)),
                "data.attributeBulkDelete");

        verify(attributeWriteSpi).deleteAttributes(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void attributeBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(attributeWriteSpi);
    }
}
