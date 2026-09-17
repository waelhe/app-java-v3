package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L41 MVC slice: the membership surface's request validation and status
 * shape. The gate order (404 unknown node / 400 level 0-2 before any
 * write), the switch's atomicity and the 401-anonymous seam are pinned
 * against the REAL chain by the module integration test; this slice pins
 * the HTTP contract itself (the L31/ProviderLedger precedent).
 */
@WebMvcTest(controllers = NeighborhoodMembershipController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodMembershipControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodMembershipControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodMembershipService membershipService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubCaller() {
        // The house form (ProviderLedgerControllerWebMvcTest): the untyped
        // any() with the generic hint matches a null Authentication too —
        // any(Class) excludes nulls in Mockito 5, which silently breaks
        // every downstream stub when the resolver hands the controller a
        // null principal.
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private NeighborhoodMembershipView view(UUID userId, UUID locationId) {
        Instant now = Instant.parse("2026-09-17T09:30:00Z");
        return new NeighborhoodMembershipView(
                UUID.randomUUID(), userId, locationId, "SELF_DECLARED", now, now, now);
    }

    @Test
    void put_joinWithCreatedBody_answers201() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(membershipService.join(userId, locationId)).thenReturn(
                new NeighborhoodMembershipService.MembershipCommandResult(
                        view(userId, locationId), true));

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(locationId.toString()))
                .andExpect(jsonPath("$.verificationState").value("SELF_DECLARED"))
                .andExpect(jsonPath("$.memberSince").exists());
    }

    @Test
    void put_idempotentRejoin_answers200() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(membershipService.join(userId, locationId)).thenReturn(
                new NeighborhoodMembershipService.MembershipCommandResult(
                        view(userId, locationId), false));

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void put_missingLocationId_is400AtTheBoundary() throws Exception {
        stubCaller();

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_unknownLocation_answers404ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(membershipService.join(userId, locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void put_nonNeighborhoodNode_answers400ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(membershipService.join(userId, locationId))
                .thenThrow(new BadRequestException(
                        "locationId must reference a level-3 neighborhood node, got level 2"));

        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returnsTheStoredMembership() throws Exception {
        UUID userId = stubCaller();
        when(membershipService.getMine(userId)).thenReturn(view(userId, UUID.randomUUID()));

        mockMvc.perform(get("/api/v1/me/neighborhood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void get_withoutMembership_answers404() throws Exception {
        UUID userId = stubCaller();
        when(membershipService.getMine(userId))
                .thenThrow(new ResourceNotFoundException("No neighborhood membership"));

        mockMvc.perform(get("/api/v1/me/neighborhood"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_answers204() throws Exception {
        UUID userId = stubCaller();

        mockMvc.perform(delete("/api/v1/me/neighborhood"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_withoutMembership_answers404() throws Exception {
        UUID userId = stubCaller();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("No membership to leave"))
                .when(membershipService).leave(userId);

        mockMvc.perform(delete("/api/v1/me/neighborhood"))
                .andExpect(status().isNotFound());
    }
}
