package com.marketplace.messaging;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagingControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final MessagingService messagingService = mock(MessagingService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new MessagingController(messagingService, currentUserProvider))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    @Test
    void getConversation() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.getConversation(any(), any())).thenReturn(mock(Conversation.class));

        mockMvc.perform(get("/api/v1/messages/conversations/{id}", conversationId))
                .andExpect(status().isOk());
    }

    @Test
    void getMessages() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.getMessages(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/messages/conversations/{id}/messages", conversationId))
                .andExpect(status().isOk());
    }

    @Test
    void createConversation() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.createConversation(any(), any())).thenReturn(mock(Conversation.class));

        mockMvc.perform(post("/api/v1/messages/conversations")
                        .contentType("application/json")
                        .content("{\"bookingId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void sendMessage() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.sendMessage(any(), any(), any())).thenReturn(mock(Message.class));

        mockMvc.perform(post("/api/v1/messages/conversations/{id}/messages", conversationId)
                        .contentType("application/json")
                        .content("{\"content\": \"Hello!\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void markAsRead() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/messages/conversations/{id}/read", conversationId))
                .andExpect(status().isOk());
    }

    @Test
    void getUnreadCount() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.getUnreadCount(any(), any())).thenReturn(5L);

        mockMvc.perform(get("/api/v1/messages/conversations/{id}/unread", conversationId))
                .andExpect(status().isOk());
    }

    @Test
    void createConversation_validationError() throws Exception {
        mockMvc.perform(post("/api/v1/messages/conversations")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendMessage_validationError() throws Exception {
        mockMvc.perform(post("/api/v1/messages/conversations/{id}/messages", conversationId)
                        .contentType("application/json")
                        .content("{\"content\": \"\"}"))
                .andExpect(status().isBadRequest());
    }
}
