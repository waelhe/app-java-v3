package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.pricing.spi.PromotionPage;
import com.marketplace.pricing.spi.PromotionSort;
import com.marketplace.pricing.spi.PromotionView;
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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P3 (the canonical Saleor plan §5-P3): the DGS data-fetcher layer proof
 * of the Saleor promotion read roots — the house containerless pattern
 * ({@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL GraphQL
 * documents against the REAL SDL and the REAL DGS wiring with no web
 * layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code PromotionCountableConnection}/{@code PromotionCountableEdge}
 * and the bounded {@code Promotion} type (Saleor's exact wire shape,
 * {@code totalCount} included) load through the DGS bridge. Then: the
 * {@code promotions}/{@code promotion} documents execute through the
 * real argument binding, the typed global-ID codec, the mapper and the
 * exception taxonomy resolver; the connection envelope serializes with
 * per-element keyset cursors from the SAME cursor strategy the
 * framework adapters use. The owning pricing use case is mocked at the
 * declared seam — the full-stack proof (real {@code @PreAuthorize}, the
 * real in-memory keyset scroll over the policy tables) lives in
 * {@code PromotionVoucherGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = PromotionDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class PromotionDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest precedent (the full seam union the slice
    // needs once every packet's fetchers live in the package).
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

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
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private DiscountReadSpi discountReadSpi;
    // The package slice wires every fetcher; every seam the slice's other
    // fetchers need gets its mock — the incomplete sibling fixes the
    // b4/b5 packets left behind (committed against a red CI) completed
    // here so the shared slice context can boot at all.
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;
    // The voucher mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

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

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-02T00:00:00Z");

    private static PromotionView promotionView(UUID id, String name) {
        return new PromotionView(id, name, true, START, END, CREATED, UPDATED);
    }

    /**
     * The {@code promotions} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — the declared-type proof
     * the context boot already started — and the bounded projection's
     * own facts ride the wire (ORDER type, ISO-8601 window/audit
     * instants).
     */
    @Test
    void promotionsSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID alphaId = UUID.randomUUID();
        UUID betaId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<PromotionView>> windowAt = position ->
                Window.from(List.of(promotionView(alphaId, "P3 Alpha"), promotionView(betaId, "P3 Beta")), index -> {
                    if (index == 0) {
                        return ScrollPosition.forward(Map.of(
                                "name", "P3 Alpha", "id", alphaId));
                    }
                    return ScrollPosition.forward(Map.of(
                            "name", "P3 Beta", "id", betaId));
                }, true);
        when(discountReadSpi.scrollPromotions(any(org.springframework.security.core.Authentication.class),
                        any(PromotionSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new PromotionPage(
                        windowAt.apply(ScrollPosition.keyset()), 3));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { promotions(first: 2) {
                    edges { node { id name type startDate endDate createdAt updatedAt } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.promotions");

        assertThat(connection.get("totalCount")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "P3 Alpha", "id", alphaId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "P3 Beta", "id", betaId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("P3 Alpha");
        assertThat(firstNode.get("type")).isEqualTo("ORDER");
        assertThat(firstNode.get("startDate")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(firstNode.get("endDate")).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(firstNode.get("createdAt")).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(firstNode.get("updatedAt")).isEqualTo("2026-10-02T00:00:00Z");
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Promotion:" + alphaId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The {@code promotion(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a mistyped raw
     * UUID answers the classified VAL-001 error through the real
     * exception resolver), and the owner read serializes through the
     * mapper.
     */
    @Test
    void promotionReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(discountReadSpi.findPromotionById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(promotionView(id, "P3 Alpha")));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Promotion:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> promotion = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { promotion(id: $id) { id name type startDate endDate createdAt updatedAt } }",
                "data.promotion", Map.of("id", globalId));

        assertThat(promotion.get("id")).isEqualTo(globalId);
        assertThat(promotion.get("name")).isEqualTo("P3 Alpha");
        assertThat(promotion.get("type")).isEqualTo("ORDER");
        assertThat(promotion.get("startDate")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(promotion.get("endDate")).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(promotion.get("createdAt")).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(promotion.get("updatedAt")).isEqualTo("2026-10-02T00:00:00Z");
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed
     * global ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();

        // The DGS executor surfaces the classified GraphQL error as a
        // QueryException carrying the resolver's mapped message.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { promotion(id: $id) { id } }",
                "$", Map.of("id", id.toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The owning use case's ADMIN denial maps to the AUTHZ-001 taxonomy
     * through the real exception resolver — the same classification the
     * full-stack integration test proves against the real
     * {@code @PreAuthorize}.
     */
    @Test
    void ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(discountReadSpi.findPromotionById(any(UUID.class), any(org.springframework.security.core.Authentication.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Promotion:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { promotion(id: $id) { id } }",
                "$", Map.of("id", globalId)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Administrator role required")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "AUTHZ-001")
                        .containsEntry("category", "authz"));
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op.
     */
    @Test
    void promotionsWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotions(first: 2, where: { ids: [\"x\"] }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void promotionsBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotions(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The externalReference boundary: the declared argument has no owning
     * column on this platform and answers the explicit classified error —
     * never a silent no-op, never a fabricated lookup.
     */
    @Test
    void promotionExternalReferenceAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotion(externalReference: \"ext-1\") { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");
    }

    /** No discriminator at all answers the explicit classified error — never a silent null. */
    @Test
    void promotionWithoutADiscriminatorAnswersTheExplicitClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotion { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("promotion requires an id");
    }

    /**
     * The optional policy-window columns cannot carry deterministic
     * keyset cursors under the house map-based cursor contract: the
     * START_DATE/END_DATE sorts answer the explicit classified error —
     * never a silent fallback to another sort.
     */
    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotions(first: 2, sortBy: { direction: ASC, field: START_DATE }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("START_DATE is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by a different sort is rejected for
     * the CREATED_AT scroll — the keyset mismatch, classified VAL-001
     * through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String nameCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("name", "P3 Alpha", "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { promotions(first: 1, sortBy: { direction: ASC, field: CREATED_AT }, after: $after) { totalCount } }",
                "$", Map.of("after", nameCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /** The unauthenticated guard: no security context answers the credentials error, never a silent read. */
    @Test
    void unauthenticatedCallerAnswersTheCredentialsError() {
        SecurityContextHolder.clearContext();
        // The fetcher's guard throws AuthenticationCredentialsNotFoundException;
        // the resolver's leakage policy (measured) masks unknown exceptions to
        // the generic internal sentence — the HTTP layer's 401 tripartite
        // contract (GraphQlSecurityIntegrationTest) remains the anonymous
        // boundary's own proof.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { promotions(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

}
