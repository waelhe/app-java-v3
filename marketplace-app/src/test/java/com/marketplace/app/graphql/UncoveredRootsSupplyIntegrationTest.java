package com.marketplace.app.graphql;

import com.marketplace.identity.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The uncovered-roots supply table (TST-02's root fix, W1-B4) — the
 * adversarial scan measured 40 SDL roots (37 on the execution head, after
 * the AUTH-01 wire legs landed) with ZERO coverage on any layer: the
 * staff transport family, the menus family, the pages family, the
 * customerType family, the admin address family, the customer
 * amendments and the account-deletion request. The review's own
 * classification: the owning SERVICES are covered — what was never
 * tested is the WIRE binding (root -> fetcher -> typed global-ID decode
 * -> payload mapping) for these roots.
 *
 * <p><b>The supply design (the plan's own mandate: table-driven, never
 * 74 hand-written tests):</b> one parametrized test rides a supply
 * table; every root contributes a SEED step (the covered sibling roots
 * create the prerequisites as ADMIN) and a CALL executed TWICE — the
 * happy leg (ADMIN, or the owning caller on the self-service roots)
 * with errors empty and the payload's entity present, then the negative
 * leg: the CONSUMER denial with the classified AUTHZ-001 family for
 * the admin-gated roots (the literal pattern 25 files already carry),
 * or the pre-login gate's classified AUTHN-001 for the anonymous
 * caller on the self-service/read roots. Seed and call are SEPARATE
 * steps so the shared JwtDecoder mock is never re-stubbed between the
 * legs — the tester identity is exactly the caller each leg claims.</p>
 *
 * <p>The count guard itself — the test that fails when a NEW root
 * appears with zero touches — lives beside the governance guards
 * ({@code GraphQlRootCoverageGuardTest}): this class supplies the
 * coverage; the guard keeps it honest.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The supply table bursts several wire calls per root on the SAME
        // budgets the production surface declares (graphqlAccountMutation
        // alone carries the staff/customer/customerType/address families);
        // the honest test-window tuning the base test profile itself
        // documents (application-test.yml: 1s refresh windows) — raised
        // HERE, for this class only, so the measured contract is the
        // roots' own wire behavior, never the budget's ceiling.
        "resilience4j.ratelimiter.instances.graphqlAccountMutation.limit-for-period=1000",
        "resilience4j.ratelimiter.instances.graphqlCmsMutation.limit-for-period=1000",
        "resilience4j.ratelimiter.instances.graphqlCmsQuery.limit-for-period=1000",
        "resilience4j.ratelimiter.instances.graphqlUserQuery.limit-for-period=1000",
        "resilience4j.ratelimiter.instances.graphqlAttributeMutation.limit-for-period=1000",
        // The erasure roots (staffDelete/staffBulkDelete/customerDelete) ride
        // the standing pseudonymization whose HMAC channel is env-bound in
        // production; the IT suite's own precedent binds a test key
        // (AccountPseudonymizationIntegrationTest) so the capability is ON
        // for the wire legs.
        "marketplace.security.pseudonymization.subject-hmac-key=b4-supply-pseudonymization-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UncoveredRootsSupplyIntegrationTest {

    private static final String ADMIN_SUBJECT = "b4-supply-admin-subject";
    private static final String CONSUMER_SUBJECT = "b4-supply-consumer-subject";

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
    private UserRepository userRepository;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private UUID adminUserId;
    private UUID consumerUserId;

    /** The rows the happy legs create — FK-safe hard deletes after every leg. */
    private final List<UUID> seededUserIds = new ArrayList<>();
    private final List<UUID> seededAttributeIds = new ArrayList<>();
    private final List<UUID> seededPageTypeIds = new ArrayList<>();
    private final List<UUID> seededPageIds = new ArrayList<>();
    private final List<UUID> seededMenuIds = new ArrayList<>();
    private final List<UUID> seededMenuItemIds = new ArrayList<>();
    private final List<UUID> seededAddressIds = new ArrayList<>();
    private final List<UUID> seededRecipientIds = new ArrayList<>();
    private final List<UUID> seededCustomerTypeIds = new ArrayList<>();

    /** FK-safe user purge: tokens, auth rows, addresses, then the users row. */
    private void purgeUser(UUID userId) {
        List<String> subjects = jdbcTemplate.queryForList(
                "SELECT subject FROM users WHERE id = ?", String.class, userId);
        for (String subject : subjects) {
            jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", subject);
            jdbcTemplate.update("DELETE FROM auth_action_tokens WHERE username = ?", subject);
            jdbcTemplate.update("DELETE FROM auth_authorities WHERE username = ?", subject);
            jdbcTemplate.update("DELETE FROM auth_users WHERE username = ?", subject);
        }
        jdbcTemplate.update("DELETE FROM user_addresses WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @BeforeEach
    void seedPrincipals() {
        if (adminUserId != null) {
            return;
        }
        // The principals' WHOLE footprint (the AUTH-01 wire IT's own
        // pattern): the login rows through the official UserDetailsManager
        // AND the identity rows — the accountRequestDeletion leg issues an
        // auth action token whose V112 FK demands the auth_users row.
        adminUserId = seedPrincipal(ADMIN_SUBJECT, "ADMIN", "B4 Supply Admin");
        consumerUserId = seedPrincipal(CONSUMER_SUBJECT, "CONSUMER", "B4 Supply Consumer");
    }

    private UUID seedPrincipal(String subject, String role, String displayName) {
        if (userDetailsManager.userExists(subject)) {
            userDetailsManager.deleteUser(subject);
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(subject)
                .password("{noop}b4-supply-password")
                .roles(role)
                .build());
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                """, id, subject, subject + "@example.com", displayName, role);
        seededUserIds.add(id);
        return id;
    }

    @AfterEach
    void removeSeededRows() {
        for (UUID id : new ArrayList<>(seededRecipientIds)) {
            jdbcTemplate.update("DELETE FROM staff_notification_recipients WHERE id = ?", id);
        }
        seededRecipientIds.clear();
        for (UUID id : new ArrayList<>(seededMenuItemIds)) {
            jdbcTemplate.update("DELETE FROM cms_menu_items WHERE id = ?", id);
        }
        seededMenuItemIds.clear();
        for (UUID id : new ArrayList<>(seededMenuIds)) {
            jdbcTemplate.update("DELETE FROM cms_menus WHERE id = ?", id);
        }
        seededMenuIds.clear();
        for (UUID id : new ArrayList<>(seededPageIds)) {
            jdbcTemplate.update("DELETE FROM cms_pages WHERE id = ?", id);
        }
        seededPageIds.clear();
        for (UUID id : new ArrayList<>(seededPageTypeIds)) {
            jdbcTemplate.update("DELETE FROM cms_page_type_attributes WHERE page_type_id = ?", id);
            jdbcTemplate.update("DELETE FROM cms_page_types WHERE id = ?", id);
        }
        seededPageTypeIds.clear();
        for (UUID id : new ArrayList<>(seededAddressIds)) {
            jdbcTemplate.update("DELETE FROM user_addresses WHERE id = ?", id);
        }
        seededAddressIds.clear();
        for (UUID id : new ArrayList<>(seededCustomerTypeIds)) {
            jdbcTemplate.update("DELETE FROM customer_type_attributes WHERE customer_type_id = ?", id);
            jdbcTemplate.update("DELETE FROM customer_types WHERE id = ?", id);
        }
        seededCustomerTypeIds.clear();
        for (UUID id : new ArrayList<>(seededAttributeIds)) {
            jdbcTemplate.update("DELETE FROM catalog_attribute_definitions WHERE id = ?", id);
        }
        seededAttributeIds.clear();
        for (UUID id : new ArrayList<>(seededUserIds)) {
            purgeUser(id);
        }
        seededUserIds.clear();
        adminUserId = null;
        consumerUserId = null;
    }

    // ------------------------------------------------------------------
    // the wire helpers (the established supply pattern)
    // ------------------------------------------------------------------

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

    /** The anonymous tester — no bearer header at all: the pre-login gate answers. */
    private HttpGraphQlTester anonymousTester() {
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql"))
                .build();
    }

    private HttpGraphQlTester admin() {
        return testerFor(ADMIN_SUBJECT, "ADMIN");
    }

    private HttpGraphQlTester consumer() {
        return testerFor(CONSUMER_SUBJECT, "CONSUMER");
    }

    private static String globalId(String type, UUID id) {
        return Base64.getEncoder()
                .encodeToString((type + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Extracts the native UUID from a typed global ID the wire returned. */
    private static UUID nativeId(String typedGlobalId) {
        String decoded = new String(Base64.getDecoder().decode(typedGlobalId), StandardCharsets.UTF_8);
        return UUID.fromString(decoded.substring(decoded.indexOf(':') + 1));
    }

    // ------------------------------------------------------------------
    // the seeders — every one rides a COVERED sibling root over the wire
    // (always as ADMIN: seeding never re-stubs the caller's own identity)
    // ------------------------------------------------------------------

    private static final Map<String, Object> ADDRESS_INPUT = Map.of(
            "firstName", "B4",
            "lastName", "Supply",
            "streetAddress1", "1 Coverage Row",
            "city", "Riyadh",
            "postalCode", "12345",
            "country", "SA");

    /** ctx keys the supplies share with their calls. */
    private static final String ID = "id";
    private static final String SECOND_ID = "secondId";

    private UUID seedStaffUser() {
        String email = "b4-s" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String id = admin().document("""
                        mutation($input: StaffCreateInput!) {
                          staffCreate(input: $input) { user { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "firstName", "B4",
                        "lastName", "Staff",
                        "email", email,
                        "isActive", true))
                .execute().errors().verify()
                .path("staffCreate.user.id").entity(String.class).get();
        seededUserIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedCustomer() {
        String email = "b4-c" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String id = admin().document("""
                        mutation($input: UserCreateInput!) {
                          customerCreate(input: $input) { user { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "firstName", "B4",
                        "lastName", "Customer",
                        "email", email,
                        "isActive", true))
                .execute().errors().verify()
                .path("customerCreate.user.id").entity(String.class).get();
        seededUserIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedCustomerType() {
        String id = admin().document("""
                        mutation($input: CustomerTypeCreateInput!) {
                          customerTypeCreate(input: $input) { customerType { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "name", "B4 Type " + UUID.randomUUID().toString().substring(0, 8),
                        "slug", "b4-type-" + UUID.randomUUID().toString().substring(0, 8)))
                .execute().errors().verify()
                .path("customerTypeCreate.customerType.id").entity(String.class).get();
        seededCustomerTypeIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedAttribute() {
        String id = admin().document("""
                        mutation($input: AttributeCreateInput!) {
                          attributeCreate(input: $input) { attribute { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "name", "B4 Attribute " + UUID.randomUUID().toString().substring(0, 8),
                        "slug", "b4-attr-" + UUID.randomUUID().toString().substring(0, 8),
                        "inputType", "DROPDOWN"))
                .execute().errors().verify()
                .path("attributeCreate.attribute.id").entity(String.class).get();
        seededAttributeIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedPageType() {
        String id = admin().document("""
                        mutation($input: PageTypeCreateInput!) {
                          pageTypeCreate(input: $input) { pageType { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "name", "B4 Page Type " + UUID.randomUUID().toString().substring(0, 8),
                        "slug", "b4-ptype-" + UUID.randomUUID().toString().substring(0, 8)))
                .execute().errors().verify()
                .path("pageTypeCreate.pageType.id").entity(String.class).get();
        seededPageTypeIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedPage(UUID pageTypeId) {
        String id = admin().document("""
                        mutation($input: PageCreateInput!) {
                          pageCreate(input: $input) { page { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "slug", "b4-page-" + UUID.randomUUID().toString().substring(0, 8),
                        "title", "B4 Page",
                        "content", "{\"blocks\":[]}",
                        "isPublished", false,
                        "pageType", globalId("PageType", pageTypeId)))
                .execute().errors().verify()
                .path("pageCreate.page.id").entity(String.class).get();
        seededPageIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedMenu() {
        String id = admin().document("""
                        mutation($input: MenuCreateInput!) {
                          menuCreate(input: $input) { menu { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of("name", "B4 Menu " + UUID.randomUUID().toString().substring(0, 8)))
                .execute().errors().verify()
                .path("menuCreate.menu.id").entity(String.class).get();
        seededMenuIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedMenuItem(UUID menuId) {
        String id = admin().document("""
                        mutation($input: MenuItemCreateInput!) {
                          menuItemCreate(input: $input) { menuItem { id } errors { field code } }
                        }
                        """)
                .variable("input", Map.of(
                        "name", "B4 Item " + UUID.randomUUID().toString().substring(0, 8),
                        "menu", globalId("Menu", menuId),
                        "url", "https://example.com"))
                .execute().errors().verify()
                .path("menuItemCreate.menuItem.id").entity(String.class).get();
        seededMenuItemIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedAddress(UUID userId) {
        String id = admin().document("""
                        mutation($input: AddressInput!, $userId: ID!) {
                          addressCreate(input: $input, userId: $userId) {
                            address { id } errors { field code }
                          }
                        }
                        """)
                .variable("input", ADDRESS_INPUT)
                .variable("userId", globalId("User", userId))
                .execute().errors().verify()
                .path("addressCreate.address.id").entity(String.class).get();
        seededAddressIds.add(nativeId(id));
        return nativeId(id);
    }

    private UUID seedRecipient() {
        String id = admin().document("""
                        mutation($input: StaffNotificationRecipientInput!) {
                          staffNotificationRecipientCreate(input: $input) {
                            staffNotificationRecipient { id } errors { field code }
                          }
                        }
                        """)
                .variable("input", Map.of(
                        "email", "b4-r" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                        "active", true))
                .execute().errors().verify()
                .path("staffNotificationRecipientCreate.staffNotificationRecipient.id")
                .entity(String.class).get();
        seededRecipientIds.add(nativeId(id));
        return nativeId(id);
    }

    // ------------------------------------------------------------------
    // the supply table
    // ------------------------------------------------------------------

    /** What one root's supply carries: SEED (admin) and CALL (twice). */
    record Supply(
            String root,
            /** Seeds prerequisites as ADMIN; returns the ctx the call reads. */
            Supplier<Map<String, Object>> seed,
            /** The root's own call — executed for the happy leg and again for the negative leg. */
            BiFunction<HttpGraphQlTester, Map<String, Object>, GraphQlTester.Response> call,
            /** The happy assertion path: the entity id (or count) the payload carries. */
            String happyEntityPath,
            /** The negative expectation. */
            Negative negative,
            /**
             * The 2026-11-09 #807 round-2 review (Minor · Functional
             * Correctness): the happy leg's OWN result hook — a root whose
             * happy call CREATES an entity registers the created id for the
             * @AfterEach cleanup. The negative leg never reaches this hook
             * (the AUTHZ/AUTHN gate refuses before any write), so a
             * registering hook runs exactly once per creating supply.
             */
            BiConsumer<GraphQlTester.Response, Map<String, Object>> afterHappy) {

        /** The no-hook convenience — every non-creating supply's own shape. */
        Supply(String root, Supplier<Map<String, Object>> seed,
               BiFunction<HttpGraphQlTester, Map<String, Object>, GraphQlTester.Response> call,
               String happyEntityPath, Negative negative) {
            this(root, seed, call, happyEntityPath, negative, (response, ctx) -> { });
        }
    }

    enum Negative {
        /** The admin-gated root: an authenticated CONSUMER is denied with the classified AUTHZ-001 family. */
        CONSUMER_DENIED,
        /** The read/self-service root: an anonymous caller is answered by the pre-login gate's AUTHN-001. */
        ANONYMOUS_GATE
    }

    private static Map<String, Object> ctx(UUID id) {
        Map<String, Object> map = new HashMap<>();
        map.put(ID, id);
        return map;
    }

    List<Supply> supplies() {
        List<Supply> table = new ArrayList<>();

        // ---- staff family ------------------------------------------------
        // The happy caller for every staff root is ADMIN; the seeded staff
        // rows are ADMINs themselves. staffDelete/staffBulkDelete seed a
        // SECOND staff first — the standing erasure's own last-active-ADMIN
        // guard (L23) refuses deleting the last one standing.
        // The 2026-11-09 #807 round-2 review (Minor · Functional
        // Correctness): the CREATING supplies register their happy leg's
        // created ids — the staff account (an ACTIVE ADMIN) and the
        // customer type (an empty registry's first type answers the
        // DEFAULT row, and customerTypeDelete's own seeded type then
        // rides on that leaked state) previously outlived the test, so
        // a later supply's verdict depended on THIS supply having run
        // first (the order-dependent state leak the round described).
        table.add(new Supply("staffCreate",
                () -> ctx(null),
                (t, c) -> t.document("""
                                mutation($input: StaffCreateInput!) {
                                  staffCreate(input: $input) { user { id } errors { field code } }
                                }
                                """)
                        .variable("input", Map.of(
                                "firstName", "B4", "lastName", "Happy", "isActive", true,
                                "email", "b4-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com"))
                        .execute(),
                "staffCreate.user.id", Negative.CONSUMER_DENIED,
                (happy, c) -> seededUserIds.add(nativeId(
                        happy.path("staffCreate.user.id").entity(String.class).get()))));
        table.add(new Supply("staffUpdate",
                () -> ctx(seedStaffUser()),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: StaffUpdateInput!) {
                                  staffUpdate(id: $id, input: $input) { user { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("User", (UUID) c.get(ID)))
                        .variable("input", Map.of("lastName", "Renamed"))
                        .execute(),
                "staffUpdate.user.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("staffDelete",
                () -> {
                    Map<String, Object> c = ctx(seedStaffUser());
                    c.put(SECOND_ID, seedStaffUser());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  staffDelete(id: $id) { user { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("User", (UUID) c.get(ID)))
                        .execute(),
                "staffDelete.errors", Negative.CONSUMER_DENIED));
        table.add(new Supply("staffBulkDelete",
                () -> {
                    Map<String, Object> c = ctx(seedStaffUser());
                    c.put(SECOND_ID, seedStaffUser());
                    c.put("thirdId", seedStaffUser());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($ids: [ID!]!) {
                                  staffBulkDelete(ids: $ids) { count errors { field code } }
                                }
                                """)
                        .variable("ids", List.of(
                                globalId("User", (UUID) c.get(ID)),
                                globalId("User", (UUID) c.get(SECOND_ID))))
                        .execute(),
                "staffBulkDelete.count", Negative.CONSUMER_DENIED));
        table.add(new Supply("staffNotificationRecipientUpdate",
                () -> ctx(seedRecipient()),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: StaffNotificationRecipientInput!) {
                                  staffNotificationRecipientUpdate(id: $id, input: $input) {
                                    staffNotificationRecipient { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("StaffNotificationRecipient", (UUID) c.get(ID)))
                        .variable("input", Map.of("active", false))
                        .execute(),
                "staffNotificationRecipientUpdate.staffNotificationRecipient.id",
                Negative.CONSUMER_DENIED));
        table.add(new Supply("staffNotificationRecipientDelete",
                () -> ctx(seedRecipient()),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  staffNotificationRecipientDelete(id: $id) {
                                    staffNotificationRecipient { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("StaffNotificationRecipient", (UUID) c.get(ID)))
                        .execute(),
                "staffNotificationRecipientDelete.staffNotificationRecipient.id",
                Negative.CONSUMER_DENIED));

        // ---- customer amendments ----------------------------------------
        table.add(new Supply("customerUpdate",
                () -> ctx(seedCustomer()),
                (t, c) -> t.document("""
                                mutation($id: ID, $input: CustomerInput!) {
                                  customerUpdate(id: $id, input: $input) { user { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("User", (UUID) c.get(ID)))
                        .variable("input", Map.of("lastName", "Renamed"))
                        .execute(),
                "customerUpdate.user.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("customerDelete",
                () -> ctx(seedCustomer()),
                (t, c) -> t.document("""
                                mutation($id: ID) {
                                  customerDelete(id: $id) { user { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("User", (UUID) c.get(ID)))
                        .execute(),
                "customerDelete.errors", Negative.CONSUMER_DENIED));

        // ---- customerType family ----------------------------------------
        table.add(new Supply("customerTypeCreate",
                () -> ctx(null),
                (t, c) -> t.document("""
                                mutation($input: CustomerTypeCreateInput!) {
                                  customerTypeCreate(input: $input) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("input", Map.of(
                                "name", "B4 Happy " + UUID.randomUUID().toString().substring(0, 8),
                                "slug", "b4-happy-" + UUID.randomUUID().toString().substring(0, 8)))
                        .execute(),
                "customerTypeCreate.customerType.id", Negative.CONSUMER_DENIED,
                (happy, c) -> seededCustomerTypeIds.add(nativeId(
                        happy.path("customerTypeCreate.customerType.id").entity(String.class).get()))));
        table.add(new Supply("customerTypeUpdate",
                () -> ctx(seedCustomerType()),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: CustomerTypeUpdateInput!) {
                                  customerTypeUpdate(id: $id, input: $input) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("CustomerType", (UUID) c.get(ID)))
                        .variable("input", Map.of("name", "B4 Renamed"))
                        .execute(),
                "customerTypeUpdate.customerType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("customerTypeDelete",
                () -> ctx(seedCustomerType()),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  customerTypeDelete(id: $id) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("CustomerType", (UUID) c.get(ID)))
                        .execute(),
                "customerTypeDelete.customerType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("customerTypeAssignAttributes",
                () -> {
                    Map<String, Object> c = ctx(seedCustomerType());
                    c.put(SECOND_ID, seedAttribute());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($attributeIds: [ID!]!, $customerTypeId: ID!) {
                                  customerTypeAssignAttributes(attributeIds: $attributeIds, customerTypeId: $customerTypeId) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("attributeIds", List.of(globalId("Attribute", (UUID) c.get(SECOND_ID))))
                        .variable("customerTypeId", globalId("CustomerType", (UUID) c.get(ID)))
                        .execute(),
                "customerTypeAssignAttributes.customerType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("customerTypeUnassignAttributes",
                () -> {
                    Map<String, Object> c = ctx(seedCustomerType());
                    UUID attributeId = seedAttribute();
                    admin().document("""
                                    mutation($attributeIds: [ID!]!, $customerTypeId: ID!) {
                                      customerTypeAssignAttributes(attributeIds: $attributeIds, customerTypeId: $customerTypeId) {
                                        errors { field code }
                                      }
                                    }
                                    """)
                            .variable("attributeIds", List.of(globalId("Attribute", attributeId)))
                            .variable("customerTypeId", globalId("CustomerType", (UUID) c.get(ID)))
                            .execute().errors().verify();
                    c.put(SECOND_ID, attributeId);
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($attributeIds: [ID!]!, $customerTypeId: ID!) {
                                  customerTypeUnassignAttributes(attributeIds: $attributeIds, customerTypeId: $customerTypeId) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("attributeIds", List.of(globalId("Attribute", (UUID) c.get(SECOND_ID))))
                        .variable("customerTypeId", globalId("CustomerType", (UUID) c.get(ID)))
                        .execute(),
                "customerTypeUnassignAttributes.customerType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("customerTypeReorderAttributes",
                () -> {
                    Map<String, Object> c = ctx(seedCustomerType());
                    UUID first = seedAttribute();
                    UUID second = seedAttribute();
                    admin().document("""
                                    mutation($attributeIds: [ID!]!, $customerTypeId: ID!) {
                                      customerTypeAssignAttributes(attributeIds: $attributeIds, customerTypeId: $customerTypeId) {
                                        errors { field code }
                                      }
                                    }
                                    """)
                            .variable("attributeIds", List.of(
                                    globalId("Attribute", first), globalId("Attribute", second)))
                            .variable("customerTypeId", globalId("CustomerType", (UUID) c.get(ID)))
                            .execute().errors().verify();
                    c.put(SECOND_ID, second);
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($customerTypeId: ID!, $moves: [ReorderInput!]!) {
                                  customerTypeReorderAttributes(customerTypeId: $customerTypeId, moves: $moves) {
                                    customerType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("customerTypeId", globalId("CustomerType", (UUID) c.get(ID)))
                        .variable("moves", List.of(Map.of(
                                "id", globalId("Attribute", (UUID) c.get(SECOND_ID)),
                                "sortOrder", 0)))
                        .execute(),
                "customerTypeReorderAttributes.customerType.id", Negative.CONSUMER_DENIED));

        // ---- pages family -------------------------------------------------
        table.add(new Supply("pageUpdate",
                () -> ctx(seedPage(seedPageType())),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: PageInput!) {
                                  pageUpdate(id: $id, input: $input) { page { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("Page", (UUID) c.get(ID)))
                        .variable("input", Map.of("title", "B4 Renamed"))
                        .execute(),
                "pageUpdate.page.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageDelete",
                () -> ctx(seedPage(seedPageType())),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  pageDelete(id: $id) { page { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("Page", (UUID) c.get(ID)))
                        .execute(),
                "pageDelete.page.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageBulkDelete",
                () -> {
                    UUID pageTypeId = seedPageType();
                    Map<String, Object> c = ctx(seedPage(pageTypeId));
                    c.put(SECOND_ID, seedPage(pageTypeId));
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($ids: [ID!]!) {
                                  pageBulkDelete(ids: $ids) { count errors { field code } }
                                }
                                """)
                        .variable("ids", List.of(
                                globalId("Page", (UUID) c.get(ID)),
                                globalId("Page", (UUID) c.get(SECOND_ID))))
                        .execute(),
                "pageBulkDelete.count", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageTypeDelete",
                () -> ctx(seedPageType()),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  pageTypeDelete(id: $id) { pageType { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("PageType", (UUID) c.get(ID)))
                        .execute(),
                "pageTypeDelete.pageType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageTypeBulkDelete",
                () -> {
                    Map<String, Object> c = ctx(seedPageType());
                    c.put(SECOND_ID, seedPageType());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($ids: [ID!]!) {
                                  pageTypeBulkDelete(ids: $ids) { count errors { field code } }
                                }
                                """)
                        .variable("ids", List.of(
                                globalId("PageType", (UUID) c.get(ID)),
                                globalId("PageType", (UUID) c.get(SECOND_ID))))
                        .execute(),
                "pageTypeBulkDelete.count", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageAttributeAssign",
                () -> {
                    Map<String, Object> c = ctx(seedPageType());
                    c.put(SECOND_ID, seedAttribute());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($attributeIds: [ID!]!, $pageTypeId: ID!) {
                                  pageAttributeAssign(attributeIds: $attributeIds, pageTypeId: $pageTypeId) {
                                    pageType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("attributeIds", List.of(globalId("Attribute", (UUID) c.get(SECOND_ID))))
                        .variable("pageTypeId", globalId("PageType", (UUID) c.get(ID)))
                        .execute(),
                "pageAttributeAssign.pageType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageAttributeUnassign",
                () -> {
                    Map<String, Object> c = ctx(seedPageType());
                    UUID attributeId = seedAttribute();
                    admin().document("""
                                    mutation($attributeIds: [ID!]!, $pageTypeId: ID!) {
                                      pageAttributeAssign(attributeIds: $attributeIds, pageTypeId: $pageTypeId) {
                                        errors { field code }
                                      }
                                    }
                                    """)
                            .variable("attributeIds", List.of(globalId("Attribute", attributeId)))
                            .variable("pageTypeId", globalId("PageType", (UUID) c.get(ID)))
                            .execute().errors().verify();
                    c.put(SECOND_ID, attributeId);
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($attributeIds: [ID!]!, $pageTypeId: ID!) {
                                  pageAttributeUnassign(attributeIds: $attributeIds, pageTypeId: $pageTypeId) {
                                    pageType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("attributeIds", List.of(globalId("Attribute", (UUID) c.get(SECOND_ID))))
                        .variable("pageTypeId", globalId("PageType", (UUID) c.get(ID)))
                        .execute(),
                "pageAttributeUnassign.pageType.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageTypeReorderAttributes",
                () -> {
                    Map<String, Object> c = ctx(seedPageType());
                    UUID first = seedAttribute();
                    UUID second = seedAttribute();
                    admin().document("""
                                    mutation($attributeIds: [ID!]!, $pageTypeId: ID!) {
                                      pageAttributeAssign(attributeIds: $attributeIds, pageTypeId: $pageTypeId) {
                                        errors { field code }
                                      }
                                    }
                                    """)
                            .variable("attributeIds", List.of(
                                    globalId("Attribute", first), globalId("Attribute", second)))
                            .variable("pageTypeId", globalId("PageType", (UUID) c.get(ID)))
                            .execute().errors().verify();
                    c.put(SECOND_ID, second);
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($moves: [ReorderInput!]!, $pageTypeId: ID!) {
                                  pageTypeReorderAttributes(moves: $moves, pageTypeId: $pageTypeId) {
                                    pageType { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("moves", List.of(Map.of(
                                "id", globalId("Attribute", (UUID) c.get(SECOND_ID)),
                                "sortOrder", 0)))
                        .variable("pageTypeId", globalId("PageType", (UUID) c.get(ID)))
                        .execute(),
                "pageTypeReorderAttributes.pageType.id", Negative.CONSUMER_DENIED));

        // ---- menus family --------------------------------------------------
        table.add(new Supply("menuUpdate",
                () -> ctx(seedMenu()),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: MenuInput!) {
                                  menuUpdate(id: $id, input: $input) { menu { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("Menu", (UUID) c.get(ID)))
                        .variable("input", Map.of("name", "B4 Renamed"))
                        .execute(),
                "menuUpdate.menu.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuDelete",
                () -> ctx(seedMenu()),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  menuDelete(id: $id) { menu { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("Menu", (UUID) c.get(ID)))
                        .execute(),
                "menuDelete.menu.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuBulkDelete",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    c.put(SECOND_ID, seedMenu());
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($ids: [ID!]!) {
                                  menuBulkDelete(ids: $ids) { count errors { field code } }
                                }
                                """)
                        .variable("ids", List.of(
                                globalId("Menu", (UUID) c.get(ID)),
                                globalId("Menu", (UUID) c.get(SECOND_ID))))
                        .execute(),
                "menuBulkDelete.count", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuItemUpdate",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    c.put(SECOND_ID, seedMenuItem((UUID) c.get(ID)));
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: MenuItemInput!) {
                                  menuItemUpdate(id: $id, input: $input) {
                                    menuItem { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("MenuItem", (UUID) c.get(SECOND_ID)))
                        .variable("input", Map.of("name", "B4 Renamed"))
                        .execute(),
                "menuItemUpdate.menuItem.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuItemDelete",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    c.put(SECOND_ID, seedMenuItem((UUID) c.get(ID)));
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  menuItemDelete(id: $id) { menuItem { id } errors { field code } }
                                }
                                """)
                        .variable("id", globalId("MenuItem", (UUID) c.get(SECOND_ID)))
                        .execute(),
                "menuItemDelete.menuItem.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuItemBulkDelete",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    c.put(SECOND_ID, seedMenuItem((UUID) c.get(ID)));
                    c.put("thirdId", seedMenuItem((UUID) c.get(ID)));
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($ids: [ID!]!) {
                                  menuItemBulkDelete(ids: $ids) { count errors { field code } }
                                }
                                """)
                        .variable("ids", List.of(
                                globalId("MenuItem", (UUID) c.get(SECOND_ID)),
                                globalId("MenuItem", (UUID) c.get("thirdId"))))
                        .execute(),
                "menuItemBulkDelete.count", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuItemMove",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    c.put(SECOND_ID, seedMenuItem((UUID) c.get(ID)));
                    return c;
                },
                (t, c) -> t.document("""
                                mutation($menu: ID!, $moves: [MenuItemMoveInput!]!) {
                                  menuItemMove(menu: $menu, moves: $moves) {
                                    menu { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("menu", globalId("Menu", (UUID) c.get(ID)))
                        .variable("moves", List.of(Map.of(
                                "itemId", globalId("MenuItem", (UUID) c.get(SECOND_ID)),
                                "sortOrder", 0)))
                        .execute(),
                "menuItemMove.menu.id", Negative.CONSUMER_DENIED));

        // ---- the read roots -------------------------------------------------
        table.add(new Supply("menus",
                () -> ctx(seedMenu()),
                (t, c) -> t.document("""
                                query { menus(first: 10) { edges { node { id } } } }
                                """).execute(),
                "menus.edges", Negative.CONSUMER_DENIED));
        table.add(new Supply("menuItems",
                () -> {
                    Map<String, Object> c = ctx(seedMenu());
                    seedMenuItem((UUID) c.get(ID));
                    return c;
                },
                (t, c) -> t.document("""
                                query { menuItems(first: 10) { edges { node { id } } } }
                                """).execute(),
                "menuItems.edges", Negative.CONSUMER_DENIED));
        table.add(new Supply("pageTypes",
                () -> ctx(seedPageType()),
                (t, c) -> t.document("""
                                query { pageTypes(first: 10) { edges { node { id } } } }
                                """).execute(),
                "pageTypes.edges", Negative.CONSUMER_DENIED));

        // ---- the admin address family ---------------------------------------
        table.add(new Supply("addressUpdate",
                () -> ctx(seedAddress(consumerUserId)),
                (t, c) -> t.document("""
                                mutation($id: ID!, $input: AddressInput!) {
                                  addressUpdate(id: $id, input: $input) {
                                    address { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("Address", (UUID) c.get(ID)))
                        .variable("input", ADDRESS_INPUT)
                        .execute(),
                "addressUpdate.address.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("addressDelete",
                () -> ctx(seedAddress(consumerUserId)),
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  addressDelete(id: $id) {
                                    address { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("Address", (UUID) c.get(ID)))
                        .execute(),
                "addressDelete.address.id", Negative.CONSUMER_DENIED));
        table.add(new Supply("addressSetDefault",
                () -> ctx(seedAddress(consumerUserId)),
                (t, c) -> t.document("""
                                mutation($addressId: ID!, $type: AddressTypeEnum!, $userId: ID!) {
                                  addressSetDefault(addressId: $addressId, type: $type, userId: $userId) {
                                    errors { field code }
                                  }
                                }
                                """)
                        .variable("addressId", globalId("Address", (UUID) c.get(ID)))
                        .variable("type", "SHIPPING")
                        .variable("userId", globalId("User", consumerUserId))
                        .execute(),
                "addressSetDefault.errors", Negative.CONSUMER_DENIED));
        table.add(new Supply("accountAddressDelete",
                () -> {
                    String id = consumer().document("""
                            mutation($input: AddressInput!, $type: AddressTypeEnum) {
                              accountAddressCreate(input: $input, type: $type) {
                                address { id } errors { field code }
                              }
                            }
                            """)
                            .variable("input", ADDRESS_INPUT)
                            .variable("type", "SHIPPING")
                            .execute().errors().verify()
                            .path("accountAddressCreate.address.id").entity(String.class).get();
                    seededAddressIds.add(nativeId(id));
                    return ctx(nativeId(id));
                },
                (t, c) -> t.document("""
                                mutation($id: ID!) {
                                  accountAddressDelete(id: $id) {
                                    address { id } errors { field code }
                                  }
                                }
                                """)
                        .variable("id", globalId("Address", (UUID) c.get(ID)))
                        .execute(),
                "accountAddressDelete.address.id", Negative.ANONYMOUS_GATE));

        // ---- the self-service deletion request -------------------------------
        table.add(new Supply("accountRequestDeletion",
                () -> ctx(null),
                (t, c) -> t.document("""
                                mutation { accountRequestDeletion(redirectUrl: "https://example.com") {
                                  errors { field code }
                                } }
                                """).execute(),
                "accountRequestDeletion.errors", Negative.ANONYMOUS_GATE));

        return table;
    }

    // ------------------------------------------------------------------
    // the legs
    // ------------------------------------------------------------------

    /**
     * One root, two legs: the seeded happy call over the real stack
     * (errors empty, the payload's entity present) and the negative
     * call — the classified denial family the root's own authorization
     * model answers with. The seed runs ONCE, as ADMIN, before either
     * leg: the caller identity of each leg is exactly what its tester
     * claims (the shared decoder mock is never re-stubbed mid-leg).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("supplies")
    void theRootExecutesItsHappyLegAndItsClassifiedNegativeLeg(Supply supply) {
        Map<String, Object> context = supply.seed().get();

        // The happy leg: the real wire call, the real owner, the real
        // payload — no mocked seams anywhere in the stack. The
        // self-service roots (accountAddressDelete,
        // accountRequestDeletion) execute as their owning CONSUMER.
        HttpGraphQlTester happyCaller = supply.negative() == Negative.CONSUMER_DENIED
                ? admin() : consumer();
        GraphQlTester.Response happy = supply.call().apply(happyCaller, context);
        happy.errors().verify();
        // The creating supply's own registration hook — the ids the happy
        // leg just created join the @AfterEach cleanup lists (the
        // 2026-11-09 #807 round-2 review: no leaked staff account, no
        // leaked default customer type).
        supply.afterHappy().accept(happy, context);
        if (supply.happyEntityPath().endsWith(".edges")) {
            happy.path(supply.happyEntityPath()).entityList(Object.class)
                    .satisfies(edges -> assertThat(edges).isNotEmpty());
        } else {
            happy.path(supply.happyEntityPath()).entity(Object.class)
                    .satisfies(entity -> assertThat(entity).isNotNull());
        }

        // The negative leg.
        switch (supply.negative()) {
            // An authenticated CONSUMER on the admin-gated root: the
            // classified AUTHZ-001 GraphQL error family (the literal
            // pattern the 25 covered files carry).
            case CONSUMER_DENIED -> supply.call().apply(consumer(), context)
                    .errors()
                    .satisfy(errors -> {
                        assertThat(errors).isNotEmpty();
                        var error = errors.getFirst();
                        assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                        assertThat(error.getExtensions()).containsEntry("category", "authz");
                    });
            // An anonymous caller on a protected root: the pre-login
            // gate's classified AUTHN-001 — the operation never dispatches.
            case ANONYMOUS_GATE -> supply.call().apply(anonymousTester(), context)
                    .errors()
                    .satisfy(errors -> {
                        assertThat(errors).isNotEmpty();
                        var error = errors.getFirst();
                        assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHN-001");
                        assertThat(error.getExtensions()).containsEntry("category", "authz");
                    });
        }
    }
}
