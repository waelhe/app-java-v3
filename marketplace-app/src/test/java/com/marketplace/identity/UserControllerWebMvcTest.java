package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = UserController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private UserDataExportService userDataExportService;

    @MockitoBean
    private AccountSelfDeletionService accountSelfDeletionService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser
    void getCurrentUser_returnsOk() throws Exception {
        var user = org.mockito.Mockito.mock(User.class);
        var response = mockResponse();

        when(userService.syncFromOidc(any())).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk());
    }

    /**
     * I7 Phase 2 (§5-ج): the export surface answers an authenticated
     * principal through the same slice — the aggregation is the mocked
     * boundary (the full contract is the integration guard's job).
     */
    @Test
    @WithMockUser
    void exportMyData_returnsOk() throws Exception {
        var user = org.mockito.Mockito.mock(User.class);
        var export = new UserDataExportResponse(
                new UserDataExportResponse.ExportMetadata(java.time.Instant.now(),
                        UserDataExportResponse.SCOPE_NOTICE),
                new UserDataExportResponse.Profile(UUID.randomUUID(), "sub", null, null,
                        "CONSUMER", null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of()); // L35: savedSearches; L41: memberships; L42: posts+comments; #484: reactions; L49: events+seats; L50: market items; L51: group memberships; W4: follows; W3: favorites

        when(userService.syncFromOidc(any())).thenReturn(user);
        when(userDataExportService.exportFor(user)).thenReturn(export);

        mockMvc.perform(get("/api/v1/users/me/export"))
                .andExpect(status().isOk());
    }

    /**
     * A-05 (A.3): the deletion surface's request contract at the slice —
     * a blank password answers the standing 400 VAL-001 validation
     * contract with the fieldErrors extension (the house advice's wire
     * shape). The slice carries no security filter chain (the house's
     * measured WebMvcTest shape — the same unauthenticated reach the
     * blank-reason purge slice test uses), so the wire-level 204/401 with
     * a real JWT belongs to the integration guard — the unit's declared
     * gate ("IT deletion"), which walks the real login gate.
     */
    @Test
    void deleteMyAccount_withABlankPasswordAnswersThe400ValidationContract() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-001"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        org.mockito.Mockito.verifyNoInteractions(accountSelfDeletionService);
    }

    private static UserResponse mockResponse() {
        return new UserResponse(UUID.randomUUID(), null, null, null, null);
    }
}
