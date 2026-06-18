package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.marketplace.shared.security.CurrentUserProvider;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * Tests for {@link UserController} using @WebMvcTest slice.
 * <p>Follows Spring Boot testing patterns:
 * @see <a href="https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#spring-boot-applications.testing-spring-boot-applications.with-mock-environment">Spring Boot Test</a>
 */
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

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private UserDetailsManager userDetailsManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AuthAuditService auditService;

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
