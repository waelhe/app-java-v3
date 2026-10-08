package com.marketplace.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AiChatGateway {

    private static final String CONVERSATION_PREFIX = "ai:";
    private final ChatClient chatClient;

    public AiChatGateway(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    public Flux<ChatClientResponse> stream(UUID userId, String conversationId, String userText) {
        Objects.requireNonNull(userId, "userId must not be null");
        String scopedConversationId = scopeConversation(userId, conversationId);

        return chatClient.prompt()
                .advisors(advisors -> advisors.param(
                        ChatMemory.CONVERSATION_ID, scopedConversationId))
                .toolContext(Map.of("userId", userId.toString()))
                .user(Objects.requireNonNull(userText, "userText must not be null"))
                .stream()
                .chatClientResponse();
    }

    public ChatClientResponse chat(UUID userId, String conversationId, String userText) {
        Objects.requireNonNull(userId, "userId must not be null");
        String scopedConversationId = scopeConversation(userId, conversationId);

        ChatClientResponse response = chatClient.prompt()
                .advisors(advisors -> advisors.param(
                        ChatMemory.CONVERSATION_ID, scopedConversationId))
                .toolContext(Map.of("userId", userId.toString()))
                .user(Objects.requireNonNull(userText, "userText must not be null"))
                .call()
                .chatClientResponse();

        if (response == null || response.chatResponse() == null) {
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        return response;
    }

    static String scopeConversation(UUID userId, String conversationId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        // A deterministic UUIDv3 derived from the scoped pair — the raw
        // concatenation ("ai:" + 36-char user id + ":" + conversation id)
        // overflows V107's official conversation_id VARCHAR(36) at its
        // shortest input (41 chars), and PostgreSQL rejects every chat
        // memory write with "value too long" once a provider is active
        // (invisible to the mocked-ChatClient unit tests). The derived key
        // is exactly 36 chars, stays deterministic (the same user +
        // conversation always map to the same memory row) and stays
        // per-user (different users never collide — the user id is inside
        // the hashed scope).
        String scope = CONVERSATION_PREFIX + userId + ":" + conversationId.trim();
        return UUID.nameUUIDFromBytes(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }
}
