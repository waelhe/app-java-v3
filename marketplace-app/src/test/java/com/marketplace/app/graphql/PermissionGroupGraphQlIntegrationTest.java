package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P1 (the canonical Saleor plan §5-P1): end-to-end proof of the Saleor
 * Users-domain permission-group read roots — {@code permissionGroups} and
 * {@code permissionGroup} — through the REAL SDL, the REAL DGS wiring, the
 * REAL security chain (the mocked JwtDecoder stamps the caller's subject
 * and roles claim exactly the way the production converter reads them),
 * the REAL identity owner (no SPI mocking: the ADMIN enforcement and the
 * bounded group projection over the closed role vocabulary execute in the
 * real service), and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the canonical plan's P1 gate and the User
 * read slice's own contract: the role matrix (a non-admin caller is denied
 * by the owning use case — AUTHZ-001), the read-back journey (the list's
 * own emitted global ID round-trips through {@code permissionGroup}), the
 * honest absences (an unknown but well-formed global ID answers null; a
 * malformed or mistyped global ID answers the classified VAL-001 error),
 * the keyset pagination contract (forward-only cursors that identify a
 * row's sort key, with the declared-but-unexecuted Saleor arguments
 * answering explicit classified errors instead of silent no-ops), and the
 * bounded projection's own facts (the closed role vocabulary rendered
 * with its granted permission codes and the honest {@code userCanManage}
 * manage-right fact).</p>
 */
