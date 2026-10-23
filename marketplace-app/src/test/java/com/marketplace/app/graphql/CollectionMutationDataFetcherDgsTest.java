package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.CollectionCreateCommand;
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationErrorCode;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.CollectionProductMove;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionUpdateCommand;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
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
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionCreate}, {@code collectionUpdate},
 * {@code collectionDelete}, {@code collectionBulkDelete},
 * {@code collectionAddProducts}, {@code collectionRemoveProducts},
 * {@code collectionReorderProducts} and {@code collectionTranslate}
 * mutation roots, packet agent-2): the GraphQL adapter's DGS proof —
 * REAL mutation documents against the REAL SDL (the agent-2 SDL
 * fragment's own declared roots) and the REAL DGS wiring with no web
 * layer and no containers. The owning write use cases are mocked at
 * the declared {@code CollectionWriteSpi}/{@code TranslationWriteSpi}
 * seams; the full-stack proof (the real registry writes over the
 * Flyway-migrated schema, the real {@code @PreAuthorize} ADMIN gate
 * and the real soft-delete SQL) lives in
 * {@code CollectionGraphQlIntegrationTest}'s mutation legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated
 * {@code CollectionCreateInput}/{@code CollectionInput}/
 * {@code MoveProductInput} binding by name); the payload shapes
 * serialize exactly as Saleor declares them ({@code errors} with
 * field/message/code — and the pinned {@code products} member for the
 * product-addressed outcomes — plus the collection projection carrying
 * the typed global ID); the domain error entries map onto the Saleor
 * {@code CollectionError} vocabulary; the transport-level negatives (a
 * malformed or mistyped global ID, a keyless call, the
 * declared-but-unowned {@code externalReference} argument) answer the
 * classified VAL-001 GraphQL error family the read roots established —
 * never a silent no-op; and the bulk delete's count rides the owner's
 * own honest number.</p>
 */
