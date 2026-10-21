package com.marketplace.app.graphql;

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
import test.config.IntegrationContainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * real-stack proof of this packet's write journeys — the address
 * lifecycle over the REAL Flyway schema (V248), the REAL DGS wiring, the
 * REAL security chain (the mocked JwtDecoder stamps the caller exactly
 * as the production converter reads it) and the REAL identity/console
 * owners (no SPI mocking). The customers keyset fix's own class carries
 * the read-side proof; this class carries the mutation side.
 *
 * <p>Local Maven runs without Docker skip this class (the house
 * {@code @Testcontainers(disabledWithoutDocker = true)} contract); the
 * GitHub Actions integration suite executes it on the candidate branch
 * — a pending run is never reported as green.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgentOneMutationIntegrationTest {

    private static final String ADMIN_SUBJECT = "a1m-admin-subject";
    private static final String CONSUMER_SUBJECT = "a1m-consumer-subject";
    private static final UUID ADMIN_ROW_ID = UUID.fromString("61616161-6161-4616-8616-616161616101");
    private static final UUID CONSUMER_ROW_ID = UUID.fromString("61616161-6161-4616-8616-616161616102");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_addresses WHERE user_id IN (?, ?)",
                ADMIN_ROW_ID, CONSUMER_ROW_ID);
        jdbcTemplate.update("DELETE FROM user_role_assignments WHERE user_id IN (?, ?)",
                ADMIN_ROW_ID, CONSUMER_ROW_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", ADMIN_ROW_ID, CONSUMER_ROW_ID);
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'ADMIN')
                """, ADMIN_ROW_ID, ADMIN_SUBJECT, "a1m-admin@example.com", "A1M Admin");
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                """, CONSUMER_ROW_ID, CONSUMER_SUBJECT, "a1m-consumer@example.com", "A1M Consumer");
    }

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

    private static String globalId(String type, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((type + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------
    // The address lifecycle: create → read back → default → IDOR wall
    // ---------------------------------------------------------------------

    @Test
    void theCallerCreatesOwnAddressReadsItBackAndSetsTheDefault() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String addressId = consumer.document("""
                        mutation { accountAddressCreate(input: {
                                firstName: "One", lastName: "Caller",
                                streetAddress1: "Street 1", city: "City", postalCode: "123",
                                country: DE }) {
                            address { id city country { code } }
                            errors { code }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("accountAddressCreate.errors").entityList(Object.class).hasSize(0)
                .path("accountAddressCreate.address.id").entity(String.class).get();

        // The read-back rides the SHARED User projection's own extend block.
        consumer.document("""
                        query { me { addresses { id city isDefaultBillingAddress } } }
                        """)
                .execute()
                .errors().verify()
                .path("me.addresses").entityList(Object.class).hasSize(1);

        consumer.document("""
                        mutation { accountSetDefaultAddress(id: "%s", type: BILLING) {
                            errors { code }
                        } }
                        """.formatted(addressId))
                .execute()
                .errors().verify()
                .path("accountSetDefaultAddress.errors").entityList(Object.class).hasSize(0);

        Integer defaults = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_addresses WHERE user_id = ? AND is_default_billing",
                Integer.class, CONSUMER_ROW_ID);
        assertThat(defaults).isEqualTo(1);
    }

    @Test
    void theConsumerCannotTouchAnotherAccountsAddress() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        String addressId = admin.document("""
                        mutation { accountAddressCreate(input: {
                                streetAddress1: "Admin Street", city: "Admin City", country: US }) {
                            address { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("accountAddressCreate.address.id").entity(String.class).get();

        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        mutation { accountAddressUpdate(id: "%s", input: { city: "Hijacked" }) {
                            errors { code }
                        } }
                        """.formatted(addressId))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty())
                .path("accountAddressUpdate").valueIsNull();
    }

    @Test
    void theStaffManagedLegCreatesAnAddressForAnyAccount() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        mutation { addressCreate(input: { city: "X", country: GB },
                                userId: "%s") {
                            address { id country { code } }
                            errors { code }
                        } }
                        """.formatted(globalId("User", CONSUMER_ROW_ID)))
                .execute()
                .errors().verify()
                .path("addressCreate.errors").entityList(Object.class).hasSize(0);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_addresses WHERE user_id = ?", Integer.class,
                CONSUMER_ROW_ID);
        assertThat(count).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // The CMS page lifecycle: page type → page → publish → read
    // ---------------------------------------------------------------------

    @Test
    void theCmsPageLifecycleExecutesOverTheRealSchema() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");

        String pageTypeId = admin.document("""
                        mutation { pageTypeCreate(input: { name: "Info Pages" }) {
                            pageType { id name slug }
                            errors { code }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("pageTypeCreate.errors").entityList(Object.class).hasSize(0)
                .path("pageTypeCreate.pageType.id").entity(String.class).get();

        String pageId = admin.document("""
                        mutation { pageCreate(input: {
                                title: "About", content: "{\\"blocks\\":[]}",
                                isPublished: false, pageType: "%s" }) {
                            page { id slug isPublished }
                            errors { code }
                        } }
                        """.formatted(pageTypeId))
                .execute()
                .errors().verify()
                .path("pageCreate.errors").entityList(Object.class).hasSize(0)
                .path("pageCreate.page.id").entity(String.class).get();

        admin.document("""
                        mutation { pageBulkPublish(ids: ["%s"], isPublished: true) { count } }
                        """.formatted(pageId))
                .execute()
                .errors().verify()
                .path("pageBulkPublish.count").entity(Integer.class).isEqualTo(1);

        UUID nativePageId = UUID.fromString(new String(
                java.util.Base64.getDecoder().decode(pageId)).split(":", 2)[1]);
        Integer published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cms_pages WHERE id = ? AND is_published",
                Integer.class, nativePageId);
        assertThat(published).isEqualTo(1);

        admin.document("""
                        query { pages(first: 10) { totalCount edges { node { slug isPublished } } } }
                        """)
                .execute()
                .errors().verify()
                .path("pages.edges[*].node.slug").entityList(String.class).contains("about");
    }

    @Test
    void theConsumerIsDeniedTheCmsSurface() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        mutation { pageTypeCreate(input: { name: "Nope" }) { errors { code } } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty())
                .path("pageTypeCreate").valueIsNull();
    }
}
