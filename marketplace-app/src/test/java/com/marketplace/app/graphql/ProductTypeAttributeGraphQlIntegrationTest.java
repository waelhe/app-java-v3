package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeDefinition;
import com.marketplace.catalog.CatalogAttributeDefinitionRepository;
import com.marketplace.catalog.CatalogAttributeOption;
import com.marketplace.catalog.CatalogAttributeOptionRepository;
import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.ProductType;
import com.marketplace.catalog.ProductTypeAttributeAssignment;
import com.marketplace.catalog.ProductTypeAttributeAssignmentRepository;
import com.marketplace.catalog.ProductTypeAttributeScope;
import com.marketplace.catalog.ProductTypeRepository;

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
 * {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign},
 * {@code productTypeReorderAttributes} and
 * {@code attributeReorderValues} mutation roots, the agent-2
 * follow-on packet): end-to-end proof of the product-type attribute
 * schema's write stack — through the REAL SDL, the REAL DGS wiring,
 * the REAL security chain, the REAL catalog owner (no SPI mocking:
 * the ADMIN enforcement, the scope-model mapping, the append-position
 * derivation and the reorder's compacted re-derivation execute in
 * the real services over the Flyway-migrated schema), and the REAL
 * typed global-ID codec.
 *
 * <p>The acceptance legs: the assign's scope-model derivation with
 * the VARIANT_SELECTION invariants (the CHOICE shape + the two-option
 * floor + the required-every-variant law) proven by the real-stack
 * read-back, the duplicate assignment's ALREADY_EXISTS, the
 * contradictory PRODUCT+variantSelection INVALID, the membership
 * move's scope write, the unassign's honest skip, the reorder's
 * compacted positions over the real registry, and the consumer
 * caller's AUTHZ-001 denial with the schema untouched.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductTypeAttributeGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p2-schema-admin-subject";
    private static final String CONSUMER_SUBJECT = "p2-schema-consumer-subject";

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
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private CatalogAttributeOptionRepository attributeOptionRepository;

    @Autowired
    private ProductTypeAttributeAssignmentRepository assignmentRepository;

    @LocalServerPort
    private int port;

    private ProductType type;
    private CatalogAttributeDefinition choiceAttribute;
    private CatalogAttributeDefinition textAttribute;
    private final List<UUID> seededOptionIds = new ArrayList<>();
    private final List<UUID> seededAssignmentIds = new ArrayList<>();
    private final List<UUID> seededDefinitionIds = new ArrayList<>();
    private UUID seededTypeId;

    @BeforeEach
    void seedReferenceRows() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        type = productTypeRepository.save(ProductType.create(
                "p2-schema-type-" + run, "P2 Schema Type", null, null));
        seededTypeId = type.getId();

        choiceAttribute = attributeDefinitionRepository.save(CatalogAttributeDefinition.create(
                "p2-schema-choice-" + run, "P2 Schema Choice", null, CatalogAttributeType.CHOICE, null));
        seededDefinitionIds.add(choiceAttribute.getId());
        textAttribute = attributeDefinitionRepository.save(CatalogAttributeDefinition.create(
                "p2-schema-text-" + run, "P2 Schema Text", null, CatalogAttributeType.TEXT, null));
        seededDefinitionIds.add(textAttribute.getId());

        for (int i = 1; i <= 3; i++) {
            CatalogAttributeOption option = attributeOptionRepository.save(
                    CatalogAttributeOption.create(choiceAttribute.getId(),
                            "p2-schema-opt-" + run + "-" + i, "Option " + i, null, i));
            seededOptionIds.add(option.getId());
        }
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededAssignmentIds.isEmpty()) {
            assignmentRepository.deleteAllById(new ArrayList<>(seededAssignmentIds));
            seededAssignmentIds.clear();
        }
        if (!seededOptionIds.isEmpty()) {
            attributeOptionRepository.deleteAllById(new ArrayList<>(seededOptionIds));
            seededOptionIds.clear();
        }
        if (!seededDefinitionIds.isEmpty()) {
            attributeDefinitionRepository.deleteAllById(new ArrayList<>(seededDefinitionIds));
            seededDefinitionIds.clear();
        }
        if (seededTypeId != null) {
            productTypeRepository.deleteById(seededTypeId);
            seededTypeId = null;
        }
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them.
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

    private UUID persistAssignment(CatalogAttributeDefinition attribute, ProductTypeAttributeScope scope,
                                   int position) {
        ProductTypeAttributeAssignment assignment = assignmentRepository.save(
                ProductTypeAttributeAssignment.create(type.getId(), attribute.getId(), scope,
                        scope == ProductTypeAttributeScope.VARIANT_SELECTION, position));
        seededAssignmentIds.add(assignment.getId());
        return assignment.getId();
    }

    @Test
    void adminAssignsTheVariantSelectionWithItsInvariants() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeAssign(productTypeId: "%s",
                                operations: [{id: "%s", type: VARIANT, variantSelection: true}]) {
                            errors { field code }
                            productType { id slug }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", choiceAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productAttributeAssign.errors").entityList(Object.class).hasSize(0)
                .path("productAttributeAssign.productType.slug").entity(String.class)
                .satisfies(slug -> assertThat(slug).startsWith("p2-schema-type-"));

        // The real-stack read-back: the derived scope, the
        // required-every-variant law and the append position.
        ProductTypeAttributeAssignment assignment = assignmentRepository
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()).get(0);
        seededAssignmentIds.add(assignment.getId());
        assertThat(assignment.getScope()).isEqualTo(ProductTypeAttributeScope.VARIANT_SELECTION);
        assertThat(assignment.isRequired()).isTrue();
        assertThat(assignment.getPosition()).isEqualTo(1);
    }

    @Test
    void aContradictoryProductTypedVariantSelectionAnswersTheInvalidPayloadError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeAssign(productTypeId: "%s",
                                operations: [{id: "%s", type: PRODUCT, variantSelection: true}]) {
                            errors { field code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productAttributeAssign.errors[0].field").entity(String.class)
                .isEqualTo("variantSelection")
                .path("productAttributeAssign.errors[0].code").entity(String.class).isEqualTo("INVALID")
                .path("productAttributeAssign.productType").valueIsNull();

        assertThat(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .isEmpty();
    }

    @Test
    void aTextAttributeCannotBecomeAVariantSelection() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeAssign(productTypeId: "%s",
                                operations: [{id: "%s", type: VARIANT, variantSelection: true}]) {
                            errors { code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productAttributeAssign.errors[0].code").entity(String.class).isEqualTo("INVALID");
    }

    @Test
    void theDuplicateAssignmentAnswersTheAlreadyExistsPayloadError() {
        persistAssignment(textAttribute, ProductTypeAttributeScope.PRODUCT, 1);
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeAssign(productTypeId: "%s",
                                operations: [{id: "%s", type: PRODUCT}]) {
                            errors { field code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productAttributeAssign.errors[0].code").entity(String.class)
                .isEqualTo("ALREADY_EXISTS");
    }

    @Test
    void adminMovesTheMembershipBetweenVariantAndVariantSelection() {
        persistAssignment(choiceAttribute, ProductTypeAttributeScope.VARIANT, 1);
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeAssignmentUpdate(productTypeId: "%s",
                                operations: [{id: "%s", variantSelection: true}]) {
                            errors { code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", choiceAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productAttributeAssignmentUpdate.errors").entityList(Object.class).hasSize(0);

        ProductTypeAttributeAssignment assignment = assignmentRepository
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()).get(0);
        assertThat(assignment.getScope()).isEqualTo(ProductTypeAttributeScope.VARIANT_SELECTION);
        assertThat(assignment.isRequired()).isTrue();
    }

    @Test
    void adminUnassignsAndTheUnknownIdIsTheHonestSkip() {
        UUID known = persistAssignment(textAttribute, ProductTypeAttributeScope.PRODUCT, 1);
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productAttributeUnassign(productTypeId: "%s",
                                attributeIds: ["%s", "%s"]) {
                            errors { code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId()),
                        globalId("Attribute", UUID.randomUUID())))
                .execute()
                .errors().verify()
                .path("productAttributeUnassign.errors").entityList(Object.class).hasSize(0);

        assertThat(assignmentRepository.findById(known)).isEmpty();
    }

    @Test
    void adminReordersTheBucketWithCompactedPositions() {
        CatalogAttributeDefinition second = attributeDefinitionRepository.save(
                CatalogAttributeDefinition.create("p2-schema-second-" + UUID.randomUUID().toString()
                        .substring(0, 8), "Second Text", null, CatalogAttributeType.TEXT, null));
        seededDefinitionIds.add(second.getId());
        persistAssignment(textAttribute, ProductTypeAttributeScope.PRODUCT, 1);
        persistAssignment(second, ProductTypeAttributeScope.PRODUCT, 2);
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productTypeReorderAttributes(productTypeId: "%s", type: PRODUCT,
                                moves: [{id: "%s", sortOrder: 1}]) {
                            errors { code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId())))
                .execute()
                .errors().verify()
                .path("productTypeReorderAttributes.errors").entityList(Object.class).hasSize(0);

        List<ProductTypeAttributeAssignment> bucket = assignmentRepository
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId());
        assertThat(bucket).hasSize(2);
        assertThat(bucket.get(0).getAttributeDefinitionId()).isEqualTo(second.getId());
        assertThat(bucket.get(0).getPosition()).isEqualTo(1);
        assertThat(bucket.get(1).getAttributeDefinitionId()).isEqualTo(textAttribute.getId());
        assertThat(bucket.get(1).getPosition()).isEqualTo(2);
    }

    @Test
    void adminReordersTheAttributeValues() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID lastOption = seededOptionIds.get(2);

        adminTester.document("""
                        mutation { attributeReorderValues(attributeId: "%s",
                                moves: [{id: "%s", sortOrder: -2}]) {
                            errors { code }
                            attribute { id slug }
                        } }
                        """.formatted(globalId("Attribute", choiceAttribute.getId()),
                        globalId("AttributeValue", lastOption)))
                .execute()
                .errors().verify()
                .path("attributeReorderValues.errors").entityList(Object.class).hasSize(0)
                .path("attributeReorderValues.attribute.slug").entity(String.class)
                .satisfies(slug -> assertThat(slug).startsWith("p2-schema-choice-"));

        List<CatalogAttributeOption> options = attributeOptionRepository
                .findAllByAttributeDefinitionIdOrderByPositionAsc(choiceAttribute.getId());
        assertThat(options.get(0).getId()).isEqualTo(lastOption);
        assertThat(options.get(0).getPosition()).isEqualTo(1);
        assertThat(options.get(1).getPosition()).isEqualTo(2);
        assertThat(options.get(2).getPosition()).isEqualTo(3);
    }

    @Test
    void consumerIsDeniedTheAssignByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumerTester.document("""
                        mutation { productAttributeAssign(productTypeId: "%s",
                                operations: [{id: "%s", type: PRODUCT}]) {
                            errors { code }
                            productType { id }
                        } }
                        """.formatted(globalId("ProductType", type.getId()),
                        globalId("Attribute", textAttribute.getId())))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("Access Denied")));

        assertThat(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(type.getId()))
                .isEmpty();
    }
}
