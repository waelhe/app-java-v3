package com.marketplace.messaging;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = MessagingController.class,
    excludeAutoConfiguration = {
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

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

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

    // ------------------------------------------------------------------
    // L44 (neighborhood community plan §5 — direct neighbor messages)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser
    void openDirectConversation_new_returnsCreated() throws Exception {
        UUID recipientId = UUID.randomUUID();
        var conversation = mockConversation();
        var response = mockConversationResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.openDirectConversation(any(), eq(recipientId)))
                .thenReturn(new MessagingService.DirectConversationOutcome(conversation, true));
        when(conversationMapper.toResponse(conversation)).thenReturn(response);

        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType("application/json")
                        .content("""
                                {"recipientId": "%s"}
                                """.formatted(recipientId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    void openDirectConversation_existing_returnsOk() throws Exception {
        UUID recipientId = UUID.randomUUID();
        var conversation = mockConversation();
        var response = mockConversationResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.openDirectConversation(any(), eq(recipientId)))
                .thenReturn(new MessagingService.DirectConversationOutcome(conversation, false));
        when(conversationMapper.toResponse(conversation)).thenReturn(response);

        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType("application/json")
                        .content("""
                                {"recipientId": "%s"}
                                """.formatted(recipientId)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void openDirectConversation_self_is400() throws Exception {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(messagingService.openDirectConversation(eq(userId), eq(userId)))
                .thenThrow(new BadRequestException("Cannot open a direct conversation with yourself"));

        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType("application/json")
                        .content("""
                                {"recipientId": "%s"}
                                """.formatted(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void openDirectConversation_unknownRecipient_is404() throws Exception {
        UUID recipientId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(messagingService.openDirectConversation(any(), eq(recipientId)))
                .thenThrow(new ResourceNotFoundException("User not found: " + recipientId));

        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType("application/json")
                        .content("""
                                {"recipientId": "%s"}
                                """.formatted(recipientId)))
                .andExpect(status().isNotFound());
    }

    // The 401-anonymous seam is proven on the REAL resource-server chain in
    // DirectConversationModuleIntegrationTest.anonymousCallerIs401 — the
    // WebMvc slice's default chain is not the production chain (the house
    // convention: slices test mapping/validation, the full context tests
    // the security seams).

    @Test
    @WithMockUser
    void openDirectConversation_missingRecipientId_is400() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/messages/conversations/direct")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static Conversation mockConversation() {
        return org.mockito.Mockito.mock(Conversation.class);
    }

    private static ConversationResponse mockConversationResponse() {
        return new ConversationResponse(UUID.randomUUID(), null, null, null);
    }
}
