package com.marketplace.ai;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single entry point to the AI chat capability — the conversational
 * gateway over Spring AI's {@code ChatClient} with per-(user, conversation)
 * memory scoping (the V107 chat-memory schema).
 *
 * <p><b>Union note (2026-10-08, the two-generation merge):</b> this class is
 * the #514 design (merged, three CodeRabbit rounds adopted) — the
 * conversation-scoped {@code chat}/{@code stream} pair carrying
 * {@link ChatMemory} and the tool context. Track A's earlier single-turn
 * gate retired with its design. What survives from Track A's D.4 (channel
 * resilience wave) is the outage-isolation half: {@code chat} — the
 * synchronous external crossing — wears
 * {@code @CircuitBreaker(name = "aiChat")}, the official Resilience4j
 * annotation mirroring the payments PSP house pattern; the instance (with
 * the OFF-state honesty ignore-list) rides {@code application.yml}, and the
 * class dropped {@code final} for the CGLIB proxy the annotation aspect
 * needs (a final class proxies silently to nothing — the measured trap this
 * note exists to prevent).
 *
 * <p><b>Why @Retry did not survive the union:</b> the D.4 wave's retry leg
 * was measured safe on the single-turn design ("an idempotent completion —
 * no side effects to double-apply"), but THIS design writes the user turn
 * into {@link ChatMemory} through the advisor on every invocation — a
 * retried call would duplicate the turn in the conversation history, so the
 * retry instance was retired rather than transplanted (the yml documents
 * the retirement where the instance used to live). {@code stream()} stays
 * unannotated: the Flux-returning crossing would need the reactor-typed
 * resilience support on this module's classpath, and no speculative
 * dependency rides an unwired consumer — the first streaming consumer's
 * own wiring carries that decision.
 */
public class AiChatGateway {

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

    /**
     * Sends one user turn to the bound provider and returns its response —
     * the AI channel's synchronous external crossing, so it wears the D.4
     * outage-isolation breaker ({@code aiChat}): a provider outage fails
     * FAST with {@code CallNotPermittedException} instead of hanging a
     * request thread. The OFF state stays honest — the yml instance's
     * ignore-list keeps the circuit closed on the capability's own 503s.
     */
    @CircuitBreaker(name = "aiChat")
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
