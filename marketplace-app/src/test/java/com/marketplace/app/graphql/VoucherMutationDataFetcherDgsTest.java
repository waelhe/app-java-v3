package com.marketplace.app.graphql;

import com.marketplace.pricing.spi.VoucherMutationError;
import com.marketplace.pricing.spi.VoucherMutationErrorCode;
import com.marketplace.pricing.spi.VoucherMutationResult;
import com.marketplace.pricing.spi.VoucherView;
import com.marketplace.pricing.spi.VoucherWriteSpi;
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

import java.time.Instant;
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
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherCreate},
 * {@code voucherUpdate}, {@code voucherDelete} and
 * {@code voucherBulkDelete} mutation roots, the agent-2 packet): the
 * GraphQL adapter's DGS proof — REAL mutation documents against the REAL
 * SDL (the agent-2 SDL fragment's own declared voucher roots) and the
 * REAL DGS wiring with no web layer and no containers. The owning write
 * use case is mocked at the declared {@code VoucherWriteSpi} seam; the
 * full-stack proof (the real policy writes over the Flyway-migrated
 * V223 schema, the real {@code @PreAuthorize} ADMIN gate and the real
 * enum-as-STRING persistence) lives in
 * {@code PromotionVoucherGraphQlIntegrationTest}'s mutation legs.
 *
 * <p>What this proves: the SDL's own voucher mutation roots load and
 * execute through the real argument binding (the generated
 * {@code VoucherInput} binding by name, the enum field binding
 * PERCENTAGE/FIXED); the payload shapes serialize exactly as Saleor
 * declares them; the domain error entries map onto the Saleor
 * {@code DiscountError} vocabulary; the transport-level negatives (a
 * malformed or mistyped global ID, a keyless call, the
 * declared-but-unowned {@code externalReference} argument) answer the
 * classified GraphQL error family — never a silent no-op.</p>
 */
@SpringBootTest(classes = VoucherMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class VoucherMutationDataFetcherDgsTest {

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

    @MockitoBean
    private VoucherWriteSpi voucherWriteSpi;

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;

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

    private static VoucherView voucherView(UUID id, String code) {
        return new VoucherView(id, code, "Policy", "SAR",
                VoucherView.DiscountType.FIXED, 10L, 0L, false,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"));
    }

    /** The typed global ID the codec emits — base64("Voucher:<uuid>"). */
    private static String voucherGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by name
     * (the enum included), the command reaches the owner's seam, and the
     * payload serializes the empty error list with the created
     * projection's typed global ID.
     */
    @Test
    void voucherCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID createdId = UUID.randomUUID();
        when(voucherWriteSpi.createVoucher(any())).thenReturn(
                VoucherMutationResult.success(voucherView(createdId, "FRESH-CODE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherCreate(input: {
                    name: "Policy", code: "fresh-code", discountValueType: FIXED,
                    discountValue: 500, currency: "SAR", minSpend: 1000,
                    startDate: "2026-11-01T00:00:00Z", endDate: "2026-12-01T00:00:00Z",
                    usageLimit: 10, applyOncePerCustomer: false
                }) {
                    errors { field message code }
                    voucher { id code name currency usageLimit used discountValueType
                              startDate endDate applyOncePerCustomer }
                } }
                """, "data.voucherCreate");

        verify(voucherWriteSpi).createVoucher(argThat(command ->
                "Policy".equals(command.name())
                        && "fresh-code".equals(command.code())
                        && "FIXED".equals(command.discountValueType())
                        && command.discountValue() == 500L
                        && "SAR".equals(command.currency())
                        && command.minSpend() == 1000L
                        && command.usageLimit() == 10L));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = (Map<String, Object>) payload.get("voucher");
        assertThat(voucher.get("id")).isEqualTo(voucherGlobalId(createdId));
        assertThat(voucher.get("code")).isEqualTo("FRESH-CODE");
        assertThat(voucher.get("discountValueType")).isEqualTo("FIXED");
        assertThat(voucher.get("usageLimit")).isEqualTo(10);
        assertThat(voucher.get("used")).isEqualTo(0);
        assertThat(voucher.get("applyOncePerCustomer")).isEqualTo(false);
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence.
     */
    @Test
    void voucherCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(voucherWriteSpi.createVoucher(any())).thenReturn(
                VoucherMutationResult.failure(List.of(
                        new VoucherMutationError("code", VoucherMutationErrorCode.ALREADY_EXISTS,
                                "A voucher with the code already exists"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherCreate(input: {code: "TAKEN-CODE", discountValueType: FIXED,
                        discountValue: 500, currency: "SAR"}) {
                    errors { field message code }
                    voucher { id }
                } }
                """, "data.voucherCreate");

        assertThat(payload.get("voucher")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("code");
        assertThat(errors.get(0).get("message")).isEqualTo("A voucher with the code already exists");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam.
     */
    @Test
    void voucherUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(voucherWriteSpi.updateVoucher(any(UUID.class), any())).thenReturn(
                VoucherMutationResult.success(voucherView(rowId, "KEPT-CODE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherUpdate(id: "%s", input: {name: "Renamed", usageLimit: 7}) {
                    errors { code }
                    voucher { id name usageLimit }
                } }
                """.formatted(voucherGlobalId(rowId)), "data.voucherUpdate");

        verify(voucherWriteSpi).updateVoucher(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "Renamed".equals(command.name())
                        && command.usageLimit() == 7L
                        && command.code() == null));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = (Map<String, Object>) payload.get("voucher");
        assertThat(voucher.get("id")).isEqualTo(voucherGlobalId(rowId));
        assertThat(voucher.get("name")).isEqualTo("Policy");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the code mapped onto the Saleor vocabulary.
     */
    @Test
    void voucherUpdateOfAnUnknownRowAnswersThePayloadError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(voucherWriteSpi.updateVoucher(any(UUID.class), any())).thenReturn(
                VoucherMutationResult.failure(List.of(new VoucherMutationError(
                        "id", VoucherMutationErrorCode.NOT_FOUND, "No voucher found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherUpdate(id: "%s", input: {name: "Renamed"}) {
                    errors { field code }
                    voucher { id }
                } }
                """.formatted(voucherGlobalId(UUID.randomUUID())), "data.voucherUpdate");

        assertThat(payload.get("voucher")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
        assertThat(errors.get(0).get("field")).isEqualTo("id");
    }

    /**
     * A malformed global ID answers the codec's classified GraphQL error
     * family — never a silent null and never a raw UUID fallback.
     */
    @Test
    void voucherUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    voucher { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(voucherWriteSpi);
    }

    /**
     * The declared-but-unowned {@code externalReference} argument (no
     * external-ID column) answers the classified error — never a
     * silently ignored argument.
     */
    @Test
    void voucherDeleteWithAnExternalReferenceAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherDelete(externalReference: "erp-1", id: "%s") {
                    errors { code }
                    voucher { id }
                } }
                """.formatted(voucherGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference is not implemented");
        org.mockito.Mockito.verifyNoInteractions(voucherWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void voucherDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(voucherWriteSpi.deleteVoucher(rowId)).thenReturn(
                VoucherMutationResult.success(voucherView(rowId, "GONE-CODE")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherDelete(id: "%s") {
                    errors { code }
                    voucher { id code }
                } }
                """.formatted(voucherGlobalId(rowId)), "data.voucherDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = (Map<String, Object>) payload.get("voucher");
        assertThat(voucher.get("id")).isEqualTo(voucherGlobalId(rowId));
        assertThat(voucher.get("code")).isEqualTo("GONE-CODE");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void voucherBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(voucherWriteSpi.deleteVouchers(anyList())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherBulkDelete(ids: ["%s", "%s"]) { count errors { code } } }
                """.formatted(voucherGlobalId(first), voucherGlobalId(second)),
                "data.voucherBulkDelete");

        verify(voucherWriteSpi).deleteVouchers(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void voucherBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { voucherBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(voucherWriteSpi);
    }
}
