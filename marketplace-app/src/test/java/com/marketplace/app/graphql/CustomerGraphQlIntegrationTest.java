package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P1 (the canonical Saleor plan §5-P1 — packet campaign-b3-a1b):
 * end-to-end proof of the Saleor Users-domain {@code customers} read root
 * through the REAL SDL, the REAL DGS wiring, the REAL security chain (the
 * mocked JwtDecoder stamps the caller's subject and roles claim exactly
 * the way the production converter reads them), the REAL identity owner
 * (no SPI mocking: the ADMIN enforcement and the CONSUMER-population
 * keyset scroll execute against the real PostgreSQL over the SAME single
 * identity store the {@code staffUsers} roster reads), and the REAL typed
 * global-ID codec (the SHARED {@code UserGraphQlMapper} wire projection).
 *
 * <p>The acceptance legs mirror the packet's gate: the population scoping
 * (the customer list answers ONLY the customer/consumer-role accounts —
 * staff accounts excluded, and the complementary roster root keeps
 * answering the whole store), the forward-only keyset scroll with cursor
 * follow-through, the round-trip global-ID stability (the list's own
 * emitted typed global ID read back through the {@code user} root, with
 * the guessed-ID null and the malformed/raw/mistyped-ID VAL-001 legs of
 * the same journey), the anonymous rejection (the /graphql chain's 401
 * problem+json contract), the wrong-role denial (PROVIDER and CONSUMER
 * callers — AUTHZ-001 by the owning use case), the empty page, and the
 * classified errors for the declared-but-unexecuted arguments (filter/
 * where/search/before/last/unowned sort fields — never silent no-ops).</p>
 */
