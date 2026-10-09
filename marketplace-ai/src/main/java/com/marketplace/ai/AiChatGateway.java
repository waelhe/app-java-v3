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
    private final ChatClient policyChatClient;
    private final @Nullable JevModelRouter modelRouter;
    private final boolean completeCallPoliciesEnabled;

    public AiChatGateway(ChatClient chatClient) {
        this(chatClient, chatClient, null);
    }

    public AiChatGateway(ChatClient chatClient, @Nullable JevModelRouter modelRouter) {
        this(chatClient, chatClient, modelRouter);
    }

    /**
     * Keeps the normal streaming client separate from complete-answer policy advisors.
     * When TypeSafe self-refinement or guardrails are enabled, SSE-shaped methods execute
     * the policy-checked call and emit the policy-processed result only after evaluation. This avoids
     * sending unchecked answer fragments and respects the official advisors' full-answer contract.
     */
    public AiChatGateway(
            ChatClient chatClient,
            ChatClient policyChatClient,
            @Nullable JevModelRouter modelRouter) {
        this(chatClient, policyChatClient, modelRouter, policyChatClient != chatClient);
    }

    /**
     * Constructor used by auto-configuration to carry the actual policy decision,
     * rather than inferring it from ChatClient object identity.
     */
    public AiChatGateway(
            ChatClient chatClient,
            ChatClient policyChatClient,
            @Nullable JevModelRouter modelRouter,
            boolean completeCallPoliciesEnabled) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.policyChatClient = Objects.requireNonNull(policyChatClient, "policyChatClient must not be null");
        this.completeCallPoliciesEnabled = completeCallPoliciesEnabled;
        this.modelRouter = modelRouter;
    }

    public Flux<ChatClientResponse> stream(UUID userId, String conversationId, String userText) {
        if (this.completeCallPoliciesEnabled) {
            // Official TypeSafe answer policies evaluate the complete answer. Buffer the
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
        if (this.completeCallPoliciesEnabled) {
            // Judgment and guardrails require the complete answer. Use the policy path and expose
            // its evaluated answer as one SSE token; normal turns keep incremental streaming.
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
            UUID userId, String conversationId, String userText, boolean applyCompleteCallPolicies) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }

        String message = Objects.requireNonNull(userText, "userText must not be null");
        ChatClient selectedClient = applyCompleteCallPolicies ? this.policyChatClient : this.chatClient;
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
