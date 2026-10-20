package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
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
 * P1 (the canonical Saleor plan §5-P1): end-to-end proof of the Saleor
 * Users-domain read roots — {@code me}, {@code user} and
 * {@code staffUsers} — through the REAL SDL, the REAL DGS wiring, the
 * REAL security chain (the mocked JwtDecoder stamps the caller's subject
 * and roles claim exactly the way the production converter reads them),
 * the REAL identity owner (no SPI mocking: the ADMIN enforcement, the
 * keyset scroll and the bounded projection execute against the real
 * PostgreSQL), and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the canonical plan's P1 gate and the
 * domain matrix's Users row: the role matrix (ADMIN/PROVIDER/CONSUMER),
 * per-operation authorization negatives (a non-admin caller is denied by
 * the owning use case — AUTHZ-001), the read-back journey (lookup by
 * global ID, by email, by the external reference of record), the honest
 * absences (unknown user answers null; a subject without a profile row
 * answers a null {@code me}; a malformed or mistyped global ID answers
 * the classified VAL-001 error), and the keyset pagination contract
 * (forward-only cursors that identify a row's sort key, with the
 * declared-but-unexecuted Saleor arguments answering explicit classified
 * errors instead of silent no-ops).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UserGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p1-users-admin-subject";
    private static final String PROVIDER_SUBJECT = "p1-users-provider-subject";
    private static final String CONSUMER_SUBJECT = "p1-users-consumer-subject";
    private static final String NO_PROFILE_SUBJECT = "p1-users-no-profile-subject";

    private static final UUID ADMIN_ROW_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROVIDER_ROW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CONSUMER_ROW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ROSTER_EXTRA_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

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

    private HttpGraphQlTester adminTester;
    private HttpGraphQlTester providerTester;
    private HttpGraphQlTester consumerTester;

    @BeforeEach
    void setUp() {
        adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        // The seeded roster: an ADMIN (the caller's own row), a PROVIDER, a
        // CONSUMER whose email is deliberately absent (the honest nullable
        // email divergence), and one extra roster row for pagination. The
        // subjects are unique to this class so reruns cannot collide.
        jdbcTemplate.update("DELETE FROM user_role_assignments WHERE user_id IN (?, ?, ?, ?)",
                ADMIN_ROW_ID, PROVIDER_ROW_ID, CONSUMER_ROW_ID, ROSTER_EXTRA_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                ADMIN_ROW_ID, PROVIDER_ROW_ID, CONSUMER_ROW_ID, ROSTER_EXTRA_ID);
        insertUser(ADMIN_ROW_ID, ADMIN_SUBJECT, "p1-admin@example.com", "P1 Admin", "ADMIN");
        insertUser(PROVIDER_ROW_ID, PROVIDER_SUBJECT, "p1-provider@example.com", "P1 Provider", "PROVIDER");
        // No email on purpose: an OIDC account without the email scope.
        insertUser(CONSUMER_ROW_ID, CONSUMER_SUBJECT, null, "P1 Consumer", "CONSUMER");
        insertUser(ROSTER_EXTRA_ID, "p1-users-extra-subject", "p1-extra@example.com", "P1 Extra", "CONSUMER");
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

    /** The typed global ID the codec emits — base64("User:<uuid>"). */
    private static String globalIdOf(UUID nativeId) {
        return java.util.Base64.getEncoder()
                .encodeToString(("User:" + nativeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // me — the caller's own profile
    // ------------------------------------------------------------------

    @Test
    void adminMeReadsOwnProfileWithTypedGlobalIdAndPermissionMatrix() {
        adminTester.document("""
                        query { me { id email isStaff isActive dateJoined userPermissions { code name } } }
                        """)
                .execute()
                .errors().verify()
                .path("me.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(ADMIN_ROW_ID)))
                .path("me.email").entity(String.class).isEqualTo("p1-admin@example.com")
                .path("me.isStaff").entity(Boolean.class).isEqualTo(true)
                .path("me.isActive").entity(Boolean.class).isEqualTo(true)
                .path("me.dateJoined").entity(String.class)
                .satisfies(joined -> assertThat(joined).isNotBlank())
                .path("me.userPermissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_ORDERS", "MANAGE_STAFF", "MANAGE_USERS");
    }

    @Test
    void providerMeAnswersOwnProfileWithTheOwnershipScopedPermission() {
        providerTester.document("""
                        query { me { id email isStaff userPermissions { code } } }
                        """)
                .execute()
                .errors().verify()
                .path("me.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(PROVIDER_ROW_ID)))
                .path("me.isStaff").entity(Boolean.class).isEqualTo(false)
                .path("me.userPermissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_PRODUCTS");
    }

    @Test
    void consumerMeAnswersTheHonestAbsenceOfAnEmailAndNoPermissions() {
        consumerTester.document("""
                        query { me { id email isStaff userPermissions { code } } }
                        """)
                .execute()
                .errors().verify()
                .path("me.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(CONSUMER_ROW_ID)))
                .path("me.email").valueIsNull()
                .path("me.isStaff").entity(Boolean.class).isEqualTo(false)
                .path("me.userPermissions").entityList(Object.class).isEmpty();
    }

    @Test
    void subjectWithoutAProfileRowAnswersNullMeNotAFabricatedProfile() {
        HttpGraphQlTester noProfileTester = testerFor(NO_PROFILE_SUBJECT, "CONSUMER");
        noProfileTester.document("query { me { id email } }")
                .execute()
                .errors().verify()
                .path("me").valueIsNull();
    }

    // ------------------------------------------------------------------
    // user — the ADMIN-enforced lookup (read-back + negatives)
    // ------------------------------------------------------------------

    @Test
    void adminUserReadsBackByTypedGlobalId() {
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id email isStaff isActive userPermissions { code } } }
                        """)
                .variable("id", globalIdOf(PROVIDER_ROW_ID))
                .execute()
                .errors().verify()
                .path("user.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(PROVIDER_ROW_ID)))
                .path("user.email").entity(String.class).isEqualTo("p1-provider@example.com")
                .path("user.isStaff").entity(Boolean.class).isEqualTo(false)
                .path("user.userPermissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_PRODUCTS");
    }

    @Test
    void adminUserReadsBackByEmail() {
        adminTester.document("""
                        query($email: String) { user(email: $email) { id isStaff } }
                        """)
                .variable("email", "p1-extra@example.com")
                .execute()
                .errors().verify()
                .path("user.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(ROSTER_EXTRA_ID)));
    }

    @Test
    void adminUserReadsBackByExternalReferenceWhichIsTheOidcSubject() {
        adminTester.document("""
                        query($ref: String) { user(externalReference: $ref) { id email } }
                        """)
                .variable("ref", CONSUMER_SUBJECT)
                .execute()
                .errors().verify()
                .path("user.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(globalIdOf(CONSUMER_ROW_ID)))
                .path("user.email").valueIsNull();
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
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
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id!")
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage()).contains("Invalid global ID"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("category", "validation"))
                .verify()
                .path("user").valueIsNull();
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", PROVIDER_ROW_ID.toString())
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage()).contains("Invalid global ID"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify()
                .path("user").valueIsNull();
    }

    @Test
    void mistypedGlobalIdForAnotherGraphqlTypeIsRejected() {
        String productTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Product:" + PROVIDER_ROW_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        adminTester.document("""
                        query($id: ID!) { user(id: $id) { id } }
                        """)
                .variable("id", productTaggedId)
                .execute()
                .errors()
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify()
                .path("user").valueIsNull();
    }

    @Test
    void userWithoutArgumentsAnswersTheClassifiedValidationError() {
        adminTester.document("query { user { id } }")
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage())
                        .contains("user requires exactly one of id, email or externalReference"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }

    @Test
    void userWithTwoArgumentsAnswersTheClassifiedValidationError() {
        adminTester.document("""
                        query($id: ID!) { user(id: $id, email: "p1-admin@example.com") { id } }
                        """)
                .variable("id", globalIdOf(ADMIN_ROW_ID))
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage())
                        .contains("user accepts exactly one of id, email or externalReference"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }

    @Test
    void providerCallerIsDeniedByTheOwningUseCaseWithTheAuthzTaxonomy() {
        providerTester.document("""
                        query($id: ID!) { user(id: $id) { id email } }
                        """)
                .variable("id", globalIdOf(ADMIN_ROW_ID))
                .execute()
                .errors()
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "AUTHZ-001"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("category", "authz"))
                .verify()
                .path("user").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedByTheOwningUseCaseWithTheAuthzTaxonomy() {
        consumerTester.document("""
                        query($email: String) { user(email: $email) { id } }
                        """)
                .variable("email", "p1-admin@example.com")
                .execute()
                .errors()
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "AUTHZ-001"))
                .verify()
                .path("user").valueIsNull();
    }

    // ------------------------------------------------------------------
    // staffUsers — the keyset roster with the Saleor connection envelope
    // ------------------------------------------------------------------

    @Test
    void adminStaffUsersAnswersTheSaleorConnectionEnvelopeWithKeysetCursors() {
        var firstPage = adminTester.document("""
                        query { staffUsers(first: 2) {
                            edges { node { id email isStaff } cursor }
                            pageInfo { hasNextPage hasPreviousPage endCursor }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify();

        firstPage.path("staffUsers.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(4));
        firstPage.path("staffUsers.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true);
        firstPage.path("staffUsers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
        firstPage.path("staffUsers.edges").entityList(Object.class).hasSize(2);

        // The default sort is email ascending (Saleor's own User ordering):
        // the seeded roster's first two emails in ascending order.
        firstPage.path("staffUsers.edges[0].node.email").entity(String.class)
                .satisfies(email -> assertThat(email).isLessThan("p1-provider@example.com"));

        String endCursor = firstPage.path("staffUsers.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's sort key: following it continues the
        // same email-ascending scroll without overlap.
        adminTester.document("""
                        query($after: String) { staffUsers(first: 2, after: $after) {
                            edges { node { email } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                        } }
                        """)
                .variable("after", endCursor)
                .execute()
                .errors().verify()
                .path("staffUsers.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true)
                .path("staffUsers.edges[0].node.email").entity(String.class)
                .satisfies(email -> assertThat(email).isGreaterThan("p1-admin@example.com"));
    }

    @Test
    void staffUsersExecutesTheOwningSortFields() {
        adminTester.document("""
                        query { staffUsers(first: 2, sortBy: { field: CREATED_AT, direction: DESC }) {
                            edges { node { email dateJoined } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("staffUsers.edges").entityList(Object.class).hasSize(2);
    }

    @Test
    void staffUsersFilterAnswersTheExplicitNotImplementedError() {
        adminTester.document("""
                        query { staffUsers(first: 2, filter: { search: "p1" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage()).contains("filter is not implemented"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }

    @Test
    void staffUsersBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        adminTester.document("query { staffUsers(last: 2) { totalCount } }")
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage()).contains("forward-only"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }

    @Test
    void staffUsersUnownedSortFieldAnswersTheExplicitNotImplementedError() {
        adminTester.document("""
                        query { staffUsers(first: 2, sortBy: { field: FIRST_NAME, direction: ASC }) { totalCount } }
                        """)
                .execute()
                .errors()
                .expect(err -> assertThat(err.getMessage()).contains("FIRST_NAME"))
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }

    @Test
    void nonPositiveFirstAnswersTheEmptyPage() {
        adminTester.document("""
                        query { staffUsers(first: 0) { edges { node { id } } totalCount pageInfo { hasNextPage } } }
                        """)
                .execute()
                .errors().verify()
                .path("staffUsers.edges").entityList(Object.class).isEmpty()
                .path("staffUsers.totalCount").valueIsNull()
                .path("staffUsers.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void providerCallerIsDeniedTheStaffListByTheOwningUseCase() {
        providerTester.document("query { staffUsers(first: 1) { totalCount } }")
                .execute()
                .errors()
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "AUTHZ-001"))
                .verify()
                .path("staffUsers").valueIsNull();
    }

    @Test
    void cursorFromAnotherSortAnswersTheClassifiedValidationError() {
        // A cursor emitted by a different sort (createdAt) is not valid for
        // the default email sort — the keyset gate rejects the mismatch.
        List<String> createdAtCursorCapture = new java.util.ArrayList<>();
        adminTester.document("""
                        query { staffUsers(first: 1, sortBy: { field: CREATED_AT, direction: ASC }) {
                            edges { cursor }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("staffUsers.edges[0].cursor").entity(String.class)
                .satisfies(createdAtCursorCapture::add);
        assertThat(createdAtCursorCapture).hasSize(1);
        String createdAtCursor = createdAtCursorCapture.get(0);

        adminTester.document("""
                        query($after: String) { staffUsers(first: 1, after: $after) { totalCount } }
                        """)
                .variable("after", createdAtCursor)
                .execute()
                .errors()
                .expect(err -> assertThat(err.getExtensions()).containsEntry("errorCode", "VAL-001"))
                .verify();
    }
}
