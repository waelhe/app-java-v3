package com.marketplace.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.expression.MessageExpressionAuthorizationManager;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    private final ConversationSubscriptionAuthorizationManager conversationSubscriptionAuthorizationManager;

    public WebSocketSecurityConfig(ConversationSubscriptionAuthorizationManager conversationSubscriptionAuthorizationManager) {
        this.conversationSubscriptionAuthorizationManager = conversationSubscriptionAuthorizationManager;
    }

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        messages
                .nullDestMatcher().authenticated()
                .simpDestMatchers("/app/**").authenticated()
                .simpSubscribeDestMatchers("/topic/notifications/{userId}")
                    .access(new MessageExpressionAuthorizationManager("#userId == authentication.name"))
                .simpSubscribeDestMatchers("/topic/conversations/{conversationId}")
                    .access(conversationSubscriptionAuthorizationManager)
                .simpSubscribeDestMatchers("/topic/**").denyAll()
                .anyMessage().denyAll();
        return messages.build();
    }
}