@SpringBootTest(classes = CollectionMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class CollectionMutationDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest/CategoryMutationDataFetcherDgsTest precedent.
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

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
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

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

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

    private static CollectionView collectionView(UUID id, String name, String slug, boolean published) {
        return new CollectionView(id, name, null, slug, published);
    }

    /** The typed global ID the codec emits — base64("Collection:<uuid>"). */
    private static String collectionGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Collection:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The typed global ID the codec emits — base64("Product:<uuid>"). */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by name,
     * the command reaches the owner's seam (with the initial membership's
     * decoded product ids), and the payload serializes the empty error
     * list with the created projection's typed global ID and the two
     * platform extension facts.
     */
    @Test
    void collectionCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID createdId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        when(collectionWriteSpi.createCollection(any())).thenReturn(
                CollectionMutationResult.success(collectionView(createdId, "Fresh", "fresh", true)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { collectionCreate(input: {name: "Fresh", slug: "fresh", isPublished: true,
                        products: ["%s"]}) {
                    errors { field message code }
                    collection { id name nameAr slug isPublished }
                } }
                """.formatted(productGlobalId(memberId)), "data.collectionCreate");

        verify(collectionWriteSpi).createCollection(argThat(command ->
                "Fresh".equals(command.name()) && "fresh".equals(command.slug())
                        && Boolean.TRUE.equals(command.published())
                        && command.productIds() != null && command.productIds().equals(List.of(memberId))));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) payload.get("collection");
        assertThat(collection.get("id")).isEqualTo(collectionGlobalId(createdId));
        assertThat(collection.get("name")).isEqualTo("Fresh");
        assertThat(collection.get("slug")).isEqualTo("fresh");
        assertThat(collection.get("nameAr")).isNull();
        assertThat(collection.get("isPublished")).isEqualTo(true);
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor CollectionError vocabulary
     * with the pinned {@code products} member carrying the typed global
     * IDs of the offending products.
     */
    @Test
    void collectionCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID duplicated = UUID.randomUUID();
        when(collectionWriteSpi.createCollection(any())).thenReturn(
                CollectionMutationResult.failure(List.of(new CollectionMutationError(
                        "products", CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM,
                        "The same product id appears more than once in the request",
                        List.of(duplicated)))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { collectionCreate(input: {name: "Fresh", slug: "fresh",
                        products: ["%s", "%s"]}) {
                    errors { field message code products }
                    collection { id }
                } }
                """.formatted(productGlobalId(duplicated), productGlobalId(duplicated)),
                "data.collectionCreate");

        assertThat(payload.get("collection")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("products");
        assertThat(errors.get(0).get("code")).isEqualTo("DUPLICATED_INPUT_ITEM");
        assertThat(errors.get(0).get("products")).isEqualTo(List.of(productGlobalId(duplicated)));
    }

    /**
     * The update through the real wiring: the typed global ID decodes
     * through the codec, the bounded input binds by name, and the
     * partial-preserve semantics ride the command's null fields.
     */
    @Test
    void collectionUpdateExecutesTheOwnerWithTheDecodedId() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(collectionWriteSpi.updateCollection(any(), any())).thenReturn(
                CollectionMutationResult.success(collectionView(id, "Amended", "fresh", true)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($id: ID!) { collectionUpdate(id: $id, input: {name: "Amended"}) {
                    errors { field message code }
                    collection { id name slug isPublished }
                } }
                """, "data.collectionUpdate", Map.of("id", collectionGlobalId(id)));

        verify(collectionWriteSpi).updateCollection(eq(id), argThat(command ->
                "Amended".equals(command.name())
                        && command.slug() == null && command.published() == null));
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) payload.get("collection");
        assertThat(collection.get("name")).isEqualTo("Amended");
        assertThat(collection.get("isPublished")).isEqualTo(true);
    }

    /**
     * The declared-but-unowned {@code externalReference} argument (no
     * external-ID column — the recorded divergence) answers the
     * classified GraphQL error family, never a silently ignored argument.
     */
    @Test
    void collectionUpdateWithAnExternalReferenceAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { collectionUpdate(externalReference: "ext-1", input: {name: "X"}) {
                    errors { field } collection { id }
                } }
                """, "data.collectionUpdate"))
                .isInstanceOf(Exception.class);
        verify(collectionWriteSpi, org.mockito.Mockito.never()).updateCollection(any(), any());
    }

    /**
     * The delete through the real wiring: the typed global ID decodes and
     * the last-known projection serializes in the payload.
     */
    @Test
    void collectionDeleteExecutesTheOwnerWithTheDecodedId() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(collectionWriteSpi.deleteCollection(any())).thenReturn(
                CollectionMutationResult.success(collectionView(id, "Gone", "gone", false)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath(
                "mutation($id: ID!) { collectionDelete(id: $id) { errors { field } collection { id name slug } } }",
                "data.collectionDelete", Map.of("id", collectionGlobalId(id)));

        verify(collectionWriteSpi).deleteCollection(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) payload.get("collection");
        assertThat(collection.get("name")).isEqualTo("Gone");
    }

    /** The bulk delete's count rides the owner's own honest number. */
    @Test
    void collectionBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(collectionWriteSpi.deleteCollections(anyList())).thenReturn(2);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { collectionBulkDelete(ids: ["%s", "%s", "%s"]) { count errors { field } } }
                """.formatted(collectionGlobalId(first), collectionGlobalId(second),
                        collectionGlobalId(unknown)), "data.collectionBulkDelete");

        assertThat(payload.get("count")).isEqualTo(2);
        assertThat(payload.get("errors")).isEqualTo(List.of());
        verify(collectionWriteSpi).deleteCollections(argThat((List<UUID> ids) ->
                ids.size() == 3 && ids.contains(first) && ids.contains(second) && ids.contains(unknown)));
    }

    /**
     * The add-products through the real wiring: both the collection key
     * and the member product keys decode through the typed global-ID
     * codec and reach the owner's seam as native UUIDs.
     */
    @Test
    void collectionAddProductsDecodesEveryTypedGlobalId() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionWriteSpi.addProducts(any(), anyList())).thenReturn(
                CollectionMutationResult.success(collectionView(collectionId, "Curated", "curated", true)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($collectionId: ID!) { collectionAddProducts(collectionId: $collectionId,
                        products: ["%s"]) {
                    errors { field message code }
                    collection { id name }
                } }
                """.formatted(productGlobalId(productId)), "data.collectionAddProducts",
                Map.of("collectionId", collectionGlobalId(collectionId)));

        verify(collectionWriteSpi).addProducts(collectionId, List.of(productId));
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) payload.get("collection");
        assertThat(collection.get("name")).isEqualTo("Curated");
    }

    /**
     * The remove-products through the real wiring — the same typed
     * global-ID decode contract over the idempotent remove.
     */
    @Test
    void collectionRemoveProductsDecodesEveryTypedGlobalId() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionWriteSpi.removeProducts(any(), anyList())).thenReturn(
                CollectionMutationResult.success(collectionView(collectionId, "Curated", "curated", true)));

        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($collectionId: ID!) { collectionRemoveProducts(collectionId: $collectionId,
                        products: ["%s"]) { errors { field } collection { id } } }
                """.formatted(productGlobalId(productId)), "data.collectionRemoveProducts",
                Map.of("collectionId", collectionGlobalId(collectionId)));

        verify(collectionWriteSpi).removeProducts(collectionId, List.of(productId));
    }

    /**
     * The reorder through the real wiring: the pinned relative moves bind
     * through the generated {@code MoveProductInput} and reach the
     * owner's seam with the decoded product ids and the verbatim sort
     * orders.
     */
    @Test
    void collectionReorderProductsBindsThePinnedRelativeMoves() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionWriteSpi.reorderProducts(any(), anyList())).thenReturn(
                CollectionMutationResult.success(collectionView(collectionId, "Curated", "curated", true)));

        dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($collectionId: ID!) { collectionReorderProducts(collectionId: $collectionId,
                        moves: [{productId: "%s", sortOrder: -1}, {productId: "%s"}]) {
                    errors { field } collection { id }
                } }
                """.formatted(productGlobalId(productId), productGlobalId(UUID.randomUUID())),
                "data.collectionReorderProducts", Map.of("collectionId", collectionGlobalId(collectionId)));

        verify(collectionWriteSpi).reorderProducts(argThat((java.util.UUID ownerId) -> ownerId.equals(collectionId)),
                argThat((List<CollectionProductMove> moves) -> moves.size() == 2
                        && moves.get(0).productId().equals(productId)
                        && Integer.valueOf(-1).equals(moves.get(0).sortOrder())
                        && moves.get(1).sortOrder() == null));
    }

    /**
     * The collection translate through the real wiring: the typed global
     * ID decodes and the bounded translation command reaches the
     * translation owner's seam with the AR/EN dimension.
     */
    @Test
    void collectionTranslateExecutesTheTranslationOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(translationWriteSpi.translateCollection(any(), any())).thenReturn(
                CollectionMutationResult.success(
                        new CollectionView(id, "منتقى", "منتقى", "curated", true)));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation($id: ID!) { collectionTranslate(id: $id, input: {name: "منتقى"},
                        languageCode: AR) {
                    errors { field message code }
                    collection { id name nameAr slug isPublished }
                } }
                """, "data.collectionTranslate", Map.of("id", collectionGlobalId(id)));

        verify(translationWriteSpi).translateCollection(eq(id),
                argThat(command -> "منتقى".equals(command.name()) && "AR".equals(command.languageCode())));
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) payload.get("collection");
        assertThat(collection.get("nameAr")).isEqualTo("منتقى");
    }

    /**
     * A malformed global ID on the delete answers the codec's classified
     * validation error through the real exception resolver — never a
     * silent null and never a raw UUID fallback.
     */
    @Test
    void aRawUuidOnTheDeleteAnswersTheCodecsClassifiedValidationError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "mutation($id: ID!) { collectionDelete(id: $id) { errors { field } } }",
                "data.collectionDelete", Map.of("id", UUID.randomUUID().toString())))
                .isInstanceOf(Exception.class);
        verify(collectionWriteSpi, org.mockito.Mockito.never()).deleteCollection(any());
    }

    /**
     * A keyless delete answers the classified validation error (never a
     * fabricated absence).
     */
    @Test
    void aKeylessDeleteAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "mutation { collectionDelete { errors { field } } }",
                "data.collectionDelete"))
                .isInstanceOf(Exception.class);
        verify(collectionWriteSpi, org.mockito.Mockito.never()).deleteCollection(any());
    }

    /**
     * A mistyped global ID (a Product tag on the collection root's
     * argument) answers the codec's per-callsite expected-type check —
     * never a silent cross-type acceptance.
     */
    @Test
    void aMistypedGlobalIdAnswersTheCodecsExpectedTypeCheck() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "mutation($id: ID!) { collectionDelete(id: $id) { errors { field } } }",
                "data.collectionDelete", Map.of("id", productGlobalId(productId))))
                .isInstanceOf(Exception.class);
        verify(collectionWriteSpi, org.mockito.Mockito.never()).deleteCollection(any());
    }
}
