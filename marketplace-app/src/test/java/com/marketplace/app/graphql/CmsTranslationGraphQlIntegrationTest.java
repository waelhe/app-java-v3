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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the pinned Saleor 3.23.45 translation read-back
 * contract for the CMS family (the wave-11 #806 review: the SDL declared
 * {@code Page.translation} and {@code MenuItem.translation} with no
 * resolver; the fields are now served from the owning console read
 * source). The HTTP GraphQL request runs through the real Spring
 * Security/DGS chain and reads Flyway-backed PostgreSQL, so this catches
 * schema-codegen, resolver-binding and persistence regressions that a
 * schema signature inventory alone cannot detect: the persisted
 * translation round trip (write via {@code pageTranslate}/
 * {@code menuItemTranslate}, read back through the field), the honest
 * null for a language with no translation row, the typed global-ID
 * stability of the rendered {@code PageTranslation.id}/
 * {@code MenuItemTranslation.id} (the TRANSLATION ROW's own UUID —
 * base64 of {@code TypeName:uuid}, never the parent object's), and the
 * persisted read-back of both translation stores.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CmsTranslationGraphQlIntegrationTest {

    private static final UUID ADMIN_ROW_ID =
            UUID.fromString("61616161-6161-4616-8616-616161616724");
    private static final String ADMIN_SUBJECT = "saleor-translation-contract-admin";
    private static final String ADMIN_EMAIL = "saleor-translation-contract-admin@example.com";

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
                """, ADMIN_ROW_ID, ADMIN_SUBJECT, ADMIN_EMAIL, "Saleor Translation Contract Admin");
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

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** The typed global-ID wire payload: base64 of {@code TypeName:uuid}. */
    private static String wirePayload(String globalId) {
        return new String(Base64.getDecoder().decode(globalId), StandardCharsets.UTF_8);
    }

    @Test
    void pageTranslationRoundTripsThroughTheOwningReadSourceWithATypedRowIdentity() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        String suffix = suffix();

        var pageTypeResponse = admin.document("""
                mutation {
                    pageTypeCreate(input: { name: "Translation Page Type", slug: "tt-%s" }) {
                        pageType { id }
                        errors { code }
                    }
                }
                """.formatted(suffix)).execute();
        pageTypeResponse.errors().verify();
        pageTypeResponse.path("pageTypeCreate.errors").entityList(Object.class).hasSize(0);
        String pageTypeId = pageTypeResponse.path("pageTypeCreate.pageType.id")
                .entity(String.class).get();

        var pageResponse = admin.document("""
                mutation($input: PageCreateInput!) {
                    pageCreate(input: $input) {
                        page { id title }
                        errors { code }
                    }
                }
                """)
                .variable("input", Map.of(
                        "title", "Translation Page",
                        "slug", "tp-" + suffix,
                        "pageType", pageTypeId,
                        "isPublished", true))
                .execute();
        pageResponse.errors().verify();
        pageResponse.path("pageCreate.errors").entityList(Object.class).hasSize(0);
        String pageId = pageResponse.path("pageCreate.page.id").entity(String.class).get();

        var translateResponse = admin.document("""
                mutation($id: ID!, $input: PageTranslationInput!) {
                    pageTranslate(id: $id, input: $input, languageCode: AR) {
                        errors { code }
                    }
                }
                """)
                .variable("id", pageId)
                .variable("input", Map.of(
                        "title", "صفحة مترجمة",
                        "content", "محتوى مترجم",
                        "seoTitle", "SEO مترجم"))
                .execute();
        translateResponse.errors().verify();
        translateResponse.path("pageTranslate.errors").entityList(Object.class).hasSize(0);

        var readBack = admin.document("""
                query($id: ID!) {
                    page(id: $id) {
                        title
                        translation(languageCode: AR) { id title content language seoTitle }
                    }
                }
                """)
                .variable("id", pageId)
                .execute();
        readBack.errors().verify();
        assertThat(readBack.path("page.title").entity(String.class).get())
                .isEqualTo("Translation Page");
        assertThat(readBack.path("page.translation.title").entity(String.class).get())
                .isEqualTo("صفحة مترجمة");
        assertThat(readBack.path("page.translation.content").entity(String.class).get())
                .isEqualTo("محتوى مترجم");
        assertThat(readBack.path("page.translation.seoTitle").entity(String.class).get())
                .isEqualTo("SEO مترجم");
        assertThat(readBack.path("page.translation.language").entity(String.class).get())
                .isEqualTo("AR");
        String translationId = readBack.path("page.translation.id").entity(String.class).get();
        assertThat(translationId).isNotBlank();

        // The typed-ID wire contract: base64 of "PageTranslation:<row-uuid>"
        // — the TRANSLATION row's identity, never the parent page's.
        assertThat(wirePayload(translationId)).startsWith("PageTranslation:");
        assertThat(wirePayload(translationId))
                .isNotEqualTo(wirePayload(pageId).replaceFirst("^Page:", "PageTranslation:"));

        // Typed-ID stability: the same row renders the same global ID.
        var secondRead = admin.document("""
                query($id: ID!) {
                    page(id: $id) { translation(languageCode: AR) { id } }
                }
                """)
                .variable("id", pageId)
                .execute();
        secondRead.errors().verify();
        assertThat(secondRead.path("page.translation.id").entity(String.class).get())
                .isEqualTo(translationId);

        // A language with no translation row answers the honest null.
        var absentRead = admin.document("""
                query($id: ID!) {
                    page(id: $id) { translation(languageCode: EN) { id } }
                }
                """)
                .variable("id", pageId)
                .execute();
        absentRead.errors().verify();
        absentRead.path("page.translation").valueIsNull();

        // Persisted read-back: the owning store carries the translated row.
        Integer persisted = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM cms_page_translations t
                JOIN cms_pages p ON p.id = t.page_id
                WHERE p.slug = ? AND t.language_code = 'ar'
                  AND t.title = ? AND t.seo_title = ?
                """, Integer.class, "tp-" + suffix, "صفحة مترجمة", "SEO مترجم");
        assertThat(persisted).isEqualTo(1);
    }

    @Test
    void menuItemTranslationRoundTripsThroughTheOwningReadSourceWithATypedRowIdentity() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        String suffix = suffix();

        var menuResponse = admin.document("""
                mutation {
                    menuCreate(input: { name: "Translation Menu", slug: "tm-%s" }) {
                        menu { id }
                        errors { code }
                    }
                }
                """.formatted(suffix)).execute();
        menuResponse.errors().verify();
        menuResponse.path("menuCreate.errors").entityList(Object.class).hasSize(0);
        String menuId = menuResponse.path("menuCreate.menu.id").entity(String.class).get();

        var itemResponse = admin.document("""
                mutation($input: MenuItemCreateInput!) {
                    menuItemCreate(input: $input) {
                        menuItem { id name }
                        errors { code }
                    }
                }
                """)
                .variable("input", Map.of(
                        "name", "Translatable Item",
                        "menu", menuId,
                        "url", "/translatable"))
                .execute();
        itemResponse.errors().verify();
        itemResponse.path("menuItemCreate.errors").entityList(Object.class).hasSize(0);
        String itemId = itemResponse.path("menuItemCreate.menuItem.id").entity(String.class).get();

        var translateResponse = admin.document("""
                mutation($id: ID!, $input: NameTranslationInput!) {
                    menuItemTranslate(id: $id, input: $input, languageCode: AR) {
                        errors { code }
                    }
                }
                """)
                .variable("id", itemId)
                .variable("input", Map.of("name", "عنصر مترجم"))
                .execute();
        translateResponse.errors().verify();
        translateResponse.path("menuItemTranslate.errors").entityList(Object.class).hasSize(0);

        var readBack = admin.document("""
                query($id: ID!) {
                    menuItem(id: $id) {
                        name
                        translation(languageCode: AR) { id name language }
                    }
                }
                """)
                .variable("id", itemId)
                .execute();
        readBack.errors().verify();
        assertThat(readBack.path("menuItem.name").entity(String.class).get())
                .isEqualTo("Translatable Item");
        assertThat(readBack.path("menuItem.translation.name").entity(String.class).get())
                .isEqualTo("عنصر مترجم");
        assertThat(readBack.path("menuItem.translation.language").entity(String.class).get())
                .isEqualTo("AR");
        String translationId = readBack.path("menuItem.translation.id").entity(String.class).get();
        assertThat(translationId).isNotBlank();

        // The typed-ID wire contract: base64 of
        // "MenuItemTranslation:<row-uuid>" — the TRANSLATION row's
        // identity, never the parent item's.
        assertThat(wirePayload(translationId)).startsWith("MenuItemTranslation:");
        assertThat(wirePayload(translationId))
                .isNotEqualTo(wirePayload(itemId).replaceFirst("^MenuItem:",
                        "MenuItemTranslation:"));

        // A language with no translation row answers the honest null.
        var absentRead = admin.document("""
                query($id: ID!) {
                    menuItem(id: $id) { translation(languageCode: EN) { id } }
                }
                """)
                .variable("id", itemId)
                .execute();
        absentRead.errors().verify();
        absentRead.path("menuItem.translation").valueIsNull();

        // Persisted read-back: the owning store carries the translated row.
        Integer persisted = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM cms_menu_item_translations t
                JOIN cms_menu_items i ON i.id = t.menu_item_id
                JOIN cms_menus m ON m.id = i.menu_id
                WHERE m.slug = ? AND i.name = ?
                  AND t.language_code = 'ar' AND t.name = ?
                """, Integer.class, "tm-" + suffix, "Translatable Item", "عنصر مترجم");
        assertThat(persisted).isEqualTo(1);
    }
}
