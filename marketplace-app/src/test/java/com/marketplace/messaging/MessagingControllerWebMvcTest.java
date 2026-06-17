package com.marketplace.messaging;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

@WebMvcTest(controllers = MessagingController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class MessagingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessagingService messagingService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private MessageMapper messageMapper;

    @MockitoBean
    private ConversationMapper conversationMapper;

    @Test
    @WithMockUser
    void getConversation_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var conversation = mockConversation();
        var response = mockConversationResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.getConversation(any(), any())).thenReturn(conversation);
        when(conversationMapper.toResponse(conversation)).thenReturn(response);

        mockMvc.perform(get("/api/v1/messages/conversations/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getMessages_returnsOk() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.getMessages(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/messages/conversations/{conversationId}/messages", conversationId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void createConversation_returnsCreated() throws Exception {
        UUID bookingId = UUID.randomUUID();
        var conversation = mockConversation();
        var response = mockConversationResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.createConversation(any(), any())).thenReturn(conversation);
        when(conversationMapper.toResponse(conversation)).thenReturn(response);

        mockMvc.perform(post("/api/v1/messages/conversations")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s"}
                                """.formatted(bookingId)))
                .andExpect(status().isCreated());
    }

    private static Conversation mockConversation() {
        return org.mockito.Mockito.mock(Conversation.class);
    }

    private static ConversationResponse mockConversationResponse() {
        return new ConversationResponse(UUID.randomUUID(), null, null, null);
    }
}
