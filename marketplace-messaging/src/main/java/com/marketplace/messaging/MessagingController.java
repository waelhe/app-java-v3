package com.marketplace.messaging;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
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
    public ResponseEntity<ConversationResponse> getConversation(@PathVariable UUID id, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(conversationMapper.toResponse(messagingService.getConversation(id, userId)));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<PagedResponse<MessageResponse>> getMessages(
            @PathVariable UUID conversationId, Pageable pageable, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                messagingService.getMessages(conversationId, userId, pageable).map(messageMapper::toResponse)));
    }

    @GetMapping("/conversations/{conversationId}/unread")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(@PathVariable UUID conversationId, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(new UnreadCountResponse(messagingService.getUnreadCount(conversationId, userId)));
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> createConversation(@Valid @RequestBody CreateConversationRequest request,
                                                                   Authentication authentication) {
        UUID participantA = currentUserProvider.getCurrentUserId(authentication);
        Conversation conversation = messagingService.createConversation(participantA, request.bookingId());
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationMapper.toResponse(conversation));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable UUID conversationId,
                                                       @Valid @RequestBody SendMessageRequest request,
                                                       Authentication authentication) {
        UUID senderId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageMapper.toResponse(messagingService.sendMessage(conversationId, senderId, request.content())));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID conversationId,
                                           Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        messagingService.markAsRead(conversationId, userId);
        return ResponseEntity.ok().build();
    }

    public record CreateConversationRequest(
            @NotNull UUID bookingId
    ) {
    }

    public record SendMessageRequest(
            @NotBlank String content
    ) {
    }

    public record UnreadCountResponse(long unreadCount) {
    }
}
