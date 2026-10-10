package com.marketplace.app.ai;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated HTTP contract for JSON chat, SSE streaming and session history.
 *
 * <p>Session metadata and the event history are managed by Spring AI Session's
 * SessionService/JDBC repository. Every read and deletion verifies ownership
 * against the authenticated marketplace user.</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Tag(name = "AI Chat", description = "Authenticated AI assistant conversations")
@Validated
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private static final int MAX_HISTORY_PAGE_SIZE = 100;
    private static final String TITLE_METADATA_KEY = "title";
    private static final String DEFAULT_TITLE = "New conversation";

    private final ObjectProvider<AiChatGateway> chatGatewayProvider;
    private final CurrentUserProvider currentUserProvider;
    private final SessionService sessionService;

    public AiChatController(
            ObjectProvider<AiChatGateway> chatGatewayProvider,
            CurrentUserProvider currentUserProvider,
            SessionService sessionService) {
        this.chatGatewayProvider = chatGatewayProvider;
        this.currentUserProvider = currentUserProvider;
        this.sessionService = sessionService;
    }

    @PostMapping("/ai/conversations")
    @RateLimiter(name = "aiChat")
    @Operation(
            summary = "Create an AI conversation",
            description = "Creates an empty conversation owned by the authenticated user. "
                    + "Pass its ID to the chat endpoint to send subsequent turns.")
    public ResponseEntity<AiConversationResponse> createConversation(
            @Valid @RequestBody(required = false) AiConversationCreateRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        String title = normalizeTitle(request == null ? null : request.title(), DEFAULT_TITLE);
        Session session = sessionService.create(CreateSessionRequest.builder()
                .userId(userId.toString())
                .metadata(TITLE_METADATA_KEY, title)
                .build());
        return ResponseEntity.status(201).body(toConversationResponse(session));
    }

    @PostMapping("/ai/chat")
    @RateLimiter(name = "aiChat")
    @Operation(
            summary = "Send a message to the AI assistant",
            description = "Requires authentication. Omitting conversationId creates a session; "
                    + "otherwise the ID must belong to the caller. Returns the complete answer as JSON.")
    public ResponseEntity<AiChatResponse> chat(
            @Valid @RequestBody AiChatRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        AiChatGateway gateway = requireChatGateway();
        Session session = resolveSession(userId, request.conversationId(), request.message());
        String answer = gateway.answer(userId, session.id(), request.message());
        return ResponseEntity.ok(new AiChatResponse(UUID.fromString(session.id()), answer));
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "aiChat")
    @Operation(
            summary = "Stream an AI answer using Server-Sent Events",
            description = "Emits conversation, token, done and failure events. Uses the same "
                    + "authenticated session and message contract as the JSON endpoint.")
    public Flux<ServerSentEvent<AiChatStreamEvent>> stream(
            @Valid @RequestBody AiChatRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        AiChatGateway gateway = requireChatGateway();
        Session session = resolveSession(userId, request.conversationId(), request.message());
        UUID conversationId = UUID.fromString(session.id());

        Flux<ServerSentEvent<AiChatStreamEvent>> opening =
                Flux.just(sse("conversation", new AiChatStreamEvent("conversation", conversationId, null, null)));
        Flux<ServerSentEvent<AiChatStreamEvent>> tokens = gateway
                .streamAnswer(userId, session.id(), request.message())
                .map(text -> sse("token", new AiChatStreamEvent("token", conversationId, text, null)));
        Flux<ServerSentEvent<AiChatStreamEvent>> completed =
                Flux.just(sse("done", new AiChatStreamEvent("done", conversationId, null, null)));

        return Flux.concat(opening, tokens, completed)
                .onErrorResume(error -> {
                    log.warn("AI stream failed for session {}", session.id(), error);
                    return Flux.just(sse("failure", new AiChatStreamEvent(
                            "failure", conversationId, null,
                            "The AI response could not be completed. Please retry.")));
                });
    }

    @GetMapping("/ai/conversations")
    @Operation(
            summary = "List the caller's AI conversations",
            description = "Returns only sessions owned by the authenticated user, newest-created first.")
    public ResponseEntity<List<AiConversationResponse>> conversations(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        List<AiConversationResponse> response = sessionService.findByUserId(userId.toString()).stream()
                .filter(this::notExpired)
                .sorted(Comparator.comparing(Session::createdAt).reversed())
                .map(this::toConversationResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ai/conversations/{conversationId}/messages")
    @Operation(
            summary = "Read a conversation's complete event history",
            description = "Returns chronological user, assistant and tool events, including archived "
                    + "events retained by Session compaction. Framework-generated synthetic summaries are "
                    + "omitted. Pages are zero-indexed and the page size is capped at 100.")
    public ResponseEntity<AiChatHistoryPage> messages(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_HISTORY_PAGE_SIZE) int size,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        Session session = requireOwnedSession(userId, conversationId);
        EventFilter currentPageFilter = EventFilter.builder()
                .page(page)
                .pageSize(size)
                .excludeSynthetic(true)
                .build();
        List<SessionEvent> pageEvents = sessionService.getEvents(session.id(), currentPageFilter);
        boolean hasMore = pageEvents.size() == size
                && !sessionService.getEvents(session.id(), EventFilter.builder()
                        .page(page + 1)
                        .pageSize(size)
                        .excludeSynthetic(true)
                        .build())
                        .isEmpty();
        List<AiChatMessageResponse> messages = pageEvents.stream()
                .map(this::toMessageResponse)
                .toList();
        return ResponseEntity.ok(new AiChatHistoryPage(
                conversationId, page, size, messages, hasMore));
    }

    @DeleteMapping("/ai/conversations/{conversationId}")
    @Operation(
            summary = "Delete an AI conversation",
            description = "Deletes the caller's session and associated event history through SessionService.")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable UUID conversationId,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        Session session = requireOwnedSession(userId, conversationId);
        sessionService.delete(session.id());
        return ResponseEntity.noContent().build();
    }

    private AiChatGateway requireChatGateway() {
        AiChatGateway gateway = chatGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ServiceUnavailableException(
                    "AI chat is not available in the current configuration.");
        }
        return gateway;
    }

    private Session resolveSession(UUID userId, UUID conversationId, String firstMessage) {
        if (conversationId != null) {
            return requireOwnedSession(userId, conversationId);
        }
        return sessionService.create(CreateSessionRequest.builder()
                .userId(userId.toString())
                .metadata(TITLE_METADATA_KEY, normalizeTitle(firstMessage, DEFAULT_TITLE))
                .build());
    }

    private Session requireOwnedSession(UUID userId, UUID conversationId) {
        Session session = sessionService.findById(conversationId.toString());
        if (session == null || !notExpired(session) || !userId.toString().equals(session.userId())) {
            // Avoid disclosing whether a session owned by another user exists.
            throw new ResourceNotFoundException("AI conversation not found");
        }
        return session;
    }

    private boolean notExpired(Session session) {
        return session.expiresAt() == null || session.expiresAt().isAfter(Instant.now());
    }

    private AiConversationResponse toConversationResponse(Session session) {
        Object title = session.metadata().get(TITLE_METADATA_KEY);
        return new AiConversationResponse(
                UUID.fromString(session.id()),
                title instanceof String value && !value.isBlank() ? value : DEFAULT_TITLE,
                session.createdAt(),
                session.expiresAt());
    }

    private AiChatMessageResponse toMessageResponse(SessionEvent event) {
        String text = event.getMessage().getText();
        List<AiChatToolCallResponse> toolCalls = event.getMessage() instanceof AssistantMessage assistant
                ? assistant.getToolCalls().stream()
                        .map(call -> new AiChatToolCallResponse(
                                call.id(), call.type(), call.name(), call.arguments()))
                        .toList()
                : List.of();
        List<AiChatToolResponse> toolResponses = event.getMessage() instanceof ToolResponseMessage tool
                ? tool.getResponses().stream()
                        .map(response -> new AiChatToolResponse(
                                response.id(), response.name(), response.responseData()))
                        .toList()
                : List.of();

        return new AiChatMessageResponse(
                event.getId(),
                event.getTimestamp(),
                event.getMessageType().name().toLowerCase(Locale.ROOT),
                text == null ? "" : text,
                event.isArchived(),
                event.hasToolCalls(),
                toolCalls,
                toolResponses);
    }

    private static String normalizeTitle(String proposed, String fallback) {
        if (proposed == null || proposed.isBlank()) {
            return fallback;
        }
        String title = proposed.strip();
        return title.substring(0, Math.min(120, title.length()));
    }

    private static ServerSentEvent<AiChatStreamEvent> sse(
            String eventName, AiChatStreamEvent payload) {
        return ServerSentEvent.<AiChatStreamEvent>builder(payload)
                .event(eventName)
                .build();
    }
}
