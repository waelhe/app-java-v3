package com.marketplace.catalog;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = CatalogController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class CatalogControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ListingMapper listingMapper;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void listActive_returnsOk() throws Exception {
        when(catalogService.listActive(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_returnsCreated() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockListing(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any())).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var listing = mockListing(id);
        var response = mockResponse(id);

        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(get("/api/v1/listings/{id}", id))
                .andExpect(status().isOk());
    }

    private static ProviderListing mockListing(UUID id) {
        var listing = org.mockito.Mockito.mock(ProviderListing.class);
        when(listing.getId()).thenReturn(id);
        return listing;
    }

    private static ListingResponse mockResponse(UUID id) {
        return new ListingResponse(id, null, null, null, null, null, null, null);
    }
}
