package com.marketplace.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Provider-neutral application entry point for AI chat.
 *
 * <p>The active provider, ChatClient builder, observability and built-in tool-calling
 * support are supplied by Spring AI auto-configuration. This class contains no provider
 * lookup, selector inspection or manual provider lifecycle logic.
 */
public final class AiChatGateway {

    private final ChatClient chatClient;

    public AiChatGateway(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ChatResponse chat(String conversationId, String userText) {
        ChatResponse response = chatClient.prompt()
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userText)
                .call()
                .chatResponse();
        if (response == null) {
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        return response;
    }
}
