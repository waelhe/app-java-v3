package com.marketplace.messaging;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.MESSAGING, version = "1.0")
public class MessagingController {

    private final MessagingService messagingService;
    private final CurrentUserProvider currentUserProvider;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    public MessagingController(MessagingService messagingService, CurrentUserProvider currentUserProvider,
                               MessageMapper messageMapper, ConversationMapper conversationMapper) {
        this.messagingService = messagingService;
        this.currentUserProvider = currentUserProvider;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get one conversation", description = "A conversation the caller "
            + "participates in (booking-scoped chat).")
    public ResponseEntity<ConversationResponse> getConversation(@PathVariable UUID id, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(conversationMapper.toResponse(messagingService.getConversation(id, userId)));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "List a conversation's messages", description = "Paginated messages of "
            + "a conversation the caller participates in, oldest first.")
    public ResponseEntity<PagedResponse<MessageResponse>> getMessages(
            @PathVariable UUID conversationId, Pageable pageable, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                messagingService.getMessages(conversationId, userId, pageable).map(messageMapper::toResponse)));
    }

    @GetMapping("/conversations/{conversationId}/unread")
    @Operation(summary = "Count unread messages", description = "The caller's unread message "
            + "count in one conversation (badge polling endpoint).")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(@PathVariable UUID conversationId, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(new UnreadCountResponse(messagingService.getUnreadCount(conversationId, userId)));
    }

    @PostMapping("/conversations")
    @Operation(summary = "Open a conversation", description = "Creates (or returns) the "
            + "conversation for a booking — the single chat thread per booking.")
    public ResponseEntity<ConversationResponse> createConversation(@Valid @RequestBody CreateConversationRequest request,
                                                                   Authentication authentication) {
        UUID participantA = currentUserProvider.getCurrentUserId(authentication);
        Conversation conversation = messagingService.createConversation(participantA, request.bookingId());
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationMapper.toResponse(conversation));
    }

    /**
     * L44 (neighborhood community plan §5 — direct neighbor messages): the
     * direct channel on the same conversations table (D-N8 — booking-less,
     * {@code booking_id IS NULL}). 201 when this call opened it, 200 when the
     * pair's conversation already existed (idempotent — whichever side asks).
     *
     * <p>The named conservative instance {@code conversationCreate} (the L29
     * model: fail fast, 429 RL-001 — the same budget as the booking
     * conversation's write family; pre-declared in the L45 report
     * controller's own javadoc) bounds the open frequency; the pair's
     * uniqueness is V67's backstop, not a per-caller cap.
     */
    @PostMapping("/conversations/direct")
    @RateLimiter(name = "conversationCreate")
    @Operation(summary = "Open a direct conversation", description = "Opens (or returns) "
            + "the direct conversation with one neighbor — 201 when newly opened, "
            + "200 when it already exists (idempotent per pair).")
    public ResponseEntity<ConversationResponse> openDirectConversation(
            @Valid @RequestBody DirectConversationRequest request,
            Authentication authentication) {
        UUID requesterId = currentUserProvider.getCurrentUserId(authentication);
        MessagingService.DirectConversationOutcome outcome =
                messagingService.openDirectConversation(requesterId, request.recipientId());
        return ResponseEntity.status(outcome.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(conversationMapper.toResponse(outcome.conversation()));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "Send a message", description = "Sends a chat message to a conversation "
            + "the caller participates in; the other participant is notified.")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable UUID conversationId,
                                                       @Valid @RequestBody SendMessageRequest request,
                                                       Authentication authentication) {
        UUID senderId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageMapper.toResponse(messagingService.sendMessage(conversationId, senderId, request.content())));
    }

    @PostMapping("/conversations/{conversationId}/read")
    @Operation(summary = "Mark a conversation read", description = "Clears the caller's unread "
            + "counter for the conversation.")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID conversationId,
                                           Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        messagingService.markAsRead(conversationId, userId);
        return ResponseEntity.ok().build();
    }

    @Schema(description = "Conversation request: the booking the chat thread belongs to")
    public record CreateConversationRequest(
            @Schema(description = "The booking this conversation is about",
                    example = "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
            @NotNull UUID bookingId
    ) {
    }

    @Schema(description = "Direct conversation request: the neighbor to talk to")
    public record DirectConversationRequest(
            @Schema(description = "The recipient user id (the conversation's other participant)",
                    example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @NotNull UUID recipientId
    ) {
    }

    @Schema(description = "Outbound chat message")
    public record SendMessageRequest(
            @Schema(description = "Message body (plain text)", example = "Hi! Is early check-in possible?")
            @NotBlank String content
    ) {
    }

    @Schema(description = "Unread badge count")
    public record UnreadCountResponse(
            @Schema(description = "Unread messages for the caller in this conversation", example = "3")
            long unreadCount
    ) {
    }
}
