package com.marketplace.identity;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = UserController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

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

    private static UserResponse mockResponse() {
        return new UserResponse(UUID.randomUUID(), null, null, null, null);
    }
}
