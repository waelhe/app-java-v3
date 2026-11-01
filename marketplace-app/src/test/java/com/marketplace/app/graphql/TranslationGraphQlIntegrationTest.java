package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeDefinition;
import com.marketplace.catalog.CatalogAttributeDefinitionRepository;
import com.marketplace.catalog.CatalogAttributeOption;
import com.marketplace.catalog.CatalogAttributeOptionRepository;
import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.Category;
import com.marketplace.catalog.CategoryRepository;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): end-to-end proof of the translation family's
 * write stack — through the REAL SDL, the REAL DGS wiring, the REAL
 * security chain (the mocked JwtDecoder stamps the caller's subject
 * and roles claim exactly the way the production converter reads
 * them), the REAL catalog owner (no SPI mocking: the ADMIN
 * enforcement and the bilingual column writes execute in the real
 * service over the Flyway-migrated schema), and the REAL typed
 * global-ID codec.
 *
 * <p>The acceptance legs: the ADMIN-only gate at the owning use case
 * (a consumer caller answers AUTHZ-001 with the row untouched), the
 * AR/EN dimension addressing (each dimension's write moves only its
 * own column — the bilingual pair's other member and the row's stable
 * identity facts stay put), the read-back journeys over the real
 * repositories, the blank-name INVALID payload error (the
 * at-least-one-name law stays standing), the null-name no-op without
 * a write, the unknown row's honest NOT_FOUND, and the mistyped-ID
 * VAL-001 family.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TranslationGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p2-trans-admin-subject";
    private static final String CONSUMER_SUBJECT = "p2-trans-consumer-subject";

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
    private CategoryRepository categoryRepository;

    @Autowired
    private CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private CatalogAttributeOptionRepository attributeOptionRepository;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededCategoryIds = new ArrayList<>();
    private final List<UUID> seededOptionIds = new ArrayList<>();
    private final List<UUID> seededDefinitionIds = new ArrayList<>();

    @BeforeEach
    void seedReferenceRows() {
        Category category = categoryRepository.save(
                Category.create("p2-trans-cat-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2 Translation Category", null, 1));
        seededCategoryIds.add(category.getId());

        CatalogAttributeDefinition definition = attributeDefinitionRepository.save(
                CatalogAttributeDefinition.create("p2-trans-attr-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2 Translation Attribute", null, CatalogAttributeType.CHOICE, null));
        seededDefinitionIds.add(definition.getId());

        CatalogAttributeOption option = attributeOptionRepository.save(
                CatalogAttributeOption.create(definition.getId(),
                        "p2-opt-" + UUID.randomUUID().toString().substring(0, 8),
                        "P2 Translation Option", null, 1));
        seededOptionIds.add(option.getId());
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededOptionIds.isEmpty()) {
            attributeOptionRepository.deleteAllById(new ArrayList<>(seededOptionIds));
            seededOptionIds.clear();
        }
        if (!seededDefinitionIds.isEmpty()) {
            attributeDefinitionRepository.deleteAllById(new ArrayList<>(seededDefinitionIds));
            seededDefinitionIds.clear();
        }
        if (!seededCategoryIds.isEmpty()) {
            categoryRepository.deleteAllById(new ArrayList<>(seededCategoryIds));
            seededCategoryIds.clear();
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

    /** A well-formed typed global ID for any native UUID over the given tag. */
    private static String globalId(String tag, UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString((tag + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void adminTranslatesTheCategoryArabicDimensionAndOnlyThatColumnMoves() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededCategoryIds.get(0);

        adminTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {name: "فئة الترجمة"}, languageCode: AR) {
                            errors { field code }
                            category { name nameAr slug }
                        } }
                        """.formatted(globalId("Category", rowId)))
                .execute()
                .errors().verify()
                .path("categoryTranslate.errors").entityList(Object.class).hasSize(0)
                .path("categoryTranslate.category.nameAr").entity(String.class).isEqualTo("فئة الترجمة")
                .path("categoryTranslate.category.name").entity(String.class).isEqualTo("P2 Translation Category");

        // The real-stack read-back: only the AR column moved — the EN
        // dimension and the row's stable code stay put.
        Category row = categoryRepository.findById(rowId).orElseThrow();
        assertThat(row.getNameAr()).isEqualTo("فئة الترجمة");
        assertThat(row.getNameEn()).isEqualTo("P2 Translation Category");
        assertThat(row.getCode()).startsWith("p2-trans-cat-");
    }

    @Test
    void adminTranslatesTheCategoryEnglishDimension() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededCategoryIds.get(0);

        adminTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {name: "The Renamed Category"}, languageCode: EN) {
                            errors { code }
                            category { name }
                        } }
                        """.formatted(globalId("Category", rowId)))
                .execute()
                .errors().verify()
                .path("categoryTranslate.errors").entityList(Object.class).hasSize(0)
                .path("categoryTranslate.category.name").entity(String.class).isEqualTo("The Renamed Category");

        Category row = categoryRepository.findById(rowId).orElseThrow();
        assertThat(row.getNameEn()).isEqualTo("The Renamed Category");
        assertThat(row.getNameAr()).isNull();
    }

    @Test
    void aBlankCategoryNameAnswersTheInvalidPayloadErrorAndTheRowStands() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededCategoryIds.get(0);

        adminTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {name: "   "}, languageCode: AR) {
                            errors { field code }
                            category { id }
                        } }
                        """.formatted(globalId("Category", rowId)))
                .execute()
                .errors().verify()
                .path("categoryTranslate.errors[0].field").entity(String.class).isEqualTo("name")
                .path("categoryTranslate.errors[0].code").entity(String.class).isEqualTo("INVALID")
                .path("categoryTranslate.category").valueIsNull();

        assertThat(categoryRepository.findById(rowId).orElseThrow().getNameEn())
                .isEqualTo("P2 Translation Category");
    }

    @Test
    void aNullCategoryNameIsTheNoOpThatAnswersTheCurrentProjection() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededCategoryIds.get(0);

        adminTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {}, languageCode: AR) {
                            errors { code }
                            category { name }
                        } }
                        """.formatted(globalId("Category", rowId)))
                .execute()
                .errors().verify()
                .path("categoryTranslate.errors").entityList(Object.class).hasSize(0)
                .path("categoryTranslate.category.name").entity(String.class)
                .isEqualTo("P2 Translation Category");

        assertThat(categoryRepository.findById(rowId).orElseThrow().getNameAr()).isNull();
    }

    @Test
    void consumerIsDeniedTheCategoryTranslateByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        UUID rowId = seededCategoryIds.get(0);

        consumerTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {name: "مستهلك"}, languageCode: AR) {
                            errors { code }
                            category { id }
                        } }
                        """.formatted(globalId("Category", rowId)))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("Access Denied")));

        assertThat(categoryRepository.findById(rowId).orElseThrow().getNameAr()).isNull();
    }

    @Test
    void anUnknownCategoryAnswersTheHonestNotFound() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { categoryTranslate(id: "%s", input: {name: "مجهول"}, languageCode: AR) {
                            errors { field code }
                            category { id }
                        } }
                        """.formatted(globalId("Category", UUID.randomUUID())))
                .execute()
                .errors().verify()
                .path("categoryTranslate.errors[0].field").entity(String.class).isEqualTo("id")
                .path("categoryTranslate.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("categoryTranslate.category").valueIsNull();
    }

    @Test
    void adminTranslatesTheAttributeAndItsValue() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID definitionId = seededDefinitionIds.get(0);
        UUID optionId = seededOptionIds.get(0);

        adminTester.document("""
                        mutation { attributeTranslate(id: "%s", input: {name: "الخاصية"}, languageCode: AR) {
                            errors { code }
                            attribute { name slug }
                        } }
                        """.formatted(globalId("Attribute", definitionId)))
                .execute()
                .errors().verify()
                .path("attributeTranslate.errors").entityList(Object.class).hasSize(0)
                .path("attributeTranslate.attribute.name").entity(String.class).isEqualTo("P2 Translation Attribute");

        CatalogAttributeDefinition definition = attributeDefinitionRepository.findById(definitionId).orElseThrow();
        assertThat(definition.getNameAr()).isEqualTo("الخاصية");
        assertThat(definition.getNameEn()).isEqualTo("P2 Translation Attribute");

        adminTester.document("""
                        mutation { attributeValueTranslate(id: "%s", input: {name: "الخيار"}, languageCode: AR) {
                            errors { code }
                            attributeValue { name slug }
                        } }
                        """.formatted(globalId("AttributeValue", optionId)))
                .execute()
                .errors().verify()
                .path("attributeValueTranslate.errors").entityList(Object.class).hasSize(0)
                .path("attributeValueTranslate.attributeValue.name").entity(String.class)
                .isEqualTo("P2 Translation Option");

        CatalogAttributeOption option = attributeOptionRepository.findById(optionId).orElseThrow();
        assertThat(option.getNameAr()).isEqualTo("الخيار");
        assertThat(option.getNameEn()).isEqualTo("P2 Translation Option");
        assertThat(option.getPosition()).isEqualTo(1);
    }

    @Test
    void aMistypedIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { attributeTranslate(id: "%s", input: {name: "X"}, languageCode: AR) {
                            errors { code }
                            attribute { id }
                        } }
                        """.formatted(globalId("Category", UUID.randomUUID())))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("Invalid global ID")));
    }
}
