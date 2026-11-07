package com.marketplace.catalog;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 2026-11-07 #808 round-10 review (Major · Functional Correctness):
 * the required-attribute migration path's PostgreSQL journey gate — the
 * CHOSEN behavior (assign as optional → the owners migrate the values →
 * the guarded promotion) proven on the REAL Flyway schema and the REAL
 * probe queries. The unit tests mock the probes' verdicts; this class
 * proves the NOT-EXISTS semantics themselves — a NEW assignment's probe
 * finds every published-shelf row lacking the value (the live-assignment
 * subquery is empty), the refusal messages name the executable
 * migration, the promotion stays fail-closed row-by-row until the whole
 * shelf carries the value, and the VARIANT mirror plus the
 * VARIANT_SELECTION every-variant law hold on the same real chain.
 *
 * <p>Boot pattern follows {@code CategoryRegistryIntegrationTest}: the
 * isolated postgres container via {@code @ServiceConnection}, Flyway
 * enabled, {@code ddl-auto=none}, {@code JdbcTemplate} fixtures, and
 * {@code @MockitoBean CurrentUserProvider} as the ONLY mocked seam — the
 * PROVIDER/ADMIN JWTs ride the REAL resource-server chain.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class RequiredAttributeMigrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private final tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();

    private static final UUID PROVIDER_USER_ID = UUID.randomUUID();

    /** The PRODUCT-scope journey — the scope the round-10 finding proved had NO executable path at all. */
    @Test
    void theProductScopeMigrationJourneyRunsEndToEndOnTheRealShelf() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);
        seedStoreCategory();
        TypedSchema schema = seedSchema(false);
        UUID productA = createAndPublishProduct(schema, "A");
        UUID productB = createAndPublishProduct(schema, "B");

        // 1) The direct required assign of the NEW origin attribute refuses on the
        //    real probe: the definition's live-assignment set on this type is empty,
        //    so every published product "lacks the value" — and the refusal names
        //    the executable migration (never the dead-end "migrate first").
        mockMvc.perform(adminAssign(schema.typeId(), schema.originDefinitionId(), "PRODUCT", true, 1))
                .andExpect(status().isConflict())
                .andExpect(contentContains("assign it as optional"))
                .andExpect(contentContains("promote it to required"));

        // 2) The optional assign passes — the migration's first half.
        UUID originAssignmentId = createAdminAssignment(schema.typeId(), schema.originDefinitionId(), "PRODUCT", false, 1);

        // 3) The promotion refuses while the shelf still lacks the value — the
        //    same fail-closed law over the now-LIVE assignment's coverage.
        mockMvc.perform(promote(originAssignmentId, true))
                .andExpect(status().isConflict())
                .andExpect(contentContains("the owners store the values first"));

        // 4) Product A migrates — the owner's own value store carries the new
        //    attribute's value alongside the existing required material value.
        mockMvc.perform(put("/api/v1/store/products/{id}/attributes", productA)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"assignmentId":"%s","optionId":"%s"},
                                 {"assignmentId":"%s","optionId":"%s"}]
                                """.formatted(schema.materialAssignmentId(), schema.cottonOptionId(),
                                        originAssignmentId, schema.importedOptionId())))
                .andExpect(status().isOk());

        // 5) The promotion STILL refuses — product B lacks the value: the probe
        //    is per-row fail-closed, never "one product migrated, all pass".
        mockMvc.perform(promote(originAssignmentId, true))
                .andExpect(status().isConflict())
                .andExpect(contentContains("the owners store the values first"));

        // 6) Product B migrates; the promotion passes on the real coverage.
        mockMvc.perform(put("/api/v1/store/products/{id}/attributes", productB)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"assignmentId":"%s","optionId":"%s"},
                                 {"assignmentId":"%s","optionId":"%s"}]
                                """.formatted(schema.materialAssignmentId(), schema.linenOptionId(),
                                        originAssignmentId, schema.localOptionId())))
                .andExpect(status().isOk());
        mockMvc.perform(promote(originAssignmentId, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true));
        assertThatAssignmentRequired(originAssignmentId, true);

        // 7) The demotion is the probe-free relaxation — the values stay, the
        //    publication gate simply stops demanding them.
        mockMvc.perform(promote(originAssignmentId, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false));
        assertThatAssignmentRequired(originAssignmentId, false);
    }

    /** The VARIANT-scope mirror and the VARIANT_SELECTION law on the same real chain. */
    @Test
    void theVariantScopeMirrorAndTheSelectionLawRunOnTheRealShelf() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(PROVIDER_USER_ID);
        seedStoreCategory();
        TypedSchema selectionSchema = seedSchema(true);
        TypedSchema plainSchema = seedSchema(false);

        // A published selection type with one live variant — the variant's
        // values were written at registration only (the pinned update
        // contract's own law), so a NEW variant-scope attribute's probe
        // finds the live variant lacking the value.
        UUID productC = createAndPublishProductWithOneVariant(selectionSchema, "C");
        UUID productD = createAndPublishProduct(plainSchema, "D");

        // The direct required VARIANT assign refuses on the real probe.
        mockMvc.perform(adminAssign(selectionSchema.typeId(), selectionSchema.sizeDefinitionId(), "VARIANT", true, 0))
                .andExpect(status().isConflict())
                .andExpect(contentContains("assign it as optional"));

        // The optional assign passes; the promotion refuses while the live
        // variant still lacks the value (the re-registration migration).
        UUID sizeAssignmentId = createAdminAssignment(selectionSchema.typeId(),
                selectionSchema.sizeDefinitionId(), "VARIANT", false, 0);
        mockMvc.perform(promote(sizeAssignmentId, true))
                .andExpect(status().isConflict())
                .andExpect(contentContains("re-register with the values first"));

        // The VARIANT_SELECTION every-variant law holds on the promotion
        // route: the selection's required flag is not demotable.
        mockMvc.perform(promote(selectionSchema.colorAssignmentId(), false))
                .andExpect(status().isConflict())
                .andExpect(contentContains("must be required for every variant"));

        // The promotion passes when the coverage holds: the plain type's
        // published product carries NO live variant, so the variant probe
        // finds nothing lacking.
        UUID careAssignmentId = createAdminAssignment(plainSchema.typeId(),
                plainSchema.sizeDefinitionId(), "VARIANT", false, 0);
        mockMvc.perform(promote(careAssignmentId, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true));
        assertThatAssignmentRequired(careAssignmentId, true);
    }

    // ------------------------------------------------------------------
    // The fixtures (the journey test's exact seeding shapes).
    // ------------------------------------------------------------------

    private record TypedSchema(UUID typeId, UUID materialDefinitionId, UUID materialAssignmentId,
                               UUID cottonOptionId, UUID linenOptionId,
                               UUID originDefinitionId, UUID importedOptionId, UUID localOptionId,
                               UUID sizeDefinitionId, UUID colorDefinitionId, UUID colorAssignmentId,
                               UUID redOptionId) { }

    private void seedStoreCategory() {
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "required-migration", "Required migration", "رحلة الإلزام", 0);
    }

    /**
     * Seeds one typed schema: a required CHOICE material (PRODUCT), a NEW
     * CHOICE origin (unassigned — the migration target), and a TEXT size
     * definition (the VARIANT-scope probe target). With {@code
     * withSelection} a required CHOICE color rides the VARIANT_SELECTION
     * scope so variants can register.
     */
    private TypedSchema seedSchema(boolean withSelection) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID typeId = UUID.randomUUID();
        UUID materialDefinitionId = UUID.randomUUID();
        UUID materialAssignmentId = UUID.randomUUID();
        UUID cottonOptionId = UUID.randomUUID();
        UUID linenOptionId = UUID.randomUUID();
        UUID originDefinitionId = UUID.randomUUID();
        UUID importedOptionId = UUID.randomUUID();
        UUID localOptionId = UUID.randomUUID();
        UUID sizeDefinitionId = UUID.randomUUID();
        UUID colorDefinitionId = UUID.randomUUID();
        UUID colorAssignmentId = UUID.randomUUID();
        UUID redOptionId = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO product_types (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)",
                typeId, "migration-" + unique, "Migration type", "نوع الرحلة");
        jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                + "VALUES (?, ?, ?, ?, 'CHOICE')",
                materialDefinitionId, "material-" + unique, "Material", "الخامة");
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                cottonOptionId, materialDefinitionId, "cotton", "Cotton", "قطن", 0);
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                linenOptionId, materialDefinitionId, "linen", "Linen", "كتان", 1);
        jdbcTemplate.update("INSERT INTO product_type_attribute_assignments "
                        + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                        + "VALUES (?, ?, ?, 'PRODUCT', TRUE, 0)",
                materialAssignmentId, typeId, materialDefinitionId);
        jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                + "VALUES (?, ?, ?, ?, 'CHOICE')",
                originDefinitionId, "origin-" + unique, "Origin", "المنشأ");
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                importedOptionId, originDefinitionId, "imported", "Imported", "مستورد", 0);
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                localOptionId, originDefinitionId, "local", "Local", "محلي", 1);
        jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                + "VALUES (?, ?, ?, ?, 'TEXT')",
                sizeDefinitionId, "size-" + unique, "Size", "المقاس");
        if (withSelection) {
            jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                    + "VALUES (?, ?, ?, ?, 'CHOICE')",
                    colorDefinitionId, "color-" + unique, "Color", "اللون");
            jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                            + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                    redOptionId, colorDefinitionId, "red", "Red", "أحمر", 0);
            jdbcTemplate.update("INSERT INTO product_type_attribute_assignments "
                            + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                            + "VALUES (?, ?, ?, 'VARIANT_SELECTION', TRUE, 0)",
                    colorAssignmentId, typeId, colorDefinitionId);
        }
        return new TypedSchema(typeId, materialDefinitionId, materialAssignmentId, cottonOptionId, linenOptionId,
                originDefinitionId, importedOptionId, localOptionId, sizeDefinitionId,
                colorDefinitionId, colorAssignmentId, redOptionId);
    }

    private UUID createAndPublishProduct(TypedSchema schema, String suffix) throws Exception {
        String body = mockMvc.perform(post("/api/v1/store/products")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeCategoryCode":"required-migration","title":"Migration product %s",
                                 "priceMinor":100000,"currency":"SAR","productTypeId":"%s",
                                 "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                                """.formatted(suffix, schema.typeId(),
                                        schema.materialAssignmentId(), schema.cottonOptionId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID productId = UUID.fromString(objectMapper.readTree(body).path("id").asString());
        mockMvc.perform(post("/api/v1/store/products/{id}/publish", productId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        return productId;
    }

    private UUID createAndPublishProductWithOneVariant(TypedSchema schema, String suffix) throws Exception {
        String body = mockMvc.perform(post("/api/v1/store/products")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeCategoryCode":"required-migration","title":"Selection product %s",
                                 "priceMinor":100000,"currency":"SAR","productTypeId":"%s",
                                 "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                                """.formatted(suffix, schema.typeId(),
                                        schema.materialAssignmentId(), schema.cottonOptionId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID productId = UUID.fromString(objectMapper.readTree(body).path("id").asString());
        mockMvc.perform(post("/api/v1/store/products/{id}/variants", productId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"MIGRATION-%s-RED","name":"Red","priceMinor":100000,
                                 "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                                """.formatted(suffix, schema.colorAssignmentId(), schema.redOptionId())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/store/products/{id}/publish", productId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        return productId;
    }

    private MockHttpServletRequestBuilder adminAssign(UUID typeId, UUID definitionId, String scope,
                                                      boolean required, int position) {
        return post("/api/v1/admin/store/product-types/{typeId}/attributes", typeId)
                .with(jwt().authorities(() -> "ROLE_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attributeDefinitionId\":\"" + definitionId + "\",\"scope\":\"" + scope
                        + "\",\"required\":" + required + ",\"position\":" + position + "}");
    }

    private UUID createAdminAssignment(UUID typeId, UUID definitionId, String scope,
                                       boolean required, int position) throws Exception {
        String body = mockMvc.perform(adminAssign(typeId, definitionId, scope, required, position))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("id").asString());
    }

    private MockHttpServletRequestBuilder promote(UUID assignmentId, boolean required) {
        return patch("/api/v1/admin/store/product-type-attributes/{assignmentId}", assignmentId)
                .with(jwt().authorities(() -> "ROLE_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"required\":" + required + "}");
    }

    private void assertThatAssignmentRequired(UUID assignmentId, boolean required) {
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT is_required FROM product_type_attribute_assignments WHERE id = ?",
                Boolean.class, assignmentId)).isEqualTo(required);
    }

    private static org.springframework.test.web.servlet.ResultMatcher contentContains(String fragment) {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(containsString(fragment));
    }
}
