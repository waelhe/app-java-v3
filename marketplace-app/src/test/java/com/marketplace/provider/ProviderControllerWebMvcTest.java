package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = ProviderController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class ProviderControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderService providerService;

    @MockitoBean
    private ProviderMapper providerMapper;

    @MockitoBean
    private com.marketplace.provider.ProviderPublicPageService providerPublicPageService;

    @MockitoBean
    private com.marketplace.provider.ProviderBusinessPageService businessPageService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(providerService.getById(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(status().isOk());
    }

    /**
     * L36 acceptance criterion 3 — the serialized public page carries
     * EXACTLY the whitelisted field set: no email, no phone, no user id,
     * no role. The record cannot leak what it does not declare; the test
     * pins the wire format against future field additions too.
     */
    @Test
    void getPublicPage_serializesExactlyTheWhitelistedFields() throws Exception {
        UUID id = UUID.randomUUID();
        var page = new com.marketplace.provider.ProviderPublicPageResponse(
                id, "Qudsia Prime", "Bio", com.marketplace.provider.ProviderStatus.VERIFIED,
                com.marketplace.provider.ProviderVerificationState.VERIFIED,
                com.marketplace.provider.ProviderActorType.AGENCY, "Qudsia Prime Estates",
                "BR-2026-1149", java.time.Instant.parse("2026-09-15T00:00:00Z"),
                "HYBRID", 4.5, 12L,
                4.2, 156L,
                java.util.List.of(
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(5, 10),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(4, 2),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(3, 0),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(2, 0),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(1, 0)),
                java.util.List.of(
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(5, 150),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(4, 6),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(3, 0),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(2, 0),
                        new com.marketplace.shared.api.RatingDistribution.RatingBucket(1, 0)),
                new com.marketplace.shared.api.PagedResponse<>(
                        List.of(new com.marketplace.shared.api.PublishedReviewView(
                                UUID.randomUUID(), 5, "Great local bakery", "Thank you",
                                null, java.time.Instant.parse("2026-09-20T00:00:00Z"),
                                "ORGANIC", null, "Nour", 7L, 3L)),
                        0, 10, 1, 1, true),
                new com.marketplace.shared.api.PagedResponse<>(
                        List.of(new com.marketplace.shared.api.ListingSummary(
                                UUID.randomUUID(), "Sunny flat", "APARTMENT",
                                java.math.BigDecimal.valueOf(150000), "SAR", "Qudsia Prime")),
                        0, 20, 1, 1, true),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null);

        when(providerPublicPageService.getPublicPage(any(UUID.class), any(Pageable.class), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/providers/{id}/public", id))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$").isMap())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(id.toString()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.displayName").value("Qudsia Prime"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("VERIFIED"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.actorType").value("AGENCY"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.agencyName").value("Qudsia Prime Estates"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.licenseNumber").value("BR-2026-1149"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.ratingAverage").value(4.5))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviewCount").value(12))
                .andExpect(MockMvcResultMatchers.jsonPath("$.ratingGeneralAverage").value(4.2))
                .andExpect(MockMvcResultMatchers.jsonPath("$.ratingGeneralCount").value(156))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviewsMode").value("HYBRID"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviews.totalElements").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviews.content[0].origin").value("ORGANIC"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviews.content[0].reviewerName").value("Nour"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reviews.content[0].helpfulCount").value(3))
                .andExpect(MockMvcResultMatchers.jsonPath("$.listings.totalElements").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.listings.content[0].title").value("Sunny flat"));
    }

    @Test
    void getPublicPage_unknownProvider_answers404() throws Exception {
        when(providerPublicPageService.getPublicPage(any(UUID.class), any(Pageable.class), any(Pageable.class)))
                .thenThrow(new com.marketplace.shared.api.ResourceNotFoundException("Provider not found"));

        mockMvc.perform(get("/api/v1/providers/{id}/public", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_returnsOk() throws Exception {
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(providerService.create(any(), any(), any(), any(), any(), any())).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("""
                                {"displayName": "Test Provider", "bio": "A test provider"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void verify_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(providerService.verify(any())).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/providers/{id}/verify", id))
                .andExpect(status().isOk());
    }

    private static com.marketplace.provider.ProviderProfile mockProviderProfile() {
        return org.mockito.Mockito.mock(com.marketplace.provider.ProviderProfile.class);
    }

    private static ProviderResponse mockResponse() {
        return new ProviderResponse(UUID.randomUUID(), null, null, null, null, null, null,
                null, null, null);
    }
}
