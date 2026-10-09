package com.marketplace.app.ai;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public HTTP adapter for the provider-neutral Spring AI chat capability.
 *
 * <p>The endpoint is authenticated by the application's resource-server
 * security chain. User identity is resolved from that authentication and is
 * never accepted from the request body. The chat gateway additionally scopes
 * Spring AI conversation memory by this user ID.</p>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Tag(name = "AI Chat", description = "Authenticated AI assistant conversations")
public class AiChatController {

    private final ObjectProvider<AiChatGateway> chatGatewayProvider;
    private final CurrentUserProvider currentUserProvider;

    public AiChatController(
            ObjectProvider<AiChatGateway> chatGatewayProvider,
            CurrentUserProvider currentUserProvider) {
        this.chatGatewayProvider = chatGatewayProvider;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/ai/chat")
    @RateLimiter(name = "aiChat")
    @Operation(
            summary = "Send a message to the AI assistant",
            description = "Requires an authenticated user. Omit conversationId to start a conversation; "
                    + "reuse the returned conversationId for later turns. The server derives memory scope "
                    + "from the authenticated user, so a supplied conversation ID cannot cross user boundaries. "
                    + "Messages must contain 1–4,000 non-whitespace characters.")
    public ResponseEntity<AiChatResponse> chat(
            @Valid @RequestBody AiChatRequest request,
            Authentication authentication) {

        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        AiChatGateway gateway = chatGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ServiceUnavailableException(
                    "AI chat is not available in the current configuration.");
        }

        UUID conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID();

        String answer = gateway.answer(userId, conversationId.toString(), request.message());
        return ResponseEntity.ok(new AiChatResponse(conversationId, answer));
    }
}
