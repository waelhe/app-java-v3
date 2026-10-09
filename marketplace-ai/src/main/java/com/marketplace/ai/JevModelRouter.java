package com.marketplace.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.judge.JevConfidenceGate;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Selects a model tier with Jev's typed Choice API, then lets Spring AI call that
 * provider's official per-request ChatOptions. The configured Spring AI provider remains
 * authoritative; the router only selects a model supported by that provider.
 */
public final class JevModelRouter {

    private static final Logger log = LoggerFactory.getLogger(JevModelRouter.class);
    private static final String QUESTION = "model_tier";

    public enum Tier {
        FAST,
        CAPABLE
    }

    public record RouteDecision(
            String selectedTier,
            Tier effectiveTier,
            String model,
            double confidence,
            Map<String, Double> probabilities,
            boolean fallbackApplied,
            String fallbackReason) {
        public RouteDecision {
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
            Objects.requireNonNull(effectiveTier, "effectiveTier must not be null");
            Assert.hasText(model, "model must not be blank");
        }
    }

    private final TypeSafeClient typeSafeClient;
    private final Choice tierChoice;
    private final String fastModel;
    private final String capableModel;
    private final JevConfidenceGate confidenceGate;
    private final Function<String, ChatOptions.Builder<?>> optionsBuilder;

    public JevModelRouter(
            TypeSafeClient typeSafeClient,
            ChatModel configuredChatModel,
            String fastModel,
            String capableModel) {

        this.typeSafeClient = Objects.requireNonNull(typeSafeClient, "typeSafeClient must not be null");
        Objects.requireNonNull(configuredChatModel, "configuredChatModel must not be null");
        Assert.hasText(fastModel, "fastModel must not be blank");
        Assert.hasText(capableModel, "capableModel must not be blank");
        if (configuredChatModel instanceof GoogleGenAiChatModel) {
            this.optionsBuilder = model -> GoogleGenAiChatOptions.builder().model(model);
        }
        else if (configuredChatModel instanceof DeepSeekChatModel) {
            this.optionsBuilder = model -> DeepSeekChatOptions.builder().model(model);
        }
        else {
            throw new IllegalArgumentException("Jev model routing supports the configured Google GenAI "
                    + "and DeepSeek Spring AI chat providers only; found "
                    + configuredChatModel.getClass().getName());
        }

        this.fastModel = fastModel;
        this.capableModel = capableModel;
        this.confidenceGate = JevConfidenceGate.withDefaultFloor();
        this.tierChoice = Choice.builder()
                .instructions("""
                        Choose the least expensive configured model tier that can reliably answer the marketplace request in `user_request`.
                        Prefer FAST for greetings, simple factual questions, short definitions, or straightforward wording.
                        Choose CAPABLE for multi-step reasoning, comparisons, long instructions, or tasks where missing context could change the answer.
                        If `user_request` refers to prior turns (for example "that one" or "as above"), choose CAPABLE.
                        Select only from the declared options; do not answer the user's request.
                        """)
                .option(Tier.FAST.name(),
                        "Fast, lower-cost model for greetings, clear simple questions, concise explanations, "
                                + "and straightforward text transformations.")
                .option(Tier.CAPABLE.name(),
                        "More capable model for complex reasoning, detailed comparisons, multi-step requests, "
                                + "long instructions, or ambiguous follow-ups.")
                .build();
    }

    public RouteDecision route(String userText) {
        Assert.hasText(userText, "userText must not be blank");
        try {
            SystemOneResponse response = this.typeSafeClient.systemOne(
                    Map.of("user_request", userText), Map.of(QUESTION, this.tierChoice));
            ChoiceAnswer answer = response.choice(QUESTION);
            String selectedTier = answer.value();
            Map<String, Double> probabilities = answer.probabilities();
            double confidence = answer.confidence();

            Tier selected = parseTier(selectedTier);
            if (selected == null) {
                return fallback(selectedTier, confidence, probabilities, "jev_returned_unknown_tier");
            }
            if (this.confidenceGate.decide(answer) != JevConfidenceGate.Decision.EXECUTE) {
                return fallback(selectedTier, confidence, probabilities, "confidence_below_official_floor");
            }

            return new RouteDecision(selectedTier, selected, modelFor(selected), confidence,
                    probabilities, false, null);
        }
        catch (TypeSafeException | IllegalArgumentException ex) {
            // Routing is an optimization, not a prerequisite for chat availability. Keep
            // the request usable by falling back to the configured capable model.
            log.warn("Jev model routing failed; using the configured capable model ({})",
                    ex.getClass().getSimpleName());
            return fallback(null, 0.0d, Map.of(), "jev_call_failed");
        }
    }

    public ChatOptions.Builder<?> optionsFor(RouteDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return this.optionsBuilder.apply(decision.model());
    }

    private RouteDecision fallback(
            String selectedTier, double confidence, Map<String, Double> probabilities, String reason) {
        log.debug("Jev routing fallback applied: {}", reason);
        return new RouteDecision(selectedTier, Tier.CAPABLE, this.capableModel,
                Double.isFinite(confidence) ? confidence : 0.0d,
                probabilities, true, reason);
    }

    private String modelFor(Tier tier) {
        return tier == Tier.FAST ? this.fastModel : this.capableModel;
    }

    private static Tier parseTier(String selectedTier) {
        if (Tier.FAST.name().equals(selectedTier)) {
            return Tier.FAST;
        }
        if (Tier.CAPABLE.name().equals(selectedTier)) {
            return Tier.CAPABLE;
        }
        return null;
    }
}
