package com.marketplace.messaging;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.MESSAGING)
public class MessagingController {

    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<Conversation> getConversation(@PathVariable UUID id) {
        return ResponseEntity.ok(messagingService.getConversation(id));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<PagedResponse<Message>> getMessages(
            @PathVariable UUID conversationId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(messagingService.getMessages(conversationId, pageable)));
    }

    @GetMapping("/conversations/{conversationId}/unread")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(@PathVariable UUID conversationId) {
        return ResponseEntity.ok(new UnreadCountResponse(messagingService.getUnreadCount(conversationId)));
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(@RequestBody CreateConversationRequest request) {
        Conversation conversation = messagingService.createConversation(
                request.participantA(), request.participantB(), request.bookingId());
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Message> sendMessage(@PathVariable UUID conversationId,
                                                @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messagingService.sendMessage(conversationId, request.senderId(), request.content()));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID conversationId,
                                            @RequestBody MarkReadRequest request) {
        messagingService.markAsRead(conversationId, request.userId());
        return ResponseEntity.ok().build();
    }

    public record CreateConversationRequest(
            @NotNull UUID participantA,
            @NotNull UUID participantB,
            UUID bookingId
    ) {}

    public record SendMessageRequest(
            @NotNull UUID senderId,
            @NotBlank String content
    ) {}

    public record MarkReadRequest(@NotNull UUID userId) {}

    public record UnreadCountResponse(long unreadCount) {}
}