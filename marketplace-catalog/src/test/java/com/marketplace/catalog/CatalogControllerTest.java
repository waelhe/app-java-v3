package com.marketplace.catalog;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final CatalogService catalogService = mock(CatalogService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new CatalogController(catalogService, currentUserProvider))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    private final UUID listingId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    private static void asProvider() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("provider", "",
                        List.of(new SimpleGrantedAuthority("ROLE_PROVIDER"))));
    }


    @Test
    void listActive() throws Exception {
        when(catalogService.listActive(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listByCategory() throws Exception {
        when(catalogService.listByCategory(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/listings/category/{category}", "plumbing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listByProvider() throws Exception {
        when(catalogService.listByProvider(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/listings/provider/{providerId}", providerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getById() throws Exception {
        ProviderListing listing = mock(ProviderListing.class);
        when(listing.getId()).thenReturn(listingId);
        when(catalogService.getById(any())).thenReturn(listing);

        mockMvc.perform(get("/api/v1/listings/{id}", listingId))
                .andExpect(status().isOk());
    }

    @Test
    void create() throws Exception {
        asProvider();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any())).thenReturn(mock(ProviderListing.class));

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("{\"title\":\"Plumber\",\"description\":\"Expert plumber\",\"category\":\"services\",\"priceCents\":5000}"))
                .andExpect(status().isCreated());
    }

    @Test
    void create_validationError() throws Exception {
        asProvider();
        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update() throws Exception {
        asProvider();
        when(catalogService.update(any(), any(), any(), any(), any(), any())).thenReturn(mock(ProviderListing.class));

        mockMvc.perform(put("/api/v1/listings/{id}", listingId)
                        .contentType("application/json")
                        .content("{\"title\":\"Updated\",\"description\":\"Updated desc\",\"category\":\"services\",\"priceCents\":6000}"))
                .andExpect(status().isOk());
    }

    @Test
    void activate() throws Exception {
        asProvider();
        when(catalogService.activate(any(), any())).thenReturn(mock(ProviderListing.class));

        mockMvc.perform(post("/api/v1/listings/{id}/activate", listingId))
                .andExpect(status().isOk());
    }

    @Test
    void pause() throws Exception {
        asProvider();
        when(catalogService.pause(any(), any())).thenReturn(mock(ProviderListing.class));

        mockMvc.perform(post("/api/v1/listings/{id}/pause", listingId))
                .andExpect(status().isOk());
    }

    @Test
    void archive() throws Exception {
        asProvider();
        when(catalogService.archive(any(), any())).thenReturn(mock(ProviderListing.class));

        mockMvc.perform(post("/api/v1/listings/{id}/archive", listingId))
                .andExpect(status().isOk());
    }

    @Test
    void create_withEmptyCategory_returnsBadRequest() throws Exception {
        asProvider();
        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("{\"title\":\"Test\",\"category\":\"\",\"priceCents\":5000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withNullPriceCents_returnsBadRequest() throws Exception {
        asProvider();
        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("{\"title\":\"Test\",\"category\":\"services\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_withEmptyTitle_returnsBadRequest() throws Exception {
        asProvider();
        mockMvc.perform(put("/api/v1/listings/{id}", listingId)
                        .contentType("application/json")
                        .content("{\"title\":\"\",\"category\":\"services\",\"priceCents\":5000}"))
                .andExpect(status().isBadRequest());
    }

}
