package com.marketplace.app.graphql;

import com.marketplace.pricing.Promotion;
import com.marketplace.pricing.PromotionRepository;
import com.marketplace.pricing.Voucher;
import com.marketplace.pricing.VoucherRedemption;
import com.marketplace.pricing.VoucherRedemptionRepository;
import com.marketplace.pricing.VoucherRepository;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P3 (the canonical Saleor plan §5-P3): end-to-end proof of the Saleor
 * Discounts-domain promotion/voucher read roots — {@code promotion},
 * {@code promotions}, {@code voucher} and {@code vouchers} — through the
 * REAL SDL, the REAL DGS wiring, the REAL security chain (the mocked
 * JwtDecoder stamps the caller's subject and roles claim exactly the way
 * the production converter reads them), the REAL pricing owner (no SPI
 * mocking: the ADMIN enforcement, the in-memory keyset scrolls over the
 * policy tables and the redemption-row usage accounting execute in the
 * real service), and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the canonical plan's P3 gate and the
 * campaign's frozen read contract: the role matrix (non-admin callers
 * are denied by the owning use case — AUTHZ-001), the read-back journey
 * (each list's own emitted global ID round-trips through the single
 * lookup with same-ID stability), the honest absences (an unknown but
 * well-formed global ID answers null; a malformed, raw-UUID or
 * MISTYPED global ID answers the classified VAL-001 error), the keyset
 * pagination contract (forward-only cursors that identify a row's sort
 * key, with the declared-but-unexecuted Saleor arguments answering
 * explicit classified errors instead of silent no-ops), and the bounded
 * projection's own facts (the ORDER type, the optional policy window's
 * honest null rendering, the voucher's counted usage and the truthful
 * per-user-limit flattening).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PromotionVoucherGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p3-discounts-admin-subject";
    private static final String PROVIDER_SUBJECT = "p3-discounts-provider-subject";
    private static final String CONSUMER_SUBJECT = "p3-discounts-consumer-subject";


    private static final Instant WINDOW_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2027-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private PromotionRepository promotionRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private VoucherRedemptionRepository voucherRedemptionRepository;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching policy data it does not own. */
    private final List<UUID> seededPromotionIds = new ArrayList<>();
    private final List<UUID> seededVoucherIds = new ArrayList<>();
    private final List<UUID> seededRedemptionIds = new ArrayList<>();

    /** The seeds the per-row lookup legs read back by their own native keys. */
    private UUID alphaPromotionId;
    private UUID betaPromotionId;
    private UUID gammaPromotionId;
    private UUID alphaVoucherId;
    private UUID betaVoucherId;
    private UUID gammaVoucherId;

    @BeforeEach
    void seedPolicyRows() {
        // The promotion seeds exercise the honest window renderings: a full
        // window (alpha), an unbounded one (beta — null/null on the wire), a
        // start-bounded one (gamma), and both value vocabularies.
        alphaPromotionId = persistPromotion("P3 Promo Alpha", Promotion.DiscountType.PERCENTAGE, 500,
                WINDOW_START, WINDOW_END);
        betaPromotionId = persistPromotion("P3 Promo Beta", Promotion.DiscountType.FIXED, 1000, null, null);
        gammaPromotionId = persistPromotion("P3 Promo Gamma", Promotion.DiscountType.PERCENTAGE, 1000,
                WINDOW_START, null);

        // The voucher seeds exercise the bounded projection's own facts: the
        // once-per-customer flattening (alpha's per-user limit IS 1, gamma's
        // is 2 — false), the null-name row (beta), the unlimited rows (null
        // usageLimit), and the counted usage (gamma consumes 2 redemptions).
        alphaVoucherId = persistVoucher("P3TEST-ALPHA", "P3 Voucher Alpha", Voucher.DiscountType.PERCENTAGE, 500,
                WINDOW_START, WINDOW_END, 5L, 1L);
        betaVoucherId = persistVoucher("P3TEST-BETA", null, Voucher.DiscountType.FIXED, 1000,
                null, null, null, null);
        gammaVoucherId = persistVoucher("P3TEST-GAMMA", "P3 Voucher Gamma", Voucher.DiscountType.PERCENTAGE,
                1500, WINDOW_START, WINDOW_END, 10L, 2L);

        persistRedemption(gammaVoucherId, UUID.randomUUID());
        persistRedemption(gammaVoucherId, UUID.randomUUID());
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededRedemptionIds.isEmpty()) {
            voucherRedemptionRepository.deleteAllById(new ArrayList<>(seededRedemptionIds));
            seededRedemptionIds.clear();
        }
        if (!seededVoucherIds.isEmpty()) {
            voucherRepository.deleteAllById(new ArrayList<>(seededVoucherIds));
            seededVoucherIds.clear();
        }
        if (!seededPromotionIds.isEmpty()) {
            promotionRepository.deleteAllById(new ArrayList<>(seededPromotionIds));
            seededPromotionIds.clear();
        }
    }

    private UUID persistPromotion(String name, Promotion.DiscountType type, long value,
                                  Instant startsAt, Instant endsAt) {
        Promotion promotion = Promotion.of(name, "SAR", type, value, 0, startsAt, endsAt, true);
        promotionRepository.save(promotion);
        seededPromotionIds.add(promotion.getId());
        return promotion.getId();
    }

    private UUID persistVoucher(String code, String name, Voucher.DiscountType type, long value,
                                Instant startsAt, Instant endsAt, Long maxRedemptions,
                                Long maxRedemptionsPerUser) {
        Voucher voucher = Voucher.of(code, name, "SAR", type, value, 0, startsAt, endsAt,
                maxRedemptions, maxRedemptionsPerUser, true);
        voucherRepository.save(voucher);
        seededVoucherIds.add(voucher.getId());
        return voucher.getId();
    }

    private void persistRedemption(UUID voucherId, UUID orderId) {
        VoucherRedemption redemption = VoucherRedemption.of(voucherId, orderId, UUID.randomUUID(),
                500, "SAR", Instant.now());
        voucherRedemptionRepository.save(redemption);
        seededRedemptionIds.add(redemption.getId());
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (authorities claim "roles", prefix ROLE_). The decoder
     * stub is global to the mock, so each test builds the tester whose
     * subject it acts as, immediately before its requests (the
     * UserGraphQlIntegrationTest precedent).
     */
    private HttpGraphQlTester testerFor(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * Decodes an emitted typed global ID — the honest tag/UUID verification
     * (Base64("Tag:") prefix compares can never hold: the padded '==' of the
     * tag-only encoding is not part of the full "Tag:<uuid>" encoding; the
     * decoded form is checked against the plain tag and the native UUID
     * shape instead).
     */
    private static String decodedGlobalId(String globalId) {
        String decoded = new String(java.util.Base64.getDecoder().decode(globalId),
                java.nio.charset.StandardCharsets.UTF_8);
        int separator = decoded.indexOf(':');
        assertThat(separator).isPositive();
        UUID.fromString(decoded.substring(separator + 1));
        return decoded;
    }

    /** A well-formed typed global ID for either discount type over any native UUID. */
    private static String globalId(String typeName, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((typeName + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // promotions / vouchers — the ADMIN-enforced keyset scrolls
    // ------------------------------------------------------------------

    @Test
    void adminSeesTheSaleorEnvelopeOverTheRealPromotionPolicyTable() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotions(first: 10) {
                            edges { node { id name type startDate endDate createdAt updatedAt } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("promotions.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3))
                .path("promotions.edges").entityList(Object.class)
                .satisfies(edges -> assertThat(edges).isNotEmpty())
                .path("promotions.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .allSatisfy(id -> assertThat(decodedGlobalId(id)).startsWith("Promotion:")))
                .path("promotions.edges[*].node.type").entityList(String.class)
                .satisfies(types -> assertThat(types)
                        .allSatisfy(type -> assertThat(type).isEqualTo("ORDER")))
                .path("promotions.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names)
                        .contains("P3 Promo Alpha", "P3 Promo Beta", "P3 Promo Gamma"));
    }

    @Test
    void adminSeesTheSaleorEnvelopeOverTheRealVoucherPolicyTable() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { vouchers(first: 10) {
                            edges { node { id name code usageLimit used currency discountValueType } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("vouchers.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3))
                .path("vouchers.edges").entityList(Object.class)
                .satisfies(edges -> assertThat(edges).isNotEmpty())
                .path("vouchers.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .allSatisfy(id -> assertThat(decodedGlobalId(id)).startsWith("Voucher:")))
                .path("vouchers.edges[*].node.code").entityList(String.class)
                .satisfies(codes -> assertThat(codes)
                        .contains("P3TEST-ALPHA", "P3TEST-BETA", "P3TEST-GAMMA"))
                .path("vouchers.edges[*].node.currency").entityList(String.class)
                .satisfies(currencies -> assertThat(currencies)
                        .allSatisfy(currency -> assertThat(currency).isEqualTo("SAR")))
                .path("vouchers.edges[*].node.discountValueType").entityList(String.class)
                .satisfies(types -> assertThat(types).contains("PERCENTAGE", "FIXED"));
    }

    @Test
    void promotionsKeysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { promotions(first: 1) {
                            edges { node { name } }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("promotions.edges[0].node.name").entity(String.class)
                .satisfies(name -> assertThat(name).isNotBlank())
                .path("promotions.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("promotions.edges").entityList(Object.class).hasSize(1);
        String firstName = firstPage.path("promotions.edges[0].node.name").entity(String.class).get();
        String firstEndCursor = firstPage.path("promotions.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's own sort key: following it continues
        // the same name-ascending scroll without overlap.
        adminTester.document("""
                        query($after: String) { promotions(first: 1, after: $after) {
                            edges { node { name } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("promotions.edges[0].node.name").entity(String.class)
                .satisfies(secondName -> assertThat(secondName).isGreaterThan(firstName))
                .path("promotions.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void vouchersKeysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { vouchers(first: 1) {
                            edges { node { code } }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("vouchers.edges[0].node.code").entity(String.class)
                .satisfies(code -> assertThat(code).isNotBlank())
                .path("vouchers.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("vouchers.edges").entityList(Object.class).hasSize(1);
        String firstCode = firstPage.path("vouchers.edges[0].node.code").entity(String.class).get();
        String firstEndCursor = firstPage.path("vouchers.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's own sort key: following it continues
        // the same code-ascending scroll without overlap.
        adminTester.document("""
                        query($after: String) { vouchers(first: 1, after: $after) {
                            edges { node { code } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("vouchers.edges[0].node.code").entity(String.class)
                .satisfies(secondCode -> assertThat(secondCode).isGreaterThan(firstCode))
                .path("vouchers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void promotionsNameDescendingSortReversesTheWindow() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotions(first: 10, sortBy: { direction: DESC, field: NAME }) {
                            edges { node { name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("promotions.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names)
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder())
                        .contains("P3 Promo Gamma", "P3 Promo Beta", "P3 Promo Alpha"));
    }

    @Test
    void promotionsCreatedAtSortExecutesTheOwningAuditColumn() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The CREATED_AT sort executes over the non-null audit column: the
        // parsed instants come back in the requested descending order.
        adminTester.document("""
                        query { promotions(first: 10, sortBy: { direction: DESC, field: CREATED_AT }) {
                            edges { node { createdAt } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("promotions.edges[*].node.createdAt").entityList(String.class)
                .satisfies(timestamps -> {
                    assertThat(timestamps).isNotEmpty();
                    List<Instant> instants = timestamps.stream().map(Instant::parse).toList();
                    assertThat(instants).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                });
    }

    @Test
    void voucherRendersTheHonestUsageAccountingAndThePerUserLimitFlattening() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The gamma voucher consumed exactly two redemptions: `used` is the
        // counted rows (never a stored counter), its per-user limit of 2
        // flattens to applyOncePerCustomer=false, its global limit renders
        // as usageLimit=10.
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { code used usageLimit applyOncePerCustomer name } }
                        """)
                .variable("id", globalId("Voucher", gammaVoucherId))
                .execute()
                .errors().verify()
                .path("voucher.code").entity(String.class).isEqualTo("P3TEST-GAMMA")
                .path("voucher.used").entity(Integer.class).isEqualTo(2)
                .path("voucher.usageLimit").entity(Integer.class).isEqualTo(10)
                .path("voucher.applyOncePerCustomer").entity(Boolean.class).isEqualTo(false)
                .path("voucher.name").entity(String.class).isEqualTo("P3 Voucher Gamma");
    }

    @Test
    void voucherAlphaFlattensItsOncePerCustomerLimitAndBetaRendersItsHonestNullName() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // Alpha's per-user limit of 1 flattens to applyOncePerCustomer=true
        // with usageLimit=5 and its own name; beta's authored null name,
        // unlimited rows and unbounded window render as the honest nulls —
        // never fabricated values.
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { code name usageLimit applyOncePerCustomer } }
                        """)
                .variable("id", globalId("Voucher", alphaVoucherId))
                .execute()
                .errors().verify()
                .path("voucher.code").entity(String.class).isEqualTo("P3TEST-ALPHA")
                .path("voucher.name").entity(String.class).isEqualTo("P3 Voucher Alpha")
                .path("voucher.usageLimit").entity(Integer.class).isEqualTo(5)
                .path("voucher.applyOncePerCustomer").entity(Boolean.class).isEqualTo(true);
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { code name usageLimit applyOncePerCustomer startDate endDate } }
                        """)
                .variable("id", globalId("Voucher", betaVoucherId))
                .execute()
                .errors().verify()
                .path("voucher.code").entity(String.class).isEqualTo("P3TEST-BETA")
                .path("voucher.name").valueIsNull()
                .path("voucher.usageLimit").valueIsNull()
                .path("voucher.applyOncePerCustomer").entity(Boolean.class).isEqualTo(false)
                .path("voucher.startDate").valueIsNull()
                .path("voucher.endDate").valueIsNull();
    }

    @Test
    void promotionRendersItsWindowFactsHonestly() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The bounded window projection: the full-window alpha renders both
        // ISO-8601 bounds plus the always-present audit instants; the
        // unbounded beta renders the honest null/null — never a fabricated
        // epoch; the start-bounded gamma renders start only.
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { name type startDate endDate createdAt updatedAt } }
                        """)
                .variable("id", globalId("Promotion", alphaPromotionId))
                .execute()
                .errors().verify()
                .path("promotion.name").entity(String.class).isEqualTo("P3 Promo Alpha")
                .path("promotion.type").entity(String.class).isEqualTo("ORDER")
                .path("promotion.startDate").entity(String.class).isEqualTo("2026-01-01T00:00:00Z")
                .path("promotion.endDate").entity(String.class).isEqualTo("2027-01-01T00:00:00Z")
                .path("promotion.createdAt").entity(String.class)
                .satisfies(value -> assertThat(value)
                        .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"))
                .path("promotion.updatedAt").entity(String.class)
                .satisfies(value -> assertThat(value)
                        .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"));
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { name startDate endDate } }
                        """)
                .variable("id", globalId("Promotion", betaPromotionId))
                .execute()
                .errors().verify()
                .path("promotion.name").entity(String.class).isEqualTo("P3 Promo Beta")
                .path("promotion.startDate").valueIsNull()
                .path("promotion.endDate").valueIsNull();
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { name startDate endDate } }
                        """)
                .variable("id", globalId("Promotion", gammaPromotionId))
                .execute()
                .errors().verify()
                .path("promotion.name").entity(String.class).isEqualTo("P3 Promo Gamma")
                .path("promotion.startDate").entity(String.class).isEqualTo("2026-01-01T00:00:00Z")
                .path("promotion.endDate").valueIsNull();
    }

    // ------------------------------------------------------------------
    // promotion / voucher — the ADMIN-enforced lookups (read-back + negatives)
    // ------------------------------------------------------------------

    @Test
    void promotionRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String emittedId = adminTester.document("""
                        query { promotions(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name } } } }
                        """)
                .execute().errors().verify()
                .path("promotions.edges[0].node.id").entity(String.class).get();

        var readBack = adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { id name } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("promotion.id").entity(String.class).isEqualTo(emittedId);

        // Same-ID stability: a fresh read of the same emitted ID answers
        // the same identity again (the native key, not a per-read handle).
        String again = readBack.path("promotion.id").entity(String.class).get();
        assertThat(again).isEqualTo(emittedId);
    }

    @Test
    void voucherRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String emittedId = adminTester.document("""
                        query { vouchers(first: 1, sortBy: { direction: ASC, field: CODE }) {
                            edges { node { id code } } } }
                        """)
                .execute().errors().verify()
                .path("vouchers.edges[0].node.id").entity(String.class).get();

        var readBack = adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { id code } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("voucher.id").entity(String.class).isEqualTo(emittedId);

        String again = readBack.path("voucher.id").entity(String.class).get();
        assertThat(again).isEqualTo(emittedId);
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID guessed = UUID.randomUUID();
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { id name } }
                        """)
                .variable("id", globalId("Promotion", guessed))
                .execute()
                .errors().verify()
                .path("promotion").valueIsNull();
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { id code } }
                        """)
                .variable("id", globalId("Voucher", UUID.randomUUID()))
                .execute()
                .errors().verify()
                .path("voucher").valueIsNull();
    }

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void mistypedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // A well-formed Promotion global ID sent to the voucher lookup: the
        // codec's own per-callsite type check answers the classified error —
        // never a cross-type read.
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { id } }
                        """)
                .variable("id", globalId("Promotion", UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
        adminTester.document("""
                        query($id: ID!) { promotion(id: $id) { id } }
                        """)
                .variable("id", globalId("Voucher", UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the role matrix + the anonymous boundary + the declared-but-
    // unexecuted argument boundaries
    // ------------------------------------------------------------------

    @Test
    void providerAndConsumerCallersAreDeniedByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        providerTester.document("query { promotions(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("promotions").valueIsNull();
        providerTester.document("query { vouchers(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("vouchers").valueIsNull();

        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("""
                        query($id: ID!) { promotion(id: $id) { id } }
                        """)
                .variable("id", globalId("Promotion", UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("promotion").valueIsNull();
        consumerTester.document("""
                        query($id: ID!) { voucher(id: $id) { id } }
                        """)
                .variable("id", globalId("Voucher", UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("voucher").valueIsNull();
    }

    /** The endpoint's own gate: an anonymous caller is rejected before any adapter runs. */
    @Test
    void anAnonymousCallerIsRejectedBeforeAnyAdapterRuns() {
        WebTestClient anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        anonymous.post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ promotions(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("AUTHN-001");
    }

    @Test
    void whereAndFilterAnswerTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotions(first: 2, where: { ids: [] }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("where is not implemented");
                });
        adminTester.document("""
                        query { vouchers(first: 2, filter: { search: "alpha" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("filter is not implemented");
                });
    }

    @Test
    void externalReferenceAndChannelAnswerTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotion(externalReference: "ext-1") { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("externalReference is not implemented");
                });
        adminTester.document("""
                        query { voucher(externalReference: "ext-1") { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("externalReference is not implemented");
                });
        adminTester.document("""
                        query { voucher(channel: "channel-slug") { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("channel is not implemented");
                });
        adminTester.document("""
                        query { vouchers(first: 2, channel: "channel-slug") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("channel is not implemented");
                });
    }

    @Test
    void deprecatedQueryArgumentAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { vouchers(first: 2, query: "alpha") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("query is not implemented");
                });
    }

    @Test
    void promotionWithoutADiscriminatorAnswersTheClassifiedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("query { promotion { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("promotion requires an id");
                });
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotions(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("forward-only");
                });
        adminTester.document("""
                        query { vouchers(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("forward-only");
                });
    }

    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // START_DATE: the optional policy-window column cannot carry a
        // deterministic keyset cursor — the explicit classified error.
        adminTester.document("""
                        query { promotions(first: 2, sortBy: { direction: ASC, field: START_DATE }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("START_DATE is not implemented");
                });
        // NAME (voucher): the optional column — the explicit classified error.
        adminTester.document("""
                        query { vouchers(first: 2, sortBy: { direction: ASC, field: NAME }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("NAME is not implemented");
                });
        // The deprecated nested sortBy.channel — the explicit classified error.
        adminTester.document("""
                        query { vouchers(first: 2, sortBy: { direction: ASC, field: CODE, channel: "channel-slug" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("sortBy.channel is not implemented");
                });
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { promotions(first: 0) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("promotions.edges").entityList(Object.class).hasSize(0)
                .path("promotions.totalCount").valueIsNull()
                .path("promotions.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("promotions.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
        adminTester.document("""
                        query { vouchers(first: 0) {
                            edges { node { code } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("vouchers.edges").entityList(Object.class).hasSize(0)
                .path("vouchers.totalCount").valueIsNull()
                .path("vouchers.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("vouchers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
    }


    // ------------------------------------------------------------------
    // the voucher mutation roots — the real write stack (the agent-2 packet)
    // ------------------------------------------------------------------

    @Test
    void adminCreatesAVoucherThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String emittedId = adminTester.document("""
                        mutation { voucherCreate(input: {
                            name: "P3 Created Policy", code: "p3-created-code",
                            discountValueType: PERCENTAGE, discountValue: 750,
                            currency: "SAR", minSpend: 5000,
                            startDate: "2026-11-01T00:00:00Z", endDate: "2026-12-01T00:00:00Z",
                            usageLimit: 9, applyOncePerCustomer: true
                        }) {
                            errors { field code }
                            voucher { id code name currency usageLimit used discountValueType
                                      applyOncePerCustomer startDate endDate }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("voucherCreate.voucher.id").entity(String.class).get();

        String decoded = new String(java.util.Base64.getDecoder().decode(emittedId),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).startsWith("Voucher:");
        UUID rowId = UUID.fromString(decoded.substring("Voucher:".length()));
        seededVoucherIds.add(rowId);

        // The real-stack read-back: the row persisted with the enum as the
        // schema's own STRING vocabulary (the V223 vouchers_type_chk
        // membership — the @Enumerated(STRING) mapping this packet fixed)
        // and the code normalized to the entity's own uppercase contract.
        Voucher row = voucherRepository.findById(rowId).orElseThrow();
        assertThat(row.getCode()).isEqualTo("P3-CREATED-CODE");
        assertThat(row.getName()).isEqualTo("P3 Created Policy");
        assertThat(row.getCurrency()).isEqualTo("SAR");
        assertThat(row.getDiscountType()).isEqualTo(Voucher.DiscountType.PERCENTAGE);
        assertThat(row.getDiscountValue()).isEqualTo(750L);
        assertThat(row.getMinSpendMinor()).isEqualTo(5000L);
        assertThat(row.getMaxRedemptions()).isEqualTo(9L);
        assertThat(row.getMaxRedemptionsPerUser()).isEqualTo(1L);

        // The real-stack read-back rides the read root's OWN declared wire
        // shape — the typed global ID emitted by the mutation itself (the
        // Saleor voucher query carries id/externalReference/channel only;
        // no code argument exists on this surface).
        adminTester.document("""
                        query($id: ID!) { voucher(id: $id) { code usageLimit applyOncePerCustomer } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("voucher.code").entity(String.class).isEqualTo("P3-CREATED-CODE")
                .path("voucher.usageLimit").entity(Integer.class).isEqualTo(9)
                .path("voucher.applyOncePerCustomer").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void duplicateCodeCreateAnswersThePayloadError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { voucherCreate(input: {code: "P3TEST-ALPHA", discountValueType: FIXED,
                                discountValue: 100, currency: "SAR"}) {
                            errors { field code message }
                            voucher { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("voucherCreate.errors[0].field").entity(String.class).isEqualTo("code")
                .path("voucherCreate.errors[0].code").entity(String.class).isEqualTo("ALREADY_EXISTS")
                .path("voucherCreate.voucher").valueIsNull();
    }

    @Test
    void consumerIsDeniedTheVoucherCreateByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumerTester.document("""
                        mutation { voucherCreate(input: {code: "P3-DENIED-1", discountValueType: FIXED,
                                discountValue: 100, currency: "SAR"}) {
                            errors { code }
                            voucher { id }
                        } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                });
    }

    @Test
    void adminUpdatesAVoucherThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String emittedId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + betaVoucherId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { voucherUpdate(id: "%s", input: {name: "P3 Voucher Beta Named", usageLimit: 4}) {
                            errors { code }
                            voucher { name usageLimit used }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("voucherUpdate.voucher.name").entity(String.class).isEqualTo("P3 Voucher Beta Named")
                .path("voucherUpdate.voucher.usageLimit").entity(Integer.class).isEqualTo(4);

        Voucher row = voucherRepository.findById(betaVoucherId).orElseThrow();
        assertThat(row.getName()).isEqualTo("P3 Voucher Beta Named");
        assertThat(row.getMaxRedemptions()).isEqualTo(4L);
    }

    @Test
    void codeChangeOnARedeemedVoucherIsRefusedThroughTheRealStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // gamma carries the two seeded redemptions — the code-stability
        // discipline refuses the code amendment with the Saleor vocabulary's
        // own member.
        String emittedId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + gammaVoucherId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { voucherUpdate(id: "%s", input: {code: "P3-FRESH-CODE"}) {
                            errors { field code }
                            voucher { id }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("voucherUpdate.errors[0].field").entity(String.class).isEqualTo("code")
                .path("voucherUpdate.errors[0].code").entity(String.class).isEqualTo("VOUCHER_ALREADY_USED")
                .path("voucherUpdate.voucher").valueIsNull();
    }

    @Test
    void adminSoftDeletesAVoucherAndTheSecondDeleteAnswersNotFound() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID seeded = persistVoucher("P3-MUT-DEL1", "P3 Mutation Delete", Voucher.DiscountType.FIXED, 300,
                null, null, null, null);
        String emittedId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + seeded)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { voucherDelete(id: "%s") {
                            errors { code }
                            voucher { code name }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("voucherDelete.voucher.code").entity(String.class).isEqualTo("P3-MUT-DEL1");

        // Soft delete: the derived lookup no longer resolves the row.
        assertThat(voucherRepository.findById(seeded)).isEmpty();
        assertThat(voucherRepository.findByCode("P3-MUT-DEL1")).isEmpty();

        adminTester.document("""
                        mutation { voucherDelete(id: "%s") {
                            errors { field code }
                            voucher { id }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("voucherDelete.errors[0].field").entity(String.class).isEqualTo("id")
                .path("voucherDelete.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("voucherDelete.voucher").valueIsNull();
    }

    @Test
    void adminBulkDeletesVouchersWithTheHonestCount() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID first = persistVoucher("P3-BULK-ONE", "P3 Bulk One", Voucher.DiscountType.FIXED, 100,
                null, null, null, null);
        UUID second = persistVoucher("P3-BULK-TWO", "P3 Bulk Two", Voucher.DiscountType.FIXED, 100,
                null, null, null, null);
        String firstId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + first).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String secondId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + second).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String unknownId = java.util.Base64.getEncoder()
                .encodeToString(("Voucher:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { voucherBulkDelete(ids: ["%s", "%s", "%s"]) { count errors { code } } }
                        """.formatted(firstId, secondId, unknownId))
                .execute()
                .errors().verify()
                .path("voucherBulkDelete.count").entity(Integer.class).isEqualTo(2);

        assertThat(voucherRepository.findById(first)).isEmpty();
        assertThat(voucherRepository.findById(second)).isEmpty();
    }
}
