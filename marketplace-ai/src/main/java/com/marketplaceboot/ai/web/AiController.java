package com.marketplaceboot.ai.web;

import com.marketplace.ai.AiChatGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated HTTP surface for AI chat.
 *
 * <p>The authenticated principal is part of the memory namespace so the client-controlled
 * conversation id cannot cross user boundaries.
 */
@RestController
@RequestMapping(path = "/api/v1/ai", version = "1.0")
public final class AiController {

    private final AiChatGateway aiChatGateway;

    public AiController(AiChatGateway aiChatGateway) {
        this.aiChatGateway = aiChatGateway;
    }

    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                             Principal principal) {
        String conversationId = principal.getName() + ":" + request.conversationId();
        return ResponseEntity.ok(aiChatGateway.chat(conversationId, request.message()));
    }

    public record ChatRequest(
            @NotNull UUID conversationId,
            @NotBlank @Size(max = 10000) String message
    ) {
    }
}
