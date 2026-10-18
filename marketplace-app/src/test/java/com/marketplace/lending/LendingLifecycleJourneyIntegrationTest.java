package com.marketplace.lending;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.AuthorizationServerFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * W6 / J23 (the unified plan §3, the register's J23 row) — the lending
 * composite journey on the REAL chain (real PostgreSQL + real Redis + the
 * real Flyway schema + the real login gate's Bearer + the real Modulith
 * event machinery): the owner's offer → the borrower's request (the fee
 * derived from the OWNER's terms, never caller-supplied) → the period's
 * exclusivity (the 409 overlap gate) → the owner's approval opening the
 * fee's intent (the LOAN origin, the deterministic {@code loan-{id}} key)
 * → the paid handover gate (the honest 409 before the money settles) →
 * the settlement listener marking the loan paid → the handover → the
 * return cycle → the owner's close — measured on the loan row, the
 * payment intent, the ledger (the owner's credit + the frozen commission
 * debit), and the notifications rows — plus the late-return pricing law
 * (the OWNER's frozen per-day rate, whole days) and the cancellation
 * branch through the ONE refund contract (the collected fee refunded in
 * full with the ledger's refund-debit mirror; the unpaid intent
 * cancelled where it stands).
 *
 * <p>The journey's money rides the payments engine's own surfaces — the
 * borrower's process step (PROCESSING + the Payment row) and the
 * administrative capture (SUCCEEDED + the COMPLETED event the lending
 * listener and the ledger listener both consume) — the J20 booking
 * journey's money-loop shape verbatim, one origin over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=" + CLIENT_ID,
        "marketplace.security.oauth2.client.secret=" + CLIENT_SECRET,
        "marketplace.security.oauth2.client.redirect-uris=" + REDIRECT_URI,
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LendingLifecycleJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-lending-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedTheStoreDictionary() {
        // The journey's category arrives as DATA (the plan's own wording —
        // never a migration), seeded by the class itself (the RMA journey's
        // measured CI lesson: never rely on another class's seeding).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
    }

    /**
     * The full journey: the offer's own terms price the fee (3 days x 200 =
     * 600, server-derived), the period is exclusive (the competing request
     * answers 409), the handover waits for the money (the honest 409), the
     * settlement marks the loan paid, the ledger credits the owner and
     * debits the frozen commission (10% test rate), the handover activates
     * the loan, the return cycle runs borrower→owner, the close settles
     * (no lateness — the period ended after the return), both parties read
     * the LOAN_CLOSED notification, the terminal guards answer 409, the
     * stranger's read answers the honest 404, and the closed period is
     * free for the next borrower.
     */
    @Test
    void theFullLendingJourneyFromOfferThroughThePaidHandoverToClosure() throws Exception {
        // The owner provisions FIRST (ADR-0002): the store's own records —
        // the product the offer will ride, with stock, by the PROVIDER.
        String ownerName = "it-lend-owner-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(ownerName, "USER", "PROVIDER");
        GateResult ownerGate = loginGate(ownerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", ownerGate.accessToken()).statusCode())
                .as("the owner's /me provisioning sync").isEqualTo(200);
        UUID product = registerProduct(ownerGate.accessToken(), 1500L);
        setStock(ownerGate.accessToken(), product, 1);

        // (1) The owner's offer — the OWNER's own terms (daily fee 200,
        // deposit 500, the frozen per-day late surcharge 50).
        HttpResponse<String> offer = postJson("/api/v1/lending/offers/" + product, ownerGate.accessToken(), """
                {"dailyFeeMinor":200,"depositMinor":500,"lateFeePerDayMinor":50}""");
        assertThat(offer.statusCode()).as("offer publish: %s", body(offer)).isEqualTo(200);
        assertThat(objectMapper.readTree(offer.body()).path("dailyFeeMinor").asLong()).isEqualTo(200L);

        // (2) The borrower's request — a 3-day window starting in 2 days.
        String borrowerName = "it-lend-borrower-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(borrowerName, "CONSUMER");
        GateResult borrowerGate = loginGate(borrowerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", borrowerGate.accessToken()).statusCode())
                .as("the borrower's /me provisioning sync").isEqualTo(200);
        Instant startAt = Instant.now().plus(Duration.ofDays(2));
        Instant endAt = startAt.plus(Duration.ofDays(3));
        HttpResponse<String> request = postJson("/api/v1/lending/items/" + product + "/loans",
                borrowerGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}""".formatted(startAt, endAt));
        assertThat(request.statusCode()).as("loan request: %s", body(request)).isEqualTo(201);
        JsonNode loanView = objectMapper.readTree(request.body());
        UUID loanId = UUID.fromString(loanView.path("id").asString());
        assertThat(loanView.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(loanView.path("feeMinor").asLong())
                .as("the fee is the OWNER's terms: 3 days x 200 — never caller-supplied").isEqualTo(600L);
        assertThat(loanView.path("currency").asString()).isEqualTo("SAR");

        // (3) The period's exclusivity — a second borrower's overlapping
        // request answers the honest 409 (the transactional gate; the
        // V200 EXCLUDE constraint is the race's backstop).
        String rivalName = "it-lend-rival-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(rivalName, "CONSUMER");
        GateResult rivalGate = loginGate(rivalName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", rivalGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> rivalRequest = postJson("/api/v1/lending/items/" + product + "/loans",
                rivalGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}"""
                        .formatted(startAt.plus(Duration.ofDays(1)), endAt.plus(Duration.ofDays(1))));
        assertThat(rivalRequest.statusCode()).as("the overlapping window: %s", body(rivalRequest)).isEqualTo(409);

        // (4) The LOAN_REQUESTED notification landed for the owner (the
        // event machinery's receipt — the payload carries the truth).
        awaitTypedNotificationRows(loanId, "LOAN_REQUESTED", 1);

        // (5) The owner's approval — the fee's intent opens through the
        // EXISTING engine (the LOAN origin, the deterministic key).
        HttpResponse<String> approval = postJson("/api/v1/lending/loans/" + loanId + "/approve",
                ownerGate.accessToken(), "");
        assertThat(approval.statusCode()).as("approve: %s", body(approval)).isEqualTo(200);
        assertThat(objectMapper.readTree(approval.body()).path("status").asString()).isEqualTo("APPROVED");
        UUID intentId = UUID.fromString(
                objectMapper.readTree(approval.body()).path("paymentIntentId").asString());
        assertThat(intentId).as("the approval links the fee's intent").isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin FROM payment_intents WHERE id = ?", String.class, intentId))
                .as("the intent's LOAN origin").isEqualTo("LOAN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM payment_intents WHERE id = ?", String.class, intentId))
                .as("the deterministic loan-{id} key (the first line of defense)").isEqualTo("loan-" + loanId);

        // (6) The honest handover gate FIRST: the handover before the
        // money settles answers the domain 409 (the A5 classification —
        // a domain conflict, never a 500).
        HttpResponse<String> prematureHandover = postJson(
                "/api/v1/lending/loans/" + loanId + "/handover", ownerGate.accessToken(), "");
        assertThat(prematureHandover.statusCode())
                .as("handover before the settled fee: %s", body(prematureHandover)).isEqualTo(409);

        // (7) The money loop (the J20 shape verbatim): the borrower's
        // process step (PROCESSING + the Payment row), then the
        // administrative capture (SUCCEEDED + the COMPLETED event).
        HttpResponse<String> process = postJson(
                "/api/v1/payments/intents/" + intentId + "/process", borrowerGate.accessToken(), "");
        assertThat(process.statusCode()).as("process: %s", body(process)).isEqualTo(200);
        assertThat(objectMapper.readTree(process.body()).path("status").asString()).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE payment_intent_id = ?", Integer.class, intentId))
                .as("the process step's Payment row").isEqualTo(1);
        String adminName = "it-lend-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> capture = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j23-capture-" + intentId + "\"}");
        assertThat(capture.statusCode()).as("admin capture: %s", body(capture)).isEqualTo(200);
        assertThat(objectMapper.readTree(capture.body()).path("status").asString()).isEqualTo("SUCCEEDED");

        // (8) The settlement listener's write: the loan is PAID (paidAt
        // stamped, the state guard's idempotent edge) — the payment
        // COMPLETED event drove the real LendingPaymentListener.
        awaitLoanPaid(loanId);

        // (9) The ledger's loan-origin settlement (ADR-0013 D2 — the
        // frozen commission): the owner's credit rides the intent's own
        // source, the commission debit rides its nameUUID key, and the
        // amounts are the intent's own (600 credit, 60 = 10% frozen).
        awaitLedgerRow(intentId, "PAYMENT_CREDIT", 1);
        UUID commissionSource = UUID.nameUUIDFromBytes(
                ("commission-" + intentId.toString()).getBytes());
        awaitLedgerRow(commissionSource, "COMMISSION_DEBIT", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ? AND entry_type = 'PAYMENT_CREDIT'",
                Long.class, intentId)).isEqualTo(600L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ? AND entry_type = 'COMMISSION_DEBIT'",
                Long.class, commissionSource))
                .as("the commission FROZEN on the intent at creation (ADR-0013 D2)").isEqualTo(60L);

        // (10) The owner confirms the handover — the paid gate satisfied.
        HttpResponse<String> handover = postJson(
                "/api/v1/lending/loans/" + loanId + "/handover", ownerGate.accessToken(), "");
        assertThat(handover.statusCode()).as("handover: %s", body(handover)).isEqualTo(200);
        assertThat(objectMapper.readTree(handover.body()).path("status").asString()).isEqualTo("ACTIVE");

        // (11) The return cycle: the borrower initiates, the owner confirms.
        HttpResponse<String> returnRequest = postJson(
                "/api/v1/lending/loans/" + loanId + "/return-request", borrowerGate.accessToken(), "");
        assertThat(returnRequest.statusCode()).as("return request: %s", body(returnRequest)).isEqualTo(200);
        assertThat(objectMapper.readTree(returnRequest.body()).path("status").asString())
                .isEqualTo("RETURN_REQUESTED");
        HttpResponse<String> returnConfirm = postJson(
                "/api/v1/lending/loans/" + loanId + "/return-confirm", ownerGate.accessToken(), "");
        assertThat(returnConfirm.statusCode()).as("return confirm: %s", body(returnConfirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(returnConfirm.body()).path("status").asString()).isEqualTo("RETURNED");

        // (12) The owner's close — terminal, on time (no lateness priced).
        HttpResponse<String> close = postJson(
                "/api/v1/lending/loans/" + loanId + "/close", ownerGate.accessToken(), "");
        assertThat(close.statusCode()).as("close: %s", body(close)).isEqualTo(200);
        JsonNode closedView = objectMapper.readTree(close.body());
        assertThat(closedView.path("status").asString()).isEqualTo("CLOSED");
        assertThat(closedView.path("lateDays").asInt()).as("an on-time return prices no lateness").isZero();
        assertThat(closedView.path("lateFeeMinor").asLong()).isZero();

        // (13) The LOAN_CLOSED notification reached BOTH parties (the
        // close event's recipient contract).
        awaitTypedNotificationRows(loanId, "LOAN_CLOSED", 2);

        // (14) The terminal guards: the second close and the late cancel
        // both answer the honest 409.
        HttpResponse<String> secondClose = postJson(
                "/api/v1/lending/loans/" + loanId + "/close", ownerGate.accessToken(), "");
        assertThat(secondClose.statusCode()).isEqualTo(409);
        HttpResponse<String> lateCancel = deleteWithBearer(
                "/api/v1/lending/loans/" + loanId, borrowerGate.accessToken(),
                "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode()).as("cancel after closure: %s", body(lateCancel)).isEqualTo(409);

        // (15) The privacy contract: a stranger's read answers the honest
        // 404 (existence itself is private — the party gate).
        String strangerName = "it-lend-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(strangerName, "CONSUMER");
        GateResult strangerGate = loginGate(strangerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", strangerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> strangerRead = getWithBearer(
                "/api/v1/lending/loans/" + loanId, strangerGate.accessToken());
        assertThat(strangerRead.statusCode()).as("stranger read: existence itself is private").isEqualTo(404);

        // (16) The closed period is FREE: the next borrower's request on
        // the same window succeeds (CLOSED is not a live hold).
        HttpResponse<String> rebooking = postJson("/api/v1/lending/items/" + product + "/loans",
                rivalGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}""".formatted(startAt, endAt));
        assertThat(rebooking.statusCode()).as("the freed period: %s", body(rebooking)).isEqualTo(201);
    }

    /**
     * ADR-0009's late-return pricing law, measured on the machine: the
     * lateness is derived from the stamped {@code returned_at} against the
     * period's end — WHOLE days, a partial day rents the whole day — and
     * priced with the OWNER's frozen per-day rate (never caller-supplied).
     * The journey uses a period that already ended, so the machine's own
     * {@code Instant.now()} stamp at the return confirmation is the late
     * fact the close prices.
     */
    @Test
    void theLateReturnPricesTheOwnersFrozenLateRate() throws Exception {
        String ownerName = "it-lend-late-owner-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(ownerName, "USER", "PROVIDER");
        GateResult ownerGate = loginGate(ownerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", ownerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID product = registerProduct(ownerGate.accessToken(), 900L);
        setStock(ownerGate.accessToken(), product, 1);
        HttpResponse<String> offer = postJson("/api/v1/lending/offers/" + product, ownerGate.accessToken(), """
                {"dailyFeeMinor":100,"depositMinor":300,"lateFeePerDayMinor":50}""");
        assertThat(offer.statusCode()).as("offer publish: %s", body(offer)).isEqualTo(200);

        // A 3-day period that ALREADY ended 2 days ago — the machine's
        // own now-stamped return will be the late fact (2 whole days).
        Instant startAt = Instant.now().minus(Duration.ofDays(5));
        Instant endAt = Instant.now().minus(Duration.ofDays(2));
        String borrowerName = "it-lend-late-borrower-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(borrowerName, "CONSUMER");
        GateResult borrowerGate = loginGate(borrowerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", borrowerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> request = postJson("/api/v1/lending/items/" + product + "/loans",
                borrowerGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}""".formatted(startAt, endAt));
        assertThat(request.statusCode()).as("loan request: %s", body(request)).isEqualTo(201);
        UUID loanId = UUID.fromString(objectMapper.readTree(request.body()).path("id").asString());
        assertThat(objectMapper.readTree(request.body()).path("feeMinor").asLong())
                .as("the past period's fee is still the owner's terms: 3 days x 100").isEqualTo(300L);

        UUID intentId = settleTheFee(loanId, ownerGate, borrowerGate);
        awaitLoanPaid(loanId);
        assertThat(postJson("/api/v1/lending/loans/" + loanId + "/handover",
                ownerGate.accessToken(), "").statusCode()).isEqualTo(200);
        assertThat(postJson("/api/v1/lending/loans/" + loanId + "/return-request",
                borrowerGate.accessToken(), "").statusCode()).isEqualTo(200);
        HttpResponse<String> returnConfirm = postJson(
                "/api/v1/lending/loans/" + loanId + "/return-confirm", ownerGate.accessToken(), "");
        assertThat(returnConfirm.statusCode()).isEqualTo(200);

        // The close prices the lateness: returned_at (now) is 2 whole days
        // past the period's end — 2 x the owner's frozen 50.
        HttpResponse<String> close = postJson(
                "/api/v1/lending/loans/" + loanId + "/close", ownerGate.accessToken(), "");
        assertThat(close.statusCode()).as("close: %s", body(close)).isEqualTo(200);
        JsonNode closedView = objectMapper.readTree(close.body());
        assertThat(closedView.path("status").asString()).isEqualTo("CLOSED");
        assertThat(closedView.path("lateDays").asInt())
                .as("the derived lateness: whole days from the stamped return").isEqualTo(2);
        assertThat(closedView.path("lateFeeMinor").asLong())
                .as("the OWNER's frozen rate prices the lateness: 2 x 50").isEqualTo(100L);
        // The auditable fact rides the loan row (Envers-audited).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT late_fee_minor FROM loans WHERE id = ?", Long.class, loanId)).isEqualTo(100L);
    }

    /**
     * The cancellation branch — the ONE refund contract (the
     * OrderCancelledEventListener twin): a borrower's cancel of an
     * APPROVED loan whose fee was COLLECTED refunds it in FULL (the intent
     * REFUNDED, the ledger's refund debit mirroring the owner's credit),
     * and a cancel of an UNPAID approved loan aborts the collectible
     * attempt where it stands (the intent CANCELLED, no money ever
     * moved). The closed loan stays closed (the second cancel 409).
     */
    @Test
    void theCancellationBranchSettlesThroughTheOneRefundContract() throws Exception {
        String ownerName = "it-lend-cancel-owner-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(ownerName, "USER", "PROVIDER");
        GateResult ownerGate = loginGate(ownerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", ownerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID product = registerProduct(ownerGate.accessToken(), 700L);
        setStock(ownerGate.accessToken(), product, 1);
        assertThat(postJson("/api/v1/lending/offers/" + product, ownerGate.accessToken(), """
                {"dailyFeeMinor":150,"depositMinor":200,"lateFeePerDayMinor":0}""")
                .statusCode()).isEqualTo(200);

        // -- The COLLECTED branch: the fee was paid, the borrower cancels. --
        Instant startAt = Instant.now().plus(Duration.ofDays(1));
        Instant endAt = startAt.plus(Duration.ofDays(2));
        String borrowerName = "it-lend-cancel-borrower-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(borrowerName, "CONSUMER");
        GateResult borrowerGate = loginGate(borrowerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", borrowerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> request = postJson("/api/v1/lending/items/" + product + "/loans",
                borrowerGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}""".formatted(startAt, endAt));
        assertThat(request.statusCode()).as("loan request: %s", body(request)).isEqualTo(201);
        UUID loanId = UUID.fromString(objectMapper.readTree(request.body()).path("id").asString());

        UUID intentId = settleTheFee(loanId, ownerGate, borrowerGate);
        awaitLoanPaid(loanId);

        // The borrower cancels the approved, paid loan — the money settles
        // through the event (the payments module's own listener).
        HttpResponse<String> cancellation = deleteWithBearer(
                "/api/v1/lending/loans/" + loanId, borrowerGate.accessToken(),
                "{\"reason\":\"plans changed\"}");
        assertThat(cancellation.statusCode()).as("cancel: %s", body(cancellation)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancellation.body()).path("status").asString()).isEqualTo("CANCELLED");

        // The ONE refund contract executed: the intent REFUNDED, the
        // ledger's refund debit mirroring the owner's credit (the same
        // nameUUID source key the full-refund mirror rides).
        awaitIntentStatus(intentId, "REFUNDED");
        UUID refundSource = UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes());
        awaitLedgerRow(refundSource, "REFUND_DEBIT", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ? AND entry_type = 'REFUND_DEBIT'",
                Long.class, refundSource))
                .as("the refund mirrors the collected fee in full").isEqualTo(300L);
        // The borrower's receipt (the event machinery's row).
        awaitTypedNotificationRows(loanId, "LOAN_CANCELLED", 1);

        // The cancelled loan is terminal — the second cancel answers 409.
        HttpResponse<String> secondCancel = deleteWithBearer(
                "/api/v1/lending/loans/" + loanId, borrowerGate.accessToken(),
                "{\"reason\":\"twice\"}");
        assertThat(secondCancel.statusCode()).isEqualTo(409);

        // -- The UNPAID branch: the collectible attempt aborts where it
        // stands (the intent CANCELLED, no Payment row, no ledger entry). --
        HttpResponse<String> request2 = postJson("/api/v1/lending/items/" + product + "/loans",
                borrowerGate.accessToken(), """
                        {"startAt":"%s","endAt":"%s"}"""
                        .formatted(endAt.plus(Duration.ofDays(1)), endAt.plus(Duration.ofDays(3))));
        assertThat(request2.statusCode()).as("the freed window's request: %s", body(request2)).isEqualTo(201);
        UUID loan2Id = UUID.fromString(objectMapper.readTree(request2.body()).path("id").asString());
        HttpResponse<String> approval2 = postJson("/api/v1/lending/loans/" + loan2Id + "/approve",
                ownerGate.accessToken(), "");
        assertThat(approval2.statusCode()).isEqualTo(200);
        UUID intent2Id = UUID.fromString(
                objectMapper.readTree(approval2.body()).path("paymentIntentId").asString());

        HttpResponse<String> cancellation2 = deleteWithBearer(
                "/api/v1/lending/loans/" + loan2Id, borrowerGate.accessToken(),
                "{\"reason\":\"found another item\"}");
        assertThat(cancellation2.statusCode()).as("unpaid cancel: %s", body(cancellation2)).isEqualTo(200);
        awaitIntentStatus(intent2Id, "CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE payment_intent_id = ?", Integer.class, intent2Id))
                .as("no money ever moved on the unpaid branch").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class, intent2Id))
                .as("no ledger entry on the unpaid branch").isZero();
    }

    // ------------------------------------------------------------------
    // The journey's shared money loop (the J20 shape verbatim).
    // ------------------------------------------------------------------

    /** Approves the loan and settles its fee: process + administrative capture. */
    private UUID settleTheFee(UUID loanId, GateResult ownerGate, GateResult borrowerGate) throws Exception {
        HttpResponse<String> approval = postJson("/api/v1/lending/loans/" + loanId + "/approve",
                ownerGate.accessToken(), "");
        assertThat(approval.statusCode()).as("approve: %s", body(approval)).isEqualTo(200);
        UUID intentId = UUID.fromString(
                objectMapper.readTree(approval.body()).path("paymentIntentId").asString());
        assertThat(postJson("/api/v1/payments/intents/" + intentId + "/process",
                borrowerGate.accessToken(), "").statusCode()).isEqualTo(200);
        String adminName = "it-lend-money-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> capture = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j23-settle-" + intentId + "\"}");
        assertThat(capture.statusCode()).as("admin capture: %s", body(capture)).isEqualTo(200);
        return intentId;
    }

    /** The bounded poll for the settlement listener's paid write (the AFTER_COMMIT hop). */
    private void awaitLoanPaid(UUID loanId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            java.sql.Timestamp paidAt = jdbcTemplate.queryForObject(
                    "SELECT paid_at FROM loans WHERE id = ?", java.sql.Timestamp.class, loanId);
            if (paidAt != null) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /** The bounded poll for the intent's own status (the refund/cancel listener hop). */
    private void awaitIntentStatus(UUID intentId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /** The bounded poll for a typed ledger row (the listener's AFTER_COMMIT hop). */
    private void awaitLedgerRow(UUID sourceId, String entryType, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE source_id = ? AND entry_type = ?",
                    Integer.class, sourceId, entryType);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
    }

    // ------------------------------------------------------------------
    // The login gate machinery (the L23 five-step sequence, house verbatim)
    // ------------------------------------------------------------------

    private void registerUser(String username, String... roles) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(roles.length == 0 ? new String[]{"USER"} : roles)
                .build());
    }

    // ------------------------------------------------------------------
    // Stage 6 (ADR-0002): the owner's commerce provisioning — the REAL
    // product rows the offer's ACTIVE-storefront gate resolves.
    // ------------------------------------------------------------------

    private UUID registerProduct(String ownerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", ownerToken, """
                {"storeCategoryCode":"journey-appliances","title":"Journey lendable",
                 "description":"Stage-8 journey stock","priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private void setStock(String ownerToken, UUID productId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/products/" + productId + "/inventory",
                ownerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("stock write: %s", body(stocked)).isEqualTo(200);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Lending Journey Gate Integration Test Client");
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode()).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(), sessionCookie);
    }

    private record GateResult(String accessToken, String sessionCookie) {
    }

    /**
     * The EXACT and TYPED wait (the house polling shape: 200ms steps, 10s
     * ceiling) — it polls the very count the assertion will read:
     * type-filtered, loan-named.
     */
    private void awaitTypedNotificationRows(UUID loanId, String type, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE type = ? AND message LIKE '%' || ? || '%'",
                    Integer.class, type, loanId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        return get(baseUrl() + path, null, accessToken);
    }

    private HttpResponse<String> get(String url, String sessionCookie, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonOrPut(String path, String accessToken, String json,
                                                String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying delete is a method override string on the builder.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(c -> SESSION_COOKIE.matcher(c))
                .filter(Matcher::find)
                .map(m -> m.group(1) + "=" + m.group(2))
                .findFirst().orElse("");
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie.isBlank() ? fallback : cookie;
    }

    private String csrfTokenFrom(String html) {
        Matcher direct = CSRF_INPUT.matcher(html);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(html);
        return reversed.find() ? reversed.group(1) : "";
    }

    private String queryParam(String url, String param) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(param) + "=([^&]+)").matcher(url);
        assertThat(matcher.find()).as("%s must be present in %s", param, url).isTrue();
        return matcher.group(1);
    }

    private String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