/**
 * The real migrated schema, not Hibernate's ddl-auto approximation (the
 * UserGraphQlIntegrationTest precedent): the context boots against the
 * Flyway V1..V247 schema — the honest standing shape even though the
 * permission-group read itself owns no table (the collection is the
 * closed role vocabulary).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PermissionGroupGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p1-groups-admin-subject";
    private static final String PROVIDER_SUBJECT = "p1-groups-provider-subject";
    private static final String CONSUMER_SUBJECT = "p1-groups-consumer-subject";

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

    @LocalServerPort
    private int port;

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

    /** A well-formed typed global ID for the Group type over any native UUID. */
    private static String groupGlobalId(UUID nativeId) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Group:" + nativeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // permissionGroups — the bounded collection with the Saleor envelope
    // ------------------------------------------------------------------

    @Test
    void adminPermissionGroupsAnswersTheRoleVocabularyInNameOrderWithPermissionsAndUserCanManage() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { permissionGroups(first: 3) {
                            edges { node { id name userCanManage permissions { code name } } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.totalCount").entity(Integer.class).isEqualTo(3)
                .path("permissionGroups.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("permissionGroups.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false)
                .path("permissionGroups.edges[*].node.name").entityList(String.class)
                .containsExactly("Admin", "Consumer", "Provider")
                .path("permissionGroups.edges[*].node.userCanManage").entityList(Boolean.class)
                .containsExactly(true, true, true)
                // The Admin group's granted permission codes — the tested
                // catalog rendering of the ADMIN role's capabilities.
                .path("permissionGroups.edges[0].node.permissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_USERS", "MANAGE_STAFF", "MANAGE_ORDERS")
                .path("permissionGroups.edges[0].node.permissions[*].name").entityList(String.class)
                .containsExactly("Manage users.", "Manage staff.", "Manage orders.")
                // The Consumer group's honest empty capability set.
                .path("permissionGroups.edges[1].node.permissions").entityList(Object.class).hasSize(0)
                // The Provider group's ownership-scoped capability.
                .path("permissionGroups.edges[2].node.permissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_PRODUCTS");
    }

    @Test
    void permissionGroupsFollowsTheForwardKeysetCursorWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { permissionGroups(first: 1) {
                            edges { node { name } cursor }
                            pageInfo { hasNextPage hasPreviousPage endCursor }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[0].node.name").entity(String.class).isEqualTo("Admin")
                .path("permissionGroups.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("permissionGroups.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false)
                .path("permissionGroups.edges").entityList(Object.class).hasSize(1);

        // The cursor identifies a row's own sort key: following it continues
        // the same name-ascending scroll without overlap.
        String firstEndCursor = firstPage.path("permissionGroups.pageInfo.endCursor").entity(String.class).get();

        var secondPage = adminTester.document("""
                        query($after: String) { permissionGroups(first: 1, after: $after) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage endCursor }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[0].node.name").entity(String.class).isEqualTo("Consumer")
                .path("permissionGroups.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
        String secondEndCursor = secondPage.path("permissionGroups.pageInfo.endCursor").entity(String.class).get();

        // The final hop: the scroll exhausts after the last element, with
        // the forward-only honest hasPreviousPage fact on every followed page.
        adminTester.document("""
                        query($after: String) { permissionGroups(first: 5, after: $after) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage }
                        } }
                        """)
                .variable("after", secondEndCursor)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[*].node.name").entityList(String.class)
                .containsExactly("Provider")
                .path("permissionGroups.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("permissionGroups.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void permissionGroupsExecutesTheOwnedNameSortDescending() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { permissionGroups(first: 3, sortBy: { field: NAME, direction: DESC }) {
                            edges { node { name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[*].node.name").entityList(String.class)
                .containsExactly("Provider", "Consumer", "Admin");
    }

    @Test
    void permissionGroupsFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { permissionGroups(first: 2, filter: { search: "admin" }) { totalCount } }
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
    void permissionGroupsBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("query { permissionGroups(last: 2) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("forward-only");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void nonPositiveFirstAnswersTheEmptyPage() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { permissionGroups(first: 0) { edges { node { id } } totalCount pageInfo { hasNextPage } } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges").entityList(Object.class).hasSize(0)
                .path("permissionGroups.totalCount").valueIsNull()
                .path("permissionGroups.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void providerCallerIsDeniedTheGroupListByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        providerTester.document("query { permissionGroups(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("permissionGroups").valueIsNull();
    }

    // ------------------------------------------------------------------
    // permissionGroup — the ADMIN-enforced lookup (read-back + negatives)
    // ------------------------------------------------------------------

    @Test
    void permissionGroupRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        List<String> emittedIdCapture = new ArrayList<>();
        adminTester.document("""
                        query { permissionGroups(first: 3, sortBy: { field: NAME, direction: ASC }) {
                            edges { node { id } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[0].node.id").entity(String.class)
                .satisfies(emittedIdCapture::add);
        assertThat(emittedIdCapture).hasSize(1);
        String emittedId = emittedIdCapture.get(0);

        adminTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id name userCanManage permissions { code } } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("permissionGroup.id").entity(String.class).isEqualTo(emittedId)
                .path("permissionGroup.name").entity(String.class).isEqualTo("Admin")
                .path("permissionGroup.userCanManage").entity(Boolean.class).isEqualTo(true)
                .path("permissionGroup.permissions[*].code").entityList(String.class)
                .containsExactly("MANAGE_USERS", "MANAGE_STAFF", "MANAGE_ORDERS");

        // The wire identity is the deterministic native handle: the same
        // group answers the same global ID on a fresh list read.
        adminTester.document("""
                        query { permissionGroups(first: 3) { edges { node { id name } } } }
                        """)
                .execute()
                .errors().verify()
                .path("permissionGroups.edges[0].node.id").entity(String.class)
                .satisfies(id -> assertThat(id).isEqualTo(emittedId))
                .path("permissionGroups.edges[0].node.name").entity(String.class).isEqualTo("Admin");
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID guessed = UUID.randomUUID();
        adminTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id name } }
                        """)
                .variable("id", groupGlobalId(guessed))
                .execute()
                .errors().verify()
                .path("permissionGroup").valueIsNull();
    }

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id } }
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
                .path("permissionGroup").valueIsNull();
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("Invalid global ID");
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                })
                .path("permissionGroup").valueIsNull();
    }

    @Test
    void mistypedGlobalIdForAnotherGraphqlTypeIsRejected() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String productTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Product:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        adminTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id } }
                        """)
                .variable("id", productTaggedId)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                })
                .path("permissionGroup").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedTheGroupLookupByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("""
                        query($id: ID!) { permissionGroup(id: $id) { id name } }
                        """)
                .variable("id", groupGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("permissionGroup").valueIsNull();
    }
}
