package com.marketplace.app.ai;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatControllerTest {

    private final ObjectProvider<AiChatGateway> gatewayProvider = mock(ObjectProvider.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final Authentication authentication = mock(Authentication.class);
    private final AiChatController controller =
            new AiChatController(gatewayProvider, currentUserProvider, sessionService);

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void startsANewConversationAndUsesTheOfficialSessionService() {
        UUID conversationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        AiChatGateway gateway = mock(AiChatGateway.class);
        Session session = session(conversationId, userId, Map.of("title", "Where is the nearest park?"));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(sessionService.create(any(CreateSessionRequest.class))).thenReturn(session);
        when(gateway.answer(userId, conversationId.toString(), "Where is the nearest park?"))
                .thenReturn("There is a park nearby.");

        ResponseEntity<AiChatResponse> response = controller.chat(
                new AiChatRequest("Where is the nearest park?", null), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().conversationId()).isEqualTo(conversationId);
        assertThat(response.getBody().answer()).isEqualTo("There is a park nearby.");
        verify(sessionService).create(any(CreateSessionRequest.class));
        verify(gateway).answer(userId, conversationId.toString(), "Where is the nearest park?");
    }

    @Test
    void continuesOnlyAnExistingConversationOwnedByTheCaller() {
        UUID conversationId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        AiChatGateway gateway = mock(AiChatGateway.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(sessionService.findById(conversationId.toString()))
                .thenReturn(session(conversationId, userId, Map.of("title", "Existing")));
        when(gateway.answer(userId, conversationId.toString(), "continue")).thenReturn("continued");

        ResponseEntity<AiChatResponse> response =
                controller.chat(new AiChatRequest("continue", conversationId), authentication);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().answer()).isEqualTo("continued");
        verify(sessionService, never()).create(any(CreateSessionRequest.class));
    }

    @Test
    void hidesUnknownOrOtherUsersConversations() {
        UUID conversationId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(sessionService.findById(conversationId.toString())).thenReturn(null);

        assertThatThrownBy(() -> controller.messages(conversationId, 0, 50, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("AI conversation not found");
    }

    @Test
    void returnsUnavailableWithoutCreatingAnOrphanSessionWhenChatIsDisabled() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> controller.chat(new AiChatRequest("Hello", null), authentication))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("AI chat is not available");
        verify(sessionService, never()).create(any(CreateSessionRequest.class));
    }

    @Test
    void emitsConversationTokensAndDoneEventsThroughSse() {
        UUID conversationId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        AiChatGateway gateway = mock(AiChatGateway.class);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(sessionService.create(any(CreateSessionRequest.class)))
                .thenReturn(session(conversationId, userId, Map.of("title", "Hello")));
        when(gateway.streamAnswer(userId, conversationId.toString(), "Hello"))
                .thenReturn(Flux.just("Hello", " there"));

        List<ServerSentEvent<AiChatStreamEvent>> events = controller.stream(
                new AiChatRequest("Hello", null), authentication).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("conversation", "token", "token", "done");
        assertThat(events.get(1).data().text()).isEqualTo("Hello");
        assertThat(events.getLast().data().conversationId()).isEqualTo(conversationId);
    }

    @Test
    void listsOnlyActiveSessionsBelongingToTheCaller() {
        Session older = session(UUID.randomUUID(), userId, Map.of("title", "Older"));
        Session newer = session(UUID.randomUUID(), userId, Map.of("title", "Newer"));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(sessionService.findByUserId(userId.toString())).thenReturn(List.of(older, newer));

        ResponseEntity<List<AiConversationResponse>> response = controller.conversations(authentication);

        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).extracting(AiConversationResponse::title)
                .containsExactlyInAnyOrder("Older", "Newer");
    }

    private static Session session(UUID id, UUID owner, Map<String, Object> metadata) {
        return Session.builder()
                .id(id.toString())
                .userId(owner.toString())
                .createdAt(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(id.hashCode() & 0xff))
                .expiresAt(Instant.now().plus(Duration.ofDays(60)))
                .metadata(metadata)
                .build();
    }
}
