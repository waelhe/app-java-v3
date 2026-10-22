package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueView;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.marketplace.catalog.spi.TranslationAmendCommand;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): the GraphQL adapter's DGS proof — REAL mutation
 * documents against the REAL SDL (the agent-2 SDL fragment's own
 * declared roots) and the REAL DGS wiring with no web layer and no
 * containers. The owning translation use case is mocked at the
 * declared {@code TranslationWriteSpi} seam; the full-stack proof
 * (the real bilingual column writes over the Flyway-migrated schema
 * and the real {@code @PreAuthorize} ADMIN gate) lives in the
 * integration legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code TranslationInput}/{@code NameTranslationInput}/
 * {@code AttributeValueTranslationInput} binding by name AND the
 * generated {@code LanguageCodeEnum} binding); the payload shapes
 * serialize exactly as Saleor declares them ({@code errors} with
 * field/message/code over the {@code TranslationError} vocabulary
 * plus the family's own projection carrying the typed global ID);
 * the domain error entries map onto the Saleor vocabulary; and the
 * transport-level negatives (a malformed or mistyped global ID, a
 * keyless call) answer the classified VAL-001 GraphQL error family
 * the read roots established — never a silent no-op.</p>
 */
@SpringBootTest(classes = TranslationMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class TranslationMutationDataFetcherDgsTest {

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

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    @MockitoBean
    private TranslationWriteSpi translationWriteSpi;

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

    private static CategoryView categoryView(UUID id, String name, String nameAr, String slug) {
        return new CategoryView(id, name, nameAr, slug, 1);
    }

    private static AttributeView attributeView(UUID id, String name, String slug) {
        return new AttributeView(id, name, slug,
                com.marketplace.catalog.CatalogAttributeType.CHOICE);
    }

    /** The typed global IDs the codec emits. */
    private static String categoryGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
     * The happy category translation through the real wiring: the input
     * binds by name, the language enum binds, the command reaches the
     * owner's seam, and the payload serializes the empty error list
     * with the amended projection's bilingual facts.
     */
    @Test
    void categoryTranslateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(translationWriteSpi.translateCategory(any(UUID.class), any())).thenReturn(
                CategoryMutationResult.success(categoryView(rowId, "Widgets", "الأدوات", "widgets")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryTranslate(id: "%s", input: {name: "الأدوات"}, languageCode: AR) {
                    errors { field message code }
                    category { id name nameAr slug }
                } }
                """.formatted(categoryGlobalId(rowId)), "data.categoryTranslate");

        verify(translationWriteSpi).translateCategory(argThat((UUID id) -> id.equals(rowId)),
                argThat((TranslationAmendCommand command) ->
                        "الأدوات".equals(command.name()) && "AR".equals(command.languageCode())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) payload.get("category");
        assertThat(category.get("id")).isEqualTo(categoryGlobalId(rowId));
        assertThat(category.get("name")).isEqualTo("Widgets");
        assertThat(category.get("nameAr")).isEqualTo("الأدوات");
        assertThat(category.get("slug")).isEqualTo("widgets");
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor TranslationError
     * vocabulary.
     */
    @Test
    void categoryTranslateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(translationWriteSpi.translateCategory(any(UUID.class), any())).thenReturn(
                CategoryMutationResult.failure(List.of(new CategoryMutationError(
                        "id", CategoryMutationErrorCode.NOT_FOUND, "No category found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryTranslate(id: "%s", input: {name: "اسم"}, languageCode: AR) {
                    errors { field message code }
                    category { id }
                } }
                """.formatted(categoryGlobalId(UUID.randomUUID())), "data.categoryTranslate");

        assertThat(payload.get("category")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("id");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
    }

    /**
     * The English dimension addresses the EN column — the generated
     * enum's own second member binding through the real wiring.
     */
    @Test
    void attributeTranslateBindsTheEnglishDimension() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(translationWriteSpi.translateAttribute(any(UUID.class), any())).thenReturn(
                AttributeMutationResult.success(attributeView(rowId, "Colour", "color")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeTranslate(id: "%s", input: {name: "Colour"}, languageCode: EN) {
                    errors { code }
                    attribute { id name slug }
                } }
                """.formatted(attributeGlobalId(rowId)), "data.attributeTranslate");

        verify(translationWriteSpi).translateAttribute(argThat((UUID id) -> id.equals(rowId)),
                argThat((TranslationAmendCommand command) ->
                        "Colour".equals(command.name()) && "EN".equals(command.languageCode())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> attribute = (Map<String, Object>) payload.get("attribute");
        assertThat(attribute.get("id")).isEqualTo(attributeGlobalId(rowId));
        assertThat(attribute.get("name")).isEqualTo("Colour");
    }

    /**
     * The value translation's dual projection — the amended value's
     * own facts through the real wiring.
     */
    @Test
    void attributeValueTranslateSerializesTheValueProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(translationWriteSpi.translateAttributeValue(any(UUID.class), any())).thenReturn(
                AttributeValueMutationResult.success(
                        attributeView(UUID.randomUUID(), "Color", "color"),
                        new AttributeValueView(rowId, "Red", "red")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueTranslate(id: "%s", input: {name: "أحمر"}, languageCode: AR) {
                    errors { code }
                    attributeValue { id name slug }
                } }
                """.formatted(attributeValueGlobalId(rowId)), "data.attributeValueTranslate");

        verify(translationWriteSpi).translateAttributeValue(argThat((UUID id) -> id.equals(rowId)),
                argThat((TranslationAmendCommand command) ->
                        "أحمر".equals(command.name()) && "AR".equals(command.languageCode())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) payload.get("attributeValue");
        assertThat(value.get("id")).isEqualTo(attributeValueGlobalId(rowId));
        assertThat(value.get("name")).isEqualTo("Red");
        assertThat(value.get("slug")).isEqualTo("red");
    }

    /**
     * A malformed global ID answers the codec's classified VAL-001
     * GraphQL error family — never a silent null and never a raw UUID
     * fallback (the read roots' own codec contract).
     */
    @Test
    void categoryTranslateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryTranslate(id: "not-a-global-id", input: {name: "X"}, languageCode: AR) {
                    errors { code }
                    category { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(translationWriteSpi);
    }

    /**
     * A mistyped but well-formed global ID (ANOTHER GraphQL type's tag)
     * answers the same classified family — the codec's per-callsite
     * expected-type check.
     */
    @Test
    void attributeTranslateWithAMistypedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeTranslate(id: "%s", input: {name: "X"}, languageCode: EN) {
                    errors { code }
                    attribute { id }
                } }
                """.formatted(categoryGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(translationWriteSpi);
    }

    /**
     * A keyless call is rejected by the SDL's own {@code id: ID!}
     * contract — GraphQL validation itself answers before the resolver
     * runs (never a fabricated absence, never a NullPointerException).
     */
    @Test
    void attributeValueTranslateWithoutAnIdIsRejectedBySchemaValidation() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { attributeValueTranslate(input: {name: "X"}, languageCode: AR) {
                    errors { code }
                    attributeValue { id }
                } }
                """, "$"))
                .isInstanceOf(Exception.class);
        org.mockito.Mockito.verifyNoInteractions(translationWriteSpi);
    }

    /** An unknown enum value is rejected by GraphQL validation itself — never a silent fallback. */
    @Test
    void anUnknownLanguageCodeIsRejectedBySchemaValidation() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryTranslate(id: "%s", input: {name: "X"}, languageCode: FR) {
                    errors { code }
                    category { id }
                } }
                """.formatted(categoryGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(Exception.class);
        org.mockito.Mockito.verifyNoInteractions(translationWriteSpi);
    }
}
