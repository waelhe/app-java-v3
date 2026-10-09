package com.marketplace.media;

import java.util.UUID;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-17 (compliance plan C.7 — the M1 store root): the signed-images
 * journey — «جولة صور موقّعة», the unit's declared gate. On the real
 * Flyway schema (V116's tables): the store category arrives as DATA (the
 * dictionary's own discipline — seeded here as the data operation it is),
 * the provider registers the product through the real REST surface, the
 * upload is requested for the productId target (the media line's third
 * arm — the object key under the {@code products/} namespace, the
 * presigned PUT), the upload is confirmed through the server-side
 * verification, and the owner's read carries the freshly presigned GET
 * URLs. The S3 I/O and the identity seams are mocked at the port line
 * (the CategoryRegistry integration pattern) — every gate between them
 * is the real chain.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProductMediaJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @MockitoBean
    private com.marketplace.media.S3MediaStorage storage;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void seedTheDictionaryAndTheIdentitySeams() {
        // The dictionary row arrives as DATA (the plan's own wording — never
        // a migration); the class-owned container isolates the insert.
        jdbc.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(ownerId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        // The lookup key is the CALLER's user id — the same ownerId the
        // getCurrentUserId stub answers with (the measured CI lesson of
        // 2026-10-09: a stub keyed on providerId alone left
        // findByUserId(ownerId) unanswered, so the media upload's verified-
        // provider gate rejected the caller with 403 and the signed-images
        // journey died at its second step).
        when(providerLookupPort.findByUserId(ownerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", ownerId)));
        when(storage.verifyUploaded(anyString(), anyString(), eq(1024L))).thenReturn(true);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://signed-put");
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");
    }

    @Test
    void theSignedImagesJourneyFromDictionaryToPresignedReads() throws Exception {
        // (1) The provider registers the product through the real REST
        // surface — the dictionary-membership gate passes (the data row).
        String productBody = mockMvc.perform(post("/api/v1/store/products")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType("application/json")
                        .content("""
                                {"storeCategoryCode":"journey-appliances","title":"Espresso machine",
                                 "description":"Pump-driven","priceMinor":149900,"currency":"SAR"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeCategoryCode").value("journey-appliances"))
                .andExpect(jsonPath("$.priceMinor").value(149900))
                .andReturn().getResponse().getContentAsString();
        String productId = productIdFrom(productBody);

        // (2) The upload is requested for the productId target — the media
        // line's third arm: the object key under products/, the presigned PUT.
        String assetId = mockMvc.perform(post("/api/v1/media/uploads")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType("application/json")
                        .content("{\"productId\":\"" + productId + "\",\"contentType\":\"image/jpeg\","
                                + "\"sizeBytes\":1024}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.objectKey").value(org.hamcrest.Matchers
                        .startsWith("products/" + productId + "/")))
                .andExpect(jsonPath("$.uploadUrl").value("https://signed-put"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // (3) The confirm runs the server-side verification (the storage
        // seam) and moves the asset to UPLOADED.
        mockMvc.perform(post("/api/v1/media/{id}/complete", assetId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.productId").value(productId));

        // (4) The owner's read carries the freshly presigned GET URLs.
        mockMvc.perform(get("/api/v1/media/products/{productId}", productId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].downloadUrl").value("https://signed-get"));
    }

    @Test
    void theStrangersProductReadAnswersTheHonest404() throws Exception {
        // A stranger (a different mocked user) reads a product they do not
        // own — existence itself is private (the R5 posture).
        // thenAnswer, not thenReturn: a CONSTANT random id would make the
        // stranger the product's own creator (the measured 200-instead-of-404:
        // Mockito computed the single UUID once, so creator and reader were
        // the same user); a fresh id per resolution is the actual stranger.
        when(currentUserProvider.getCurrentUserId(any(Authentication.class)))
                .thenAnswer(invocation -> UUID.randomUUID());
        String productBody = mockMvc.perform(post("/api/v1/store/products")
                        .with(jwt().authorities(() -> "ROLE_PROVIDER"))
                        .contentType("application/json")
                        .content("{\"storeCategoryCode\":\"journey-appliances\",\"title\":\"t\","
                                + "\"priceMinor\":1,\"currency\":\"SAR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String productId = productIdFrom(productBody);

        mockMvc.perform(get("/api/v1/store/products/{id}", productId)
                        .with(jwt().authorities(() -> "ROLE_PROVIDER")))
                .andExpect(status().isNotFound());
    }

    private static String productIdFrom(String body) {
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }
}
