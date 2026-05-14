package com.marketplace.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final NotificationService service = mock(NotificationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    @Test
    void getMine() throws Exception {
        when(service.getMyNotifications(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk());
    }

    @Test
    void markRead() throws Exception {
        when(service.markAsRead(any(), any())).thenReturn(mock(Notification.class));

        mockMvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()))
                .andExpect(status().isOk());
    }
}