/**
 * The real migrated schema, not Hibernate's ddl-auto approximation (the
 * UserGraphQlIntegrationTest precedent): the customer population's keyset
 * SQL and the seeded rows run against the Flyway V1..V247 schema — the
 * honest standing shape (the seed migration's own rows are part of the
 * population the assertions reason about relatively, never by absolute
 * count).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CustomerGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p1c-customers-admin-subject";
    private static final String PROVIDER_SUBJECT = "p1c-customers-provider-subject";
    private static final String CONSUMER_SUBJECT = "p1c-customers-consumer-subject";

    private static final UUID ADMIN_ROW_ID = UUID.fromString("56565656-5656-4656-8656-565656565601");
    private static final UUID PROVIDER_ROW_ID = UUID.fromString("56565656-5656-4656-8656-565656565602");
    private static final UUID CONSUMER_ONE_ROW_ID = UUID.fromString("56565656-5656-4656-8656-565656565603");
    private static final UUID CONSUMER_TWO_ROW_ID = UUID.fromString("56565656-5656-4656-8656-565656565604");
    private static final UUID CONSUMER_THREE_ROW_ID = UUID.fromString("56565656-5656-4656-8656-565656565605");

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
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    /**
     * The seeded population: an ADMIN (the caller's own row — a staff
     * account that must NEVER surface through customers), a PROVIDER (the
     * same exclusion proof, plus the seed migration's own provider rows),
     * and three CONSUMER rows (the customer population this class asserts
     * on — the honest pagination surface). The subjects are unique to this
     * class so reruns cannot collide.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_role_assignments WHERE user_id IN (?, ?, ?, ?, ?)",
                ADMIN_ROW_ID, PROVIDER_ROW_ID, CONSUMER_ONE_ROW_ID, CONSUMER_TWO_ROW_ID, CONSUMER_THREE_ROW_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?, ?, ?)",
                ADMIN_ROW_ID, PROVIDER_ROW_ID, CONSUMER_ONE_ROW_ID, CONSUMER_TWO_ROW_ID, CONSUMER_THREE_ROW_ID);
        insertUser(ADMIN_ROW_ID, ADMIN_SUBJECT, "p1c-admin@example.com", "P1C Admin", "ADMIN");
        insertUser(PROVIDER_ROW_ID, PROVIDER_SUBJECT, "p1c-provider@example.com", "P1C Provider", "PROVIDER");
        insertUser(CONSUMER_ONE_ROW_ID, "p1c-customer-one-subject", "p1c-customer-one@example.com", "P1C Customer One", "CONSUMER");
        insertUser(CONSUMER_TWO_ROW_ID, "p1c-customer-two-subject", "p1c-customer-two@example.com", "P1C Customer Two", "CONSUMER");
        insertUser(CONSUMER_THREE_ROW_ID, "p1c-customer-three-subject", "p1c-customer-three@example.com", "P1C Customer Three", "CONSUMER");
    }

    private void insertUser(UUID id, String subject, String email, String displayName, String role) {
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, subject, email, displayName, role);
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

    /** The typed global ID the codec emits — base64("User:<uuid>"). */
    private static String globalIdOf(UUID nativeId) {
        return java.util.Base64.getEncoder()
                .encodeToString(("User:" + nativeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // customers — the CONSUMER population with the Saleor envelope
    // ------------------------------------------------------------------

    @Test
    void adminCustomersAnswersOnlyTheConsumerPopulationStaffExcluded() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var page = adminTester.document("""
                        query { customers(first: 100) {
                            edges { node { id email isStaff isActive } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3))
                .path("customers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false)
                .path("customers.edges[*].node.email").entityList(String.class)
                // The customer population answers the seeded CONSUMER rows…
                .contains("p1c-customer-one@example.com",
                        "p1c-customer-two@example.com",
                        "p1c-customer-three@example.com")
                // …and NEVER the staff accounts: this class's ADMIN/PROVIDER
                // rows and the seed migration's own provider rows are all
                // excluded by the population's own scoping.
                .satisfies(emails -> assertThat(emails).doesNotContain(
                        "p1c-admin@example.com",
                        "p1c-provider@example.com",
                        "info@qudsayya-realty.com"));

        // The complementary root keeps answering the WHOLE single identity
        // store: the roster's total strictly exceeds the customer
        // population's (the staff accounts are in the roster, not in
        // customers) — the scoping proof read from both roots' own totals.
        Integer customerTotal = page.path("customers.totalCount").entity(Integer.class).get();
        Integer rosterTotal = adminTester.document("""
                        query { staffUsers(first: 1) { totalCount } }
                        """)
                .execute()
                .errors().verify()
                .path("staffUsers.totalCount").entity(Integer.class).get();
        assertThat(rosterTotal).isGreaterThan(customerTotal);

        // The staff rows the roster answers never surface as customer IDs.
        page.path("customers.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .doesNotContain(globalIdOf(ADMIN_ROW_ID), globalIdOf(PROVIDER_ROW_ID)));
    }

    @Test
    void customersFollowsTheForwardKeysetCursorWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { customers(first: 2) {
                            edges { node { email } cursor }
                            pageInfo { hasNextPage hasPreviousPage endCursor }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges").entityList(Object.class).hasSize(2);

        List<String> firstPageEmails =
                firstPage.path("customers.edges[*].node.email").entityList(String.class).get();
        assertThat(firstPageEmails.get(0)).isLessThanOrEqualTo(firstPageEmails.get(1));
        String firstEndCursor = firstPage.path("customers.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's own sort key: following it continues
        // the same email-ascending scroll strictly after that row — no
        // overlap — with the honest forward-only hasPreviousPage fact.
        adminTester.document("""
                        query($after: String) { customers(first: 2, after: $after) {
                            edges { node { email } }
                            pageInfo { hasNextPage hasPreviousPage endCursor }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("customers.edges").entityList(Object.class).hasSize(2)
                .path("customers.edges[0].node.email").entity(String.class)
                .satisfies(email -> assertThat(email).isGreaterThan(firstPageEmails.get(1)))
                .path("customers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void customersExecutesTheOwningSortFields() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 2, sortBy: { field: CREATED_AT, direction: DESC }) {
                            edges { node { email dateJoined } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges").entityList(Object.class).hasSize(2);
    }

    @Test
    void customersFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 2, filter: { search: "p1c" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("filter is not implemented");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void customersWhereAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 2, where: { isActive: true }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("where filter is not implemented");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void customersSearchAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 2, search: "p1c") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("search is not implemented");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void customersBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("query { customers(last: 2) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("forward-only");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void customersUnownedSortFieldAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 2, sortBy: { field: FIRST_NAME, direction: ASC }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("FIRST_NAME");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void cursorFromAnotherSortAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // A cursor emitted by a different sort (createdAt) is not valid for
        // the default email sort — the keyset gate rejects the mismatch.
        List<String> createdAtCursorCapture = new java.util.ArrayList<>();
        adminTester.document("""
                        query { customers(first: 1, sortBy: { field: CREATED_AT, direction: ASC }) {
                            edges { cursor }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges[0].cursor").entity(String.class)
                .satisfies(createdAtCursorCapture::add);
        assertThat(createdAtCursorCapture).hasSize(1);
        String createdAtCursor = createdAtCursorCapture.get(0);

        adminTester.document("""
                        query($after: String) { customers(first: 1, after: $after) { totalCount } }
                        """)
                .variable("after", createdAtCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void nonPositiveFirstAnswersTheEmptyPage() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { customers(first: 0) { edges { node { id } } totalCount pageInfo { hasNextPage } } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges").entityList(Object.class).hasSize(0)
                .path("customers.totalCount").valueIsNull()
                .path("customers.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // the customers journey's global-ID contract (read-back via the
    // user root — the customers root itself takes no id argument)
    // ------------------------------------------------------------------

    @Test
    void customerRoundTripsTheIdTheListItselfEmittedThroughTheUserRoot() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        List<String> emittedIds = adminTester.document("""
                        query { customers(first: 100) { edges { node { id email isStaff } } } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges[*].node.id").entityList(String.class).get();

        // The emitted wire identity is the codec's typed global ID for the
        // seeded customer row — never a raw UUID — and the customer is the
        // honest non-staff projection.
        String emittedId = globalIdOf(CONSUMER_ONE_ROW_ID);
        assertThat(emittedIds).contains(emittedId);
        int position = emittedIds.indexOf(emittedId);
        adminTester.document("""
                        query { customers(first: 100) { edges { node { id isStaff } } } }
                        """)
                .execute()
                .errors().verify()
                .path("customers.edges[" + position + "].node.id").entity(String.class)
                .isEqualTo(emittedId)
                .path("customers.edges[" + position + "].node.isStaff").entity(Boolean.class).isEqualTo(false);

        // The read-back journey: the list's own emitted global ID resolves
        // the same account through the user root (the Users read surface's
        // single shared wire identity).
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id email isStaff } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("user.id").entity(String.class).isEqualTo(emittedId)
                .path("user.email").entity(String.class).isEqualTo("p1c-customer-one@example.com")
                .path("user.isStaff").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID guessed = UUID.randomUUID();
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id email } }
                        """)
                .variable("id", globalIdOf(guessed))
                .execute()
                .errors().verify()
                .path("user").valueIsNull();
    }

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("Invalid global ID");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getExtensions()).containsEntry("category", "validation");
                })
                .path("user").valueIsNull();
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", CONSUMER_ONE_ROW_ID.toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("Invalid global ID");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                })
                .path("user").valueIsNull();
    }

    @Test
    void mistypedGlobalIdForAnotherGraphqlTypeIsRejected() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String productTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Product:" + CONSUMER_ONE_ROW_ID)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", productTaggedId)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                })
                .path("user").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the role matrix — the owning use case's ADMIN enforcement
    // ------------------------------------------------------------------

    @Test
    void providerCallerIsDeniedTheCustomerListByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        providerTester.document("query { customers(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("customers").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedTheCustomerListByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("query { customers(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("customers").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the anonymous boundary — the /graphql chain's 401 problem+json
    // ------------------------------------------------------------------

    @Test
    void anonymousCustomerQueryIsRejectedAs401ProblemDetail() {
        // No Authorization header: the security chain answers the shared
        // problem+json 401 AUTHN-001 contract before any GraphQL handling —
        // the tripartite anonymous contract GraphQlSecurityIntegrationTest
        // pins, asserted here for the customers root's own journey.
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ customers(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"marketplace\"")
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://marketplace.com/errors/unauthorized")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/graphql")
                .jsonPath("$.errorCode").isEqualTo("AUTHN-001");
    }
}
