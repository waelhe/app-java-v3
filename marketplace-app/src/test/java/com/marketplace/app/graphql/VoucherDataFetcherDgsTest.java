package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
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
import com.marketplace.pricing.spi.VoucherPage;
import com.marketplace.pricing.spi.VoucherSort;
import com.marketplace.pricing.spi.VoucherView;
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
 * of the Saleor voucher read roots — the house containerless pattern
 * ({@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL GraphQL
 * documents against the REAL SDL and the REAL DGS wiring with no web
 * layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code VoucherCountableConnection}/{@code VoucherCountableEdge} and
 * the bounded {@code Voucher} type (Saleor's exact wire shape,
 * {@code totalCount} included) load through the DGS bridge. Then: the
 * {@code vouchers}/{@code voucher} documents execute through the real
 * argument binding, the typed global-ID codec, the mapper and the
 * exception taxonomy resolver; the connection envelope serializes with
 * per-element keyset cursors from the SAME cursor strategy the
 * framework adapters use. The owning pricing use case is mocked at the
 * declared seam — the full-stack proof (real {@code @PreAuthorize}, the
 * real in-memory keyset scroll over the policy tables) lives in
 * {@code PromotionVoucherGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = VoucherDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class VoucherDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    // The token-rotation JDBC store rides the package component scan; its
    // owning seam is not under test in this slice — the interface mock
    // keeps the slice's containerless wiring intact (the seam-gets-its-mock law).
    @MockitoBean
    private RefreshTokenRotationStore refreshTokenRotationStore;

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
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private DiscountReadSpi discountReadSpi;

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
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
    @MockitoBean
    private AccountLifecycleSpi accountLifecycleSpi;
    @MockitoBean
    private AddressManagementSpi addressManagementSpi;
    @MockitoBean
    private CustomerTypeSpi customerTypeSpi;
    @MockitoBean
    private UserManagementSpi userManagementSpi;
    @MockitoBean
    private CmsPagePort cmsPagePort;
    @MockitoBean
    private CmsNavigationPort cmsNavigationPort;
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

    private static VoucherView voucherView(UUID id, String code) {
        return new VoucherView(id, code, "P3 Voucher " + code, "SAR",
                VoucherView.DiscountType.PERCENTAGE, 5L, 2L, true, START, END);
    }

    /**
     * The {@code vouchers} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — the declared-type proof
     * the context boot already started — and the bounded projection's
     * own facts ride the wire (the honest redemption count, the
     * per-user-limit flattening, the PERCENTAGE/FIXED vocabulary, the
     * row's own currency).
     */
    @Test
    void vouchersSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID alphaId = UUID.randomUUID();
        UUID betaId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<VoucherView>> windowAt = position ->
                Window.from(List.of(voucherView(alphaId, "P3TEST-ALPHA"), voucherView(betaId, "P3TEST-BETA")), index -> {
                    if (index == 0) {
                        return ScrollPosition.forward(Map.of(
                                "code", "P3TEST-ALPHA", "id", alphaId));
                    }
                    return ScrollPosition.forward(Map.of(
                            "code", "P3TEST-BETA", "id", betaId));
                }, true);
        when(discountReadSpi.scrollVouchers(any(org.springframework.security.core.Authentication.class),
                        any(VoucherSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new VoucherPage(
                        windowAt.apply(ScrollPosition.keyset()), 3));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { vouchers(first: 2) {
                    edges { node { id name code usageLimit used startDate endDate applyOncePerCustomer discountValueType currency } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.vouchers");

        assertThat(connection.get("totalCount")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "code", "P3TEST-ALPHA", "id", alphaId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "code", "P3TEST-BETA", "id", betaId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("code")).isEqualTo("P3TEST-ALPHA");
        assertThat(firstNode.get("name")).isEqualTo("P3 Voucher P3TEST-ALPHA");
        assertThat(firstNode.get("usageLimit")).isEqualTo(5);
        assertThat(firstNode.get("used")).isEqualTo(2);
        assertThat(firstNode.get("startDate")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(firstNode.get("endDate")).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(firstNode.get("applyOncePerCustomer")).isEqualTo(true);
        assertThat(firstNode.get("discountValueType")).isEqualTo("PERCENTAGE");
        assertThat(firstNode.get("currency")).isEqualTo("SAR");
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + alphaId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The {@code voucher(id)} document: the REAL argument binding carries
     * the opaque global ID, the codec decodes it (a mistyped raw UUID
     * answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void voucherReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(discountReadSpi.findVoucherById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(voucherView(id, "P3TEST-ALPHA")));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> voucher = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { voucher(id: $id) { id name code usageLimit used startDate endDate applyOncePerCustomer discountValueType currency } }",
                "data.voucher", Map.of("id", globalId));

        assertThat(voucher.get("id")).isEqualTo(globalId);
        assertThat(voucher.get("code")).isEqualTo("P3TEST-ALPHA");
        assertThat(voucher.get("used")).isEqualTo(2);
        assertThat(voucher.get("applyOncePerCustomer")).isEqualTo(true);
        assertThat(voucher.get("discountValueType")).isEqualTo("PERCENTAGE");
        assertThat(voucher.get("currency")).isEqualTo("SAR");
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

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { voucher(id: $id) { id } }",
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
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        when(discountReadSpi.findVoucherById(any(UUID.class), any(org.springframework.security.core.Authentication.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { voucher(id: $id) { id } }",
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
     * classified error through the real resolver — never a silent no-op:
     * filter, the deprecated query, the channel (no channel model), and
     * the deprecated nested sortBy.channel.
     */
    @Test
    void vouchersFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void vouchersDeprecatedQueryArgumentAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(first: 2, query: \"x\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("query is not implemented");
    }

    @Test
    void vouchersChannelArgumentAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(first: 2, channel: \"c\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel is not implemented");
    }

    @Test
    void vouchersNestedSortChannelAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(first: 2, sortBy: { direction: ASC, field: CODE, channel: \"c\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("sortBy.channel is not implemented");
    }

    @Test
    void voucherExternalReferenceAndChannelAnswerTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { voucher(externalReference: \"ext-1\") { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { voucher(channel: \"c\") { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void vouchersBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The unowned sort vocabulary: NAME (optional column), VALUE/
     * MINIMUM_SPENT_AMOUNT (channel-scoped money + the frozen money
     * contract), TYPE (no voucher-type discrimination) answer the
     * explicit classified error — never a silent fallback to CODE.
     */
    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { vouchers(first: 2, sortBy: { direction: ASC, field: NAME }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("NAME is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by a different sort is rejected for
     * the code scroll — the keyset mismatch, classified VAL-001 through
     * the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String createdAtCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("createdAt", Instant.parse("2026-10-01T00:00:00Z"),
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { vouchers(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", createdAtCursor)))
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
                "query { vouchers(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

}
