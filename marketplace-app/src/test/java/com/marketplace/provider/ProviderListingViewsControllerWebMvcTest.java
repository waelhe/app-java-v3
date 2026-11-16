package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.marketplace.shared.api.ListingViewStats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's views
 * surface — the days-whitelist gate (the type raises the 400 before any
 * query), the "me" resolution seam (404 without a profile, the
 * ProviderStatsController contract verbatim), and the response line
 * format. The real aggregate arithmetic and boundary dates are the
 * integration test's.
 */
@WebMvcTest(controllers = ProviderListingViewsController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class ProviderListingViewsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderListingViewsService viewsService;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @MockitoBean
    private com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private static final java.util.UUID USER_ID = java.util.UUID.randomUUID();
    private static final java.util.UUID PROVIDER_ID = java.util.UUID.randomUUID();

    @Test
    @WithMockUser
    void omittedDays_defaultsToTheThirtyDayWindow() throws Exception {
        stubOwnProfile();
        when(viewsService.getViews(eq(USER_ID), any())).thenReturn(new ProviderListingViewsResponse(
                30, java.time.LocalDate.of(2026, 8, 18), java.util.List.of()));

        mockMvc.perform(get("/api/v1/providers/me/listings/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.listings").isArray());

        ArgumentCaptor<ListingViewsWindow> window = ArgumentCaptor.forClass(ListingViewsWindow.class);
        verify(viewsService).getViews(eq(USER_ID), window.capture());
        assertThat(window.getValue().days()).isEqualTo(30);
    }

    @Test
    @WithMockUser
    void eachWhitelistedWindow_reachesTheService_andTheResponseCarriesTheEntries() throws Exception {
        stubOwnProfile();
        when(viewsService.getViews(eq(USER_ID), any())).thenReturn(new ProviderListingViewsResponse(
                7, java.time.LocalDate.of(2026, 9, 10), java.util.List.of(new ListingViewStats(
                java.util.UUID.randomUUID(), "Villa", 12L))));

        for (int days : new int[]{7, 30, 90}) {
            mockMvc.perform(get("/api/v1/providers/me/listings/views")
                            .param("days", String.valueOf(days)))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<ListingViewsWindow> window = ArgumentCaptor.forClass(ListingViewsWindow.class);
        verify(viewsService, times(3)).getViews(eq(USER_ID), window.capture());
        assertThat(window.getAllValues())
                .extracting(ListingViewsWindow::days)
                .containsExactlyInAnyOrder(7, 30, 90);

        // the response line carries the per-listing entries (id, title, views)
        mockMvc.perform(get("/api/v1/providers/me/listings/views").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings[0].listingId").exists())
                .andExpect(jsonPath("$.listings[0].title").value("Villa"))
                .andExpect(jsonPath("$.listings[0].views").value(12))
                .andExpect(jsonPath("$.sinceInclusive").value("2026-09-10"));
    }

    @Test
    @WithMockUser
    void nonWhitelistedDays_isA400BeforeAnyQuery() throws Exception {
        // The window type gate runs BEFORE the "me" resolution — the same
        // order the stats controller settles (StatsWindow construction
        // first, requireOwnProviderUserId after): a malformed request is
        // a 400 regardless of who sent it.
        stubOwnProfile();

        mockMvc.perform(get("/api/v1/providers/me/listings/views").param("days", "8"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/providers/me/listings/views").param("days", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/providers/me/listings/views").param("days", "365"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/providers/me/listings/views").param("days", "abc"))
                .andExpect(status().isBadRequest());

        verify(viewsService, never()).getViews(any(), any());
    }

    @Test
    @WithMockUser
    void noProviderProfile_isA404_notA500() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(providerLookupPort.findByUserId(USER_ID)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/providers/me/listings/views"))
                .andExpect(status().isNotFound());

        verify(viewsService, never()).getViews(any(), any());
    }

    // NOTE: the anonymous-401 guard lives in the full-context integration
    // test — this slice excludes the resource-server auto-configuration, so
    // the real chain's anyRequest().authenticated() line is not wired here
    // (the same reason ProviderStatsControllerWebMvcTest carries no
    // anonymous test).

    private void stubOwnProfile() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(providerLookupPort.findByUserId(USER_ID)).thenReturn(java.util.Optional.of(
                new com.marketplace.shared.api.ProviderSummary(PROVIDER_ID, "Views Provider", "VERIFIED", USER_ID)));
    }
    // NOTE (A1 contract): the controller passes the USER id as the
    // cross-module provider id — the views service receives USER_ID (the
    // space provider_listings.provider_id carries), not the profile id.
}
