package com.marketplace.messaging;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ConversationSubscriptionAuthorizationManager
        implements AuthorizationManager<MessageAuthorizationContext<?>> {

    private final ConversationRepository conversationRepository;

    public ConversationSubscriptionAuthorizationManager(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         MessageAuthorizationContext<?> context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        String conversationId = context.getVariables().get("conversationId");
        if (conversationId == null) {
            return new AuthorizationDecision(false);
        }

        try {
            UUID userId = UUID.fromString(auth.getName());
            return new AuthorizationDecision(
                    conversationRepository.findById(UUID.fromString(conversationId))
                            .map(conversation -> conversation.hasParticipant(userId))
                            .orElse(false));
        } catch (IllegalArgumentException e) {
            return new AuthorizationDecision(false);
        }
    }
}