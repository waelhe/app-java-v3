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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the pinned Saleor 3.23.43 menu-item creation contract.
 * The official operation takes exactly one MenuItemCreateInput argument;
 * menu and parent IDs live inside that input. The HTTP GraphQL request runs
 * through the real Spring Security/DGS chain and writes to Flyway-backed
 * PostgreSQL, so this catches schema-codegen, resolver-binding and persistence
 * regressions that a schema signature inventory alone cannot detect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CmsNavigationGraphQlIntegrationTest {

    private static final UUID ADMIN_ROW_ID =
            UUID.fromString("61616161-6161-4616-8616-616161616723");
    private static final String ADMIN_SUBJECT = "saleor-menu-contract-admin";
    private static final String ADMIN_EMAIL = "saleor-menu-contract-admin@example.com";

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
    void seedAdminPrincipal() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ADMIN_ROW_ID);
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'ADMIN')
                """, ADMIN_ROW_ID, ADMIN_SUBJECT, ADMIN_EMAIL, "Saleor Menu Contract Admin");
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

    @Test
    void menuItemCreateReadsMenuAndParentFromSaleorInputAndPersistsTheTree() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        String slug = "saleor-contract-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        var menuResponse = admin.document("""
                mutation {
                    menuCreate(input: { name: "Saleor contract menu", slug: "%s" }) {
                        menu { id slug }
                        errors { code }
                    }
                }
                """.formatted(slug)).execute();
        menuResponse.errors().verify();
        menuResponse.path("menuCreate.errors").entityList(Object.class).hasSize(0);
        String menuId = menuResponse.path("menuCreate.menu.id").entity(String.class).get();

        var parentResponse = admin.document("""
                mutation($input: MenuItemCreateInput!) {
                    menuItemCreate(input: $input) {
                        menuItem { id name url menu { id } }
                        errors { code }
                    }
                }
                """)
                .variable("input", Map.of(
                        "name", "Parent",
                        "menu", menuId,
                        "url", "/parent"))
                .execute();
        parentResponse.errors().verify();
        parentResponse.path("menuItemCreate.errors").entityList(Object.class).hasSize(0);
        String parentId = parentResponse.path("menuItemCreate.menuItem.id")
                .entity(String.class).get();

        var childResponse = admin.document("""
                mutation($input: MenuItemCreateInput!) {
                    menuItemCreate(input: $input) {
                        menuItem { id name url menu { id } }
                        errors { code }
                    }
                }
                """)
                .variable("input", Map.of(
                        "name", "Child",
                        "menu", menuId,
                        "parent", parentId,
                        "url", "/child"))
                .execute();
        childResponse.errors().verify();
        childResponse.path("menuItemCreate.errors").entityList(Object.class).hasSize(0);
        String childId = childResponse.path("menuItemCreate.menuItem.id")
                .entity(String.class).get();
        assertThat(childId).isNotBlank();

        var readBack = admin.document("""
                query($id: ID!) {
                    menuItem(id: $id) { id name url level }
                }
                """)
                .variable("id", childId)
                .execute();
        readBack.errors().verify();
        assertThat(readBack.path("menuItem.name").entity(String.class).get()).isEqualTo("Child");
        assertThat(readBack.path("menuItem.url").entity(String.class).get()).isEqualTo("/child");

        Integer parentChildLinkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM cms_menu_items child
                JOIN cms_menu_items parent ON parent.id = child.parent_id
                JOIN cms_menus menu ON menu.id = child.menu_id
                WHERE menu.slug = ? AND parent.name = ? AND child.name = ? AND child.url = ?
                """, Integer.class, slug, "Parent", "Child", "/child");
        assertThat(parentChildLinkCount).isEqualTo(1);
    }
}
