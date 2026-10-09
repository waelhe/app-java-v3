package com.marketplace.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral application gateway around Spring AI's managed ChatClient.
 *
 * <p>Conversation state is owned by Spring AI Session. Session IDs are opaque
 * server-issued identifiers; the advisor receives both the session ID and the
 * authenticated user ID so its official ownership check is applied on every turn.</p>
 */
public final class AiChatGateway {

    private final ChatClient chatClient;

    public AiChatGateway(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    public Flux<ChatClientResponse> stream(UUID userId, String conversationId, String userText) {
        return prompt(userId, conversationId, userText).stream().chatClientResponse();
    }

    /**
     * Streams generated answer text through Spring AI's public ChatClient API.
     * SessionMemoryAdvisor persists the complete tool-call-aware turn.
     */
    public Flux<String> streamAnswer(UUID userId, String conversationId, String userText) {
        return prompt(userId, conversationId, userText).stream().content();
    }

    public String answer(UUID userId, String conversationId, String userText) {
        String content = prompt(userId, conversationId, userText).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Spring AI returned an empty chat answer");
        }
        return content;
    }

    public ChatClientResponse chat(UUID userId, String conversationId, String userText) {
        ChatClientResponse response = prompt(userId, conversationId, userText)
                .call()
                .chatClientResponse();
        if (response == null || response.chatResponse() == null) {
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        return response;
    }

    private ChatClient.ChatClientRequestSpec prompt(
            UUID userId, String conversationId, String userText) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }

        return chatClient.prompt()
                .advisors(advisors -> advisors
                        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, conversationId)
                        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, userId.toString()))
                .toolContext(Map.of("userId", userId.toString()))
                .user(Objects.requireNonNull(userText, "userText must not be null"));
    }
}
