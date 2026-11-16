package com.marketplace.ai;

import org.springframework.ai.chat.client.ChatClient;
import java.util.Objects;

public final class AiQueryUnderstanding {

    private final ChatClient chatClient;

    public AiQueryUnderstanding(ChatClient.Builder builder) {
        this.chatClient = Objects.requireNonNull(builder, "builder must not be null").build();
    }

    public AiSearchIntent understand(String userText) {
        Objects.requireNonNull(userText, "userText must not be null");

        AiSearchIntent result = chatClient.prompt()
                .system("""
                        Interpret the user's marketplace request.
                        Return SEARCH when the user is looking for marketplace listings,
                        LISTING_DETAILS when the user asks about a specific listing,
                        and GENERAL for unrelated or purely conversational requests.
                        Extract only values explicitly supported by the user text.
                        Do not invent categories, prices, guest counts, or listing facts.
                        """)
                .user(userText)
                .call()
                .entity(AiSearchIntent.class, spec -> spec.validateSchema());

        if (result == null) {
            throw new IllegalStateException("Spring AI returned an empty structured result");
        }
        return result;
    }
}
