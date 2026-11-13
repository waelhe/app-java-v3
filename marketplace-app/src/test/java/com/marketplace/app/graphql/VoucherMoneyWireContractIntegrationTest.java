package com.marketplace.app.graphql;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import test.config.IntegrationContainers;

/**
 * The voucher money wire contract (IDP-01's root fix, W1-B1) — the
 * pinned Saleor 3.23.45 {@code PositiveDecimal} semantics proven over
 * the REAL HTTP GraphQL surface, the REAL security chain (mocked
 * JwtDecoder stamps subject/roles exactly as the production converter
 * reads them), the REAL pricing owner and the REAL migrated Flyway
 * schema:
 *
 * <ul>
 *   <li>FIXED: {@code discountValue: 100} means 100.00 — the row
 *       carries 10000 minor units (the finding's measured silent 100x
 *       scale error is pinned shut).</li>
 *   <li>PERCENTAGE: {@code 7.5} percentage points — the row carries
 *       750 basis points (the same value the finding measured as a
 *       coercion rejection on the old Int wire).</li>
 *   <li>Sub-basis-point precision ({@code 7.555}) and currency-exponent
 *       overflow ({@code 10.505} on a 2-decimal currency) answer the
 *       classified INVALID payload error and never create a row.</li>
 *   <li>{@code minAmountSpent} rides the pinned write-path spelling in
 *       MAJOR units ({@code 50.25} = 5025 minor).</li>
 * </ul>
 *
 * <p>The legs mirror the hostile review's empirical harness
 * (verification/local-reproduction.md) — flipped from measuring the
 * defects to pinning their absence: the exact payloads that produced
 * the 1.00 USD voucher and the coercion rejection now produce the
 * correct rows and the correct classifications.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class VoucherMoneyWireContractIntegrationTest {

    private static final String ADMIN_SUBJECT = "b1-money-admin-subject";

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final List<UUID> seededUserIds = new ArrayList<>();
    private final List<String> seededVoucherCodes = new ArrayList<>();

    @BeforeEach
    void seed() {
        UUID adminId = UUID.randomUUID();
        userRepository.save(new User(adminId, ADMIN_SUBJECT, null,
                "b1 money wire admin", UserRole.ADMIN));
        seededUserIds.add(adminId);
    }

    @AfterEach
    void removeSeededRows() {
        for (String code : seededVoucherCodes) {
            jdbcTemplate.update("DELETE FROM vouchers WHERE code = ?", code);
        }
        seededVoucherCodes.clear();
        for (UUID id : new ArrayList<>(seededUserIds)) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        seededUserIds.clear();
    }

    // ------------------------------------------------------------------
    // helpers (the established wire-contract IT pattern)
    // ------------------------------------------------------------------

    private HttpGraphQlTester adminTester() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", ADMIN_SUBJECT)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("ADMIN"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /** One voucherCreate call over the real wire. */
    private org.springframework.graphql.test.tester.GraphQlTester.Response createVoucher(
            HttpGraphQlTester tester, Map<String, Object> input) {
        return tester.document("""
                        mutation($input: VoucherInput!) {
                          voucherCreate(input: $input) {
                            voucher { id code currency discountValueType }
                            errors { field message code }
                          }
                        }
                        """)
                .variable("input", input)
                .execute();
    }

    // ------------------------------------------------------------------
    // the money wire legs
    // ------------------------------------------------------------------

    /**
     * The magnitude leg: a Saleor-semantics dashboard sending
     * {@code discountValue: 100} for a FIXED USD voucher — meaning
     * 100.00 — persists {@code vouchers.discount_value = 10000} minor
     * units. The hostile review measured 100 (a 1.00 USD voucher); the
     * pin proves the fix.
     */
    @Test
    void fixedVoucherWireDecimalPersistsMajorUnitsAsMinorUnits() {
        String code = "B1-FIX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        var response = createVoucher(adminTester(), Map.of(
                "name", "B1 fixed wire voucher",
                "code", code,
                "discountValueType", "FIXED",
                "discountValue", 100,
                "currency", "USD"));

        response.errors().verify();
        response.path("voucherCreate.voucher.code").entity(String.class)
                .satisfies(actual -> assertThat(actual).isEqualTo(code));
        response.path("voucherCreate.voucher.discountValueType").entity(String.class)
                .satisfies(type -> assertThat(type).isEqualTo("FIXED"));

        Long persisted = jdbcTemplate.queryForObject(
                "SELECT discount_value FROM vouchers WHERE code = ?", Long.class, code);
        assertThat(persisted).isEqualTo(10000L);
    }

    /**
     * The coercion leg: {@code 7.5} — a valid Saleor percentage — rides
     * the PositiveDecimal wire and lands as 750 basis points. The
     * hostile review measured this exact value dying in Int coercion
     * before any domain code ran.
     */
    @Test
    void percentageVoucherWireDecimalLandsAsBasisPoints() {
        String code = "B1-PCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        var response = createVoucher(adminTester(), Map.of(
                "name", "B1 percentage wire voucher",
                "code", code,
                "discountValueType", "PERCENTAGE",
                "discountValue", 7.5,
                "currency", "USD"));

        response.errors().verify();
        response.path("voucherCreate.voucher.discountValueType").entity(String.class)
                .satisfies(type -> assertThat(type).isEqualTo("PERCENTAGE"));

        Long persisted = jdbcTemplate.queryForObject(
                "SELECT discount_value FROM vouchers WHERE code = ?", Long.class, code);
        assertThat(persisted).isEqualTo(750L);
    }

    /**
     * The precision leg: {@code 7.555} (finer than one basis point)
     * answers the classified INVALID payload error and never creates a
     * row — the internal integer cannot host it, and the honest answer
     * is the error, not the rounding.
     */
    @Test
    void subBasisPointPercentageAnswersTheClassifiedInvalidAndCreatesNoRow() {
        String code = "B1-FINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        var response = createVoucher(adminTester(), Map.of(
                "name", "B1 fine wire voucher",
                "code", code,
                "discountValueType", "PERCENTAGE",
                "discountValue", 7.555,
                "currency", "USD"));

        response.errors().verify();
        response.path("voucherCreate.voucher").valueIsNull();
        response.path("voucherCreate.errors[0].field").entity(String.class)
                .satisfies(field -> assertThat(field).isEqualTo("discountValue"));
        response.path("voucherCreate.errors[0].code").entity(String.class)
                .satisfies(codeValue -> assertThat(codeValue).isEqualTo("INVALID"));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vouchers WHERE code = ?", Integer.class, code);
        assertThat(rows).isZero();
    }

    /**
     * The currency-exponent leg: {@code 10.505} on a 2-decimal
     * currency answers the classified INVALID — currency-exact
     * conversion, never silent rounding.
     */
    @Test
    void scaleBeyondTheCurrencyExponentAnswersTheClassifiedInvalid() {
        String code = "B1-SCALE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        var response = createVoucher(adminTester(), Map.of(
                "name", "B1 scale wire voucher",
                "code", code,
                "discountValueType", "FIXED",
                "discountValue", 10.505,
                "currency", "USD"));

        response.errors().verify();
        response.path("voucherCreate.voucher").valueIsNull();
        response.path("voucherCreate.errors[0].field").entity(String.class)
                .satisfies(field -> assertThat(field).isEqualTo("discountValue"));
        response.path("voucherCreate.errors[0].code").entity(String.class)
                .satisfies(codeValue -> assertThat(codeValue).isEqualTo("INVALID"));
    }

    /**
     * The minAmountSpent leg: the pinned write-path spelling in MAJOR
     * units — {@code 50.25} lands as 5025 minor units on the row.
     */
    @Test
    void minAmountSpentRidesMajorUnitsOntoMinorUnits() {
        String code = "B1-MIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        var response = createVoucher(adminTester(), Map.of(
                "name", "B1 min wire voucher",
                "code", code,
                "discountValueType", "FIXED",
                "discountValue", 25.50,
                "currency", "USD",
                "minAmountSpent", 50.25));

        response.errors().verify();
        response.path("voucherCreate.voucher.code").entity(String.class)
                .satisfies(actual -> assertThat(actual).isEqualTo(code));

        Long persistedMin = jdbcTemplate.queryForObject(
                "SELECT min_spend_minor FROM vouchers WHERE code = ?", Long.class, code);
        assertThat(persistedMin).isEqualTo(5025L);
        Long persistedValue = jdbcTemplate.queryForObject(
                "SELECT discount_value FROM vouchers WHERE code = ?", Long.class, code);
        assertThat(persistedValue).isEqualTo(2550L);
    }

    /**
     * The negative value never rides the pinned scalar — the
     * PositiveDecimal coercion (nonnegative only) rejects the request
     * before any domain code runs.
     */
    @Test
    void negativeValueAnswersScalarCoercionRejectionAndCreatesNoRow() {
        String code = "B1-NEG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        seededVoucherCodes.add(code);

        adminTester().document("""
                        mutation($input: VoucherInput!) {
                          voucherCreate(input: $input) {
                            voucher { id }
                            errors { field code }
                          }
                        }
                        """)
                .variable("input", Map.of(
                        "code", code,
                        "discountValueType", "FIXED",
                        "discountValue", -1.00,
                        "currency", "USD"))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vouchers WHERE code = ?", Integer.class, code);
        assertThat(rows).isZero();
    }
}
