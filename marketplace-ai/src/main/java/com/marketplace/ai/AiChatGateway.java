package com.marketplace.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    private static final Logger log = LoggerFactory.getLogger(AiChatGateway.class);

    private final ChatClient chatClient;
    private final ChatClient guardedChatClient;
    private final @Nullable JevModelRouter modelRouter;
    private final boolean guardrailsEnabled;

    public AiChatGateway(ChatClient chatClient) {
        this(chatClient, chatClient, null);
    }

    public AiChatGateway(ChatClient chatClient, @Nullable JevModelRouter modelRouter) {
        this(chatClient, chatClient, modelRouter);
    }

    /**
     * Keeps the unguarded streaming client separate from call-only advisors. When official
     * call-only guardrails are configured, SSE-shaped methods execute a guarded complete call
     * and emit its verified result only after the advisor returns. This avoids sending
     * unverified answer fragments while respecting JevGuardrailAdvisor's unsupported stream API.
     */
    public AiChatGateway(
            ChatClient chatClient,
            ChatClient guardedChatClient,
            @Nullable JevModelRouter modelRouter) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.guardedChatClient = Objects.requireNonNull(guardedChatClient, "guardedChatClient must not be null");
        this.guardrailsEnabled = guardedChatClient != chatClient;
        this.modelRouter = modelRouter;
    }

    public Flux<ChatClientResponse> stream(UUID userId, String conversationId, String userText) {
        if (this.guardrailsEnabled) {
            // JevGuardrailAdvisor's official call path screens the complete answer. Buffer the
            // result before publishing it rather than leaking unchecked fragments via stream().
            return Mono.fromCallable(() -> chat(userId, conversationId, userText))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flux();
        }
        return streamPrompt(userId, conversationId, userText)
                .flatMapMany(request -> request.stream().chatClientResponse());
    }

    /**
     * Streams generated answer text through Spring AI's public ChatClient API.
     * SessionMemoryAdvisor persists the complete tool-call-aware turn.
     */
    public Flux<String> streamAnswer(UUID userId, String conversationId, String userText) {
        if (this.guardrailsEnabled) {
            // The official advisor explicitly rejects streaming because it must screen the whole
            // answer. Use its guarded call path and expose the verified answer as one SSE token;
            // true token-by-token streaming remains available when guardrails are disabled.
            return Mono.fromCallable(() -> answer(userId, conversationId, userText))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flux();
        }
        // Jev is a decision API, not a streaming chat model. Route off the request thread, then
        // hand the selected model options to Spring AI's public stream API.
        return streamPrompt(userId, conversationId, userText)
                .flatMapMany(request -> request.stream().content());
    }

    public String answer(UUID userId, String conversationId, String userText) {
        String content = prompt(userId, conversationId, userText, true).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Spring AI returned an empty chat answer");
        }
        return content;
    }

    public ChatClientResponse chat(UUID userId, String conversationId, String userText) {
        ChatClientResponse response = prompt(userId, conversationId, userText, true)
                .call()
                .chatClientResponse();
        if (response == null || response.chatResponse() == null) {
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        return response;
    }

    private Mono<ChatClient.ChatClientRequestSpec> streamPrompt(
            UUID userId, String conversationId, String userText) {
        return Mono.fromCallable(() -> prompt(userId, conversationId, userText, false))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ChatClient.ChatClientRequestSpec prompt(
            UUID userId, String conversationId, String userText, boolean applyCallGuardrails) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }

        String message = Objects.requireNonNull(userText, "userText must not be null");
        ChatClient selectedClient = applyCallGuardrails ? this.guardedChatClient : this.chatClient;
        ChatClient.ChatClientRequestSpec request = selectedClient.prompt()
                .advisors(advisors -> advisors
                        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, conversationId)
                        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, userId.toString()))
                .toolContext(Map.of("userId", userId.toString()))
                .user(message);

        if (this.modelRouter != null) {
            JevModelRouter.RouteDecision route = this.modelRouter.route(message);
            request.options(this.modelRouter.optionsFor(route));
            log.info("Jev model route tier={} model={} confidence={} fallback={}",
                    route.effectiveTier(), route.model(), route.confidence(), route.fallbackApplied());
        }

        return request;
    }
}
