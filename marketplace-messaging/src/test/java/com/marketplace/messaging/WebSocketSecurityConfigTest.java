package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketSecurityConfigTest {

    private final AuthorizationManager<Message<?>> authorizationManager = new WebSocketSecurityConfig()
            .messageAuthorizationManager(MessageMatcherDelegatingAuthorizationManager.builder());

    @Test
    void rejectsUnauthenticatedSendToApplicationDestination() {
        AuthorizationResult decision = authorizationManager.authorize(unauthenticated(),
                message(SimpMessageType.MESSAGE, "/app/chat.sendMessage/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void rejectsUnauthenticatedSubscribeToTopicDestination() {
        AuthorizationResult decision = authorizationManager.authorize(unauthenticated(),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations.42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void acceptsAuthenticatedSendToApplicationDestination() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated(),
                message(SimpMessageType.MESSAGE, "/app/chat.sendMessage/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void acceptsAuthenticatedSubscribeToTopicDestination() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated(),
                message(SimpMessageType.SUBSCRIBE, "/topic/conversations.42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void acceptsAuthenticatedConnectWithoutDestination() {
        AuthorizationResult decision = authorizationManager.authorize(authenticated(),
                message(SimpMessageType.CONNECT, null));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    private static Supplier<TestingAuthenticationToken> authenticated() {
        return () -> new TestingAuthenticationToken("user", "password", "ROLE_USER");
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
