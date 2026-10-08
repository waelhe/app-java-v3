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
        return CONVERSATION_PREFIX + userId + ":" + conversationId.trim();
    }
}
