package com.marketplace.app.graphql;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.orders.Order;
import com.marketplace.orders.OrderRepository;
import test.config.IntegrationContainers;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P4 (the canonical Saleor plan §5-P4 — packet campaign-b5-b):
 * end-to-end proof of the Saleor Orders-domain {@code order} and
 * {@code orders} read roots through the REAL SDL, the REAL DGS
 * wiring, the REAL security chain (the mocked JwtDecoder stamps the
 * caller's subject and roles claim exactly the way the production
 * converter reads them), the REAL orders owner (no SPI mocking: the
 * REAL SQL keyset scrolls execute against the real PostgreSQL over
 * the SAME authoritative orders rows the placement machine writes),
 * and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the packet's gate: the ADMIN-ONLY
 * management surface (the ADMIN caller's list and lookup; the
 * PROVIDER and CONSUMER callers are DENIED by the owning use case's
 * method security — the AUTHZ-001 taxonomy, never a silent read and
 * never a population fallback; the anonymous caller answers the
 * /graphql chain's 401 problem+json contract), the forward-only
 * keyset scroll with cursor follow-through in the placement-instant
 * sort (the D-N5 complete ordering key's own column), the lifecycle
 * ADAPT mapping on the wire (the platform's PLACED renders upstream's
 * UNCONFIRMED, CONFIRMED renders UNFULFILLED, FULFILLED/CANCELLED
 * render their upstream spellings), the round-trip global-ID
 * stability, the guessed-ID null, the malformed/raw/mistyped-ID
 * VAL-001 legs, the empty page, and the classified errors for the
 * declared-but-unexecuted arguments (filter/where/search/channel/
 * before/last/unowned sort fields/externalReference/keyless — never
 * silent no-ops).</p>
 *
 * <p>The real migrated schema, not Hibernate's ddl-auto approximation
 * (the ProductGraphQlIntegrationTest precedent): the scrolls and the
 * seeded rows run against the Flyway V1..V247 schema — the honest
 * standing shape (the seed migration's own rows are part of the
 * population the assertions reason about relatively, never by
 * absolute count).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderGraphQlIntegrationTest {

    private static final String CONSUMER_SUBJECT = "p4-orders-consumer-subject";
    private static final String PROVIDER_SUBJECT = "p4-orders-provider-subject";
    private static final String ADMIN_SUBJECT = "p4-orders-admin-subject";

    /** The base64 prefix every typed Order global ID starts with on the wire. */
    private static final String ORDER_ID_PREFIX = java.util.Base64.getEncoder()
            .encodeToString("Order:".getBytes(java.nio.charset.StandardCharsets.UTF_8));

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
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final List<UUID> seededOrderIds = new ArrayList<>();
    private UUID placedOrderId;
    private UUID confirmedOrderId;
    private UUID fulfilledOrderId;
    private UUID cancelledOrderId;

    @BeforeEach
    void seedOrders() {
        // The A1 convention the orders FK graph demands (fk_orders_users):
        // an order's consumer and seller are USERS rows — the seed places
        // real ones before the orders reference them (the pre-integration
        // round's seed generated random ids that were never inserted, so
        // every order INSERT violated the foreign key and the whole class
        // errored at seed time).
        UUID consumerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        userRepository.save(new User(consumerId, consumerId.toString(), null,
                "it-order consumer", com.marketplace.identity.UserRole.CONSUMER));
        userRepository.save(new User(sellerId, sellerId.toString(), null,
                "it-order seller", com.marketplace.identity.UserRole.PROVIDER));

        Order placed = orderRepository.save(Order.placed(consumerId, 2500L, "SAR", sellerId));
        Order confirmed = Order.placed(consumerId, 3100L, "SAR", sellerId);
        confirmed.confirm(Instant.now());
        confirmed = orderRepository.save(confirmed);
        Order fulfilled = Order.placed(consumerId, 1900L, "SAR", sellerId);
        fulfilled.confirm(Instant.now());
        fulfilled.fulfill(Instant.now());
        fulfilled = orderRepository.save(fulfilled);
        Order cancelled = Order.placed(consumerId, 700L, "SAR", sellerId);
        cancelled.cancel("it-order-fixture", Instant.now());
        cancelled = orderRepository.save(cancelled);

        placedOrderId = placed.getId();
        confirmedOrderId = confirmed.getId();
        fulfilledOrderId = fulfilled.getId();
        cancelledOrderId = cancelled.getId();
        seededOrderIds.addAll(List.of(placedOrderId, confirmedOrderId, fulfilledOrderId, cancelledOrderId));
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededOrderIds.isEmpty()) {
            orderRepository.deleteAllById(new ArrayList<>(seededOrderIds));
            seededOrderIds.clear();
        }
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (authorities claim "roles", prefix ROLE_).
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

    /** A well-formed typed global ID for the Order type over any native UUID. */
    private static String orderGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Order:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // the ADMIN-only management surface
    // ------------------------------------------------------------------

    @Test
    void adminSeesTheOrderPopulationWithTheMappedLifecycleVocabulary() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 100) {
                            edges { node { id created status } }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges[*].node.status").entityList(String.class)
                .satisfies(statuses -> {
                    // The lifecycle ADAPT mapping on the wire: every seeded
                    // platform state renders its own upstream vocabulary value.
                    assertThat(statuses).contains("UNCONFIRMED", "UNFULFILLED", "FULFILLED", "CANCELED");
                    assertThat(statuses).doesNotContain("PLACED", "CONFIRMED", "CANCELLED");
                })
                .path("orders.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .allSatisfy(id -> assertThat(id).startsWith(ORDER_ID_PREFIX)))
                .path("orders.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(4));
    }

    @Test
    void providerCallerIsDeniedTheOrderListByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        providerTester.document("query { orders(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("orders").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedTheOrderListByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("query { orders(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("orders").valueIsNull();
    }

    @Test
    void nonAdminCallerIsDeniedTheOrderLookupByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("""
                        query($id: ID!) { order(id: $id) { id } }
                        """)
                .variable("id", orderGlobalId(placedOrderId))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("order").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the keyset scroll — cursor follow-through
    // ------------------------------------------------------------------

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String firstPageEndCursor = tester.document("""
                        query { orders(first: 1, sortBy: { direction: DESC, field: CREATED_AT }) {
                            edges { node { id } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges").entityList(Object.class).hasSize(1)
                .path("orders.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("orders.pageInfo.endCursor").entity(String.class).get();

        String firstPageOrderId = tester.document("""
                        query { orders(first: 1, sortBy: { direction: DESC, field: CREATED_AT }) {
                            edges { node { id } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges[0].node.id").entity(String.class).get();

        tester.document("""
                        query($after: String) { orders(first: 100, after: $after,
                                sortBy: { direction: DESC, field: CREATED_AT }) {
                            edges { node { id } }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .errors().verify()
                .path("orders.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids).doesNotContain(firstPageOrderId));
    }

    @Test
    void statusSortScrollsAndPagesToo() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String firstPageEndCursor = tester.document("""
                        query { orders(first: 1, sortBy: { direction: ASC, field: STATUS }) {
                            edges { node { status } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges").entityList(Object.class).hasSize(1)
                .path("orders.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("orders.pageInfo.endCursor").entity(String.class).get();

        tester.document("""
                        query($after: String) { orders(first: 100, after: $after,
                                sortBy: { direction: ASC, field: STATUS }) {
                            edges { node { status } }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .errors().verify()
                .path("orders.edges[*].node.status").entityList(String.class)
                .satisfies(values -> assertThat(values).isNotEmpty());
    }

    @Test
    void theDeprecatedCreationDateAliasRidesTheSameExecutedScroll() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 100, sortBy: { direction: DESC, field: CREATION_DATE }) {
                            edges { node { id } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges[*].node.id").entityList(String.class)
                .satisfies(values -> assertThat(values).hasSizeGreaterThanOrEqualTo(4));
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 0) {
                            edges { node { id } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges").entityList(Object.class).hasSize(0)
                .path("orders.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("orders.totalCount").valueIsNull();
    }

    // ------------------------------------------------------------------
    // order — the bounded lookup
    // ------------------------------------------------------------------

    @Test
    void orderRoundTripsTheIdTheListItselfEmittedAndRendersTheMappedStatus() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String emittedId = tester.document("""
                        query { orders(first: 1, sortBy: { direction: ASC, field: STATUS }) {
                            edges { node { id status } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("orders.edges[0].node.id").entity(String.class).get();

        assertThat(emittedId).startsWith(ORDER_ID_PREFIX);
        tester.document("""
                        query($id: ID!) { order(id: $id) { id status } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("order.id").entity(String.class).isEqualTo(emittedId)
                .path("order.status").entity(String.class)
                .satisfies(status -> assertThat(status).isIn(
                        "UNCONFIRMED", "UNFULFILLED", "FULFILLED", "CANCELED", "DRAFT",
                        "PARTIALLY_FULFILLED", "PARTIALLY_RETURNED", "RETURNED", "EXPIRED"));
    }

    @Test
    void theCancelledOrderRendersItsUpstreamSpelling() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { order(id: $id) { status } }
                        """)
                .variable("id", orderGlobalId(cancelledOrderId))
                .execute()
                .errors().verify()
                .path("order.status").entity(String.class).isEqualTo("CANCELED");
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { order(id: $id) { id } }
                        """)
                .variable("id", orderGlobalId(UUID.randomUUID()))
                .execute()
                .errors().verify()
                .path("order").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the typed global-ID codec's classified validation errors
    // ------------------------------------------------------------------

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { order(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { order(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void mistypedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { order(id: $id) { id } }
                        """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the anonymous boundary — the reopened pre-login gate contract
    // ------------------------------------------------------------------

    @Test
    void anonymousOrderQueryIsAnsweredByThePreLoginGateEnvelope() {
        // No Authorization header: the security chain now permits the
        // anonymous POST and PreLoginGraphQlGate is the wall — the gate
        // parses the document, finds the protected `orders` root, and
        // answers the classified AUTHN-001 GraphQL error INSIDE the
        // envelope (HTTP 200 + application/json, the error before any
        // dispatch). The same reopened contract GraphQlSecurityIntegration
        // Test pins at the surface level, asserted here for the orders
        // root's own journey: the classified error pair, and NO data —
        // the protected collection never resolves for the anonymous
        // caller.
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ orders(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.errors[0].message")
                .isEqualTo("Authentication is required for this GraphQL operation")
                .jsonPath("$.data").doesNotExist();
    }

    // ------------------------------------------------------------------
    // the classified not-implemented boundaries — never silent no-ops
    // ------------------------------------------------------------------

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 2, filter: { search: "x" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("filter is not implemented")));
    }

    @Test
    void whereFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 2, where: { ids: ["x"] }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("where filter is not implemented")));
    }

    @Test
    void searchAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 2, search: "order") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("search is not implemented")));
    }

    @Test
    void channelAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 2, channel: "channel-1") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("channel filtering is not implemented")));
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("forward-only pagination")));
    }

    @Test
    void numberSortAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { orders(first: 2, sortBy: { direction: ASC, field: NUMBER }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("sort field NUMBER is not implemented")));
    }

    @Test
    void externalReferenceLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($externalReference: String) { order(externalReference: $externalReference) { id } }
                        """)
                .variable("externalReference", "erp-order-1")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("externalReference lookup is not implemented")));
    }

    @Test
    void keylessLookupAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { order { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("order requires its id")));
    }
}
