package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevModelRouterTest {

    private static final String QUESTION = "model_tier";

    @Test
    void usesJevChoiceAndReturnsTheSelectedGoogleModel() {
        TypeSafeClient client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response(
                new ChoiceAnswer("FAST", Map.of("FAST", 0.92d, "CAPABLE", 0.08d), 0.92d)));

        JevModelRouter router = new JevModelRouter(
                client, mock(GoogleGenAiChatModel.class),
                "gemini-3.5-flash-lite", "gemini-3.5-flash", 0.65d);

        JevModelRouter.RouteDecision decision = router.route("Hello");

        assertThat(decision.effectiveTier()).isEqualTo(JevModelRouter.Tier.FAST);
        assertThat(decision.model()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(decision.confidence()).isEqualTo(0.92d);
        assertThat(decision.fallbackApplied()).isFalse();
        assertThat(((GoogleGenAiChatOptions) router.optionsFor(decision).build()).getModel())
                .isEqualTo("gemini-3.5-flash-lite");
    }

    @Test
    void fallsBackToTheCapableModelWhenJevConfidenceIsBelowTheConfiguredFloor() {
        TypeSafeClient client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response(
                new ChoiceAnswer("FAST", Map.of("FAST", 0.56d, "CAPABLE", 0.44d), 0.56d)));

        JevModelRouter router = new JevModelRouter(
                client, mock(GoogleGenAiChatModel.class),
                "gemini-3.5-flash-lite", "gemini-3.5-flash", 0.65d);

        JevModelRouter.RouteDecision decision = router.route("Compare two complicated rental contracts");

        assertThat(decision.selectedTier()).isEqualTo("FAST");
        assertThat(decision.effectiveTier()).isEqualTo(JevModelRouter.Tier.CAPABLE);
        assertThat(decision.model()).isEqualTo("gemini-3.5-flash");
        assertThat(decision.fallbackApplied()).isTrue();
        assertThat(decision.fallbackReason()).isEqualTo("confidence_below_threshold");
    }

    @Test
    void fallsBackToTheCapableModelWhenJevIsUnavailable() {
        TypeSafeClient client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenThrow(new TypeSafeException("offline"));

        JevModelRouter router = new JevModelRouter(
                client, mock(DeepSeekChatModel.class),
                "deepseek-flash", "deepseek-v4-pro", 0.65d);

        JevModelRouter.RouteDecision decision = router.route("Explain the account statement");

        assertThat(decision.effectiveTier()).isEqualTo(JevModelRouter.Tier.CAPABLE);
        assertThat(decision.model()).isEqualTo("deepseek-v4-pro");
        assertThat(decision.fallbackReason()).isEqualTo("jev_call_failed");
        assertThat(((DeepSeekChatOptions) router.optionsFor(decision).build()).getModel())
                .isEqualTo("deepseek-v4-pro");
    }

    @Test
    void sendsOnlyTheConfiguredTierOptionsToJev() {
        TypeSafeClient client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response(
                new ChoiceAnswer("CAPABLE", Map.of("FAST", 0.05d, "CAPABLE", 0.95d), 0.95d)));

        JevModelRouter router = new JevModelRouter(
                client, mock(GoogleGenAiChatModel.class),
                "gemini-3.5-flash-lite", "gemini-3.5-flash", 0.65d);

        router.route("Plan a multi-step migration.");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Question>> questions =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).systemOne(anyString(), questions.capture());
        assertThat(questions.getValue()).containsOnlyKeys(QUESTION);
        assertThat(((org.springaicommunity.typesafe.question.Choice) questions.getValue().get(QUESTION))
                .criteria()).containsOnlyKeys("FAST", "CAPABLE");
    }

    private static SystemOneResponse response(ChoiceAnswer answer) {
        return new SystemOneResponse("jev-latest", Map.<String, Answer>of(QUESTION, answer), null);
    }
}
