package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final UserService service = mock(UserService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UserController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();

    @Test
    void getCurrentUser() throws Exception {
        User user = new User();
        when(service.syncFromOidc(any())).thenReturn(user);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("sub-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "sub-123", "email", "test@example.com")))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);

        mockMvc.perform(get("/api/v1/users/me")
                        .principal(token))
                .andExpect(status().isOk());
    }
}
