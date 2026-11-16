package com.marketplace.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The WebSocket message-authorization rules (§7-а of the Spring Security
 * 7.1.1 compliance wave) — every rule of
 * {@code WebSocketAuthorizationConfig#messageAuthorizationManager()} driven
 * through the real {@link AuthorizationManager} against constructed STOMP
 * messages: the tokenless-CONNECT guard (null destination requires
 * authentication), the /app send guard, the own-notifications SpEL rule,
 * the conversation-participant guard, the deny-all close, and the
 * participant/non-participant/unknown-conversation matrix. The wiring those
 * rules run inside (the documented manual no-annotation configuration) is
 * pinned by {@code WebSocketSecurityWiringTest}; the end-to-end CONNECT
 * flow by the marketplace-app websocket integration tests.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketSecurityConfigTest {

    @Mock
    private ConversationRepository conversationRepository;

    private AuthorizationManager<Message<?>> authorizationManager;

    @BeforeEach
    void setUp() {
        var conversationManager = new ConversationSubscriptionAuthorizationManager(conversationRepository);
        authorizationManager = new WebSocketAuthorizationConfig(conversationManager)
                .messageAuthorizationManager();
    }

    @Test
    void rejectsUnauthenticatedSendToApplicationDestination() {
        AuthorizationResult decision = authorizationManager.authorize(unauthenticated(),
                message(SimpMessageType.MESSAGE, "/app/chat.sendMessage/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void rejectsUnauthenticatedSubscribeToNotificationTopicDestination() {
        AuthorizationResult decision = authorizationManager.authorize(unauthenticated(),
                message(SimpMessageType.SUBSCRIBE, "/topic/notifications/user"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void rejectsUnauthenticatedSubscribeToConversationTopicDestination() {
        AuthorizationResult decision = authorizationManager.authorize(unauthenticated(),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations/" + UUID.randomUUID()));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void acceptsAuthenticatedSendToApplicationDestination() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated("user"),
                message(SimpMessageType.MESSAGE, "/app/chat.sendMessage/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void acceptsAuthenticatedSubscribeToOwnNotificationTopic() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated("user"),
                message(SimpMessageType.SUBSCRIBE, "/topic/notifications/user"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void rejectsAuthenticatedSubscribeToForeignNotificationTopic() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated("user"),
                message(SimpMessageType.SUBSCRIBE, "/topic/notifications/other-user"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void acceptsParticipantSubscribeToConversationTopic() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var conversation = new Conversation(conversationId, UUID.randomUUID(), userId, UUID.randomUUID());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        AuthorizationResult decision = authorizationManager.authorize(authenticated(userId.toString()),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations/" + conversationId));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void rejectsNonParticipantSubscribeToConversationTopic() {
        UUID conversationId = UUID.randomUUID();
        var conversation = new Conversation(conversationId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        AuthorizationResult decision = authorizationManager.authorize(authenticated(UUID.randomUUID().toString()),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations/" + conversationId));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void rejectsSubscribeToUnknownConversationTopic() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        AuthorizationResult decision = authorizationManager.authorize(authenticated(UUID.randomUUID().toString()),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations/" + conversationId));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void acceptsAuthenticatedConnectWithoutDestination() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated("user"),
                message(SimpMessageType.CONNECT, null));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    private static Supplier<TestingAuthenticationToken> authenticated(String name) {
        return () -> new TestingAuthenticationToken(name, "password", "ROLE_USER");
    }

    private static Supplier<TestingAuthenticationToken> unauthenticated() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("anonymous", "password");
        token.setAuthenticated(false);
        return () -> token;
    }

    private static Message<byte[]> message(SimpMessageType messageType, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(messageType);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
