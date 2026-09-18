package com.marketplace.ai;

import com.marketplace.shared.api.ServiceUnavailableException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The single entry point to the AI chat capability — a thin, provider-neutral
 * gate over the active Spring AI {@code ChatModel}.
 *
 * <p>Provider wiring is entirely Spring AI's official auto-configuration: the
 * {@code spring-ai-starter-model-google-genai} and
 * {@code spring-ai-starter-model-deepseek} starters share this classpath and
 * exactly one provider activates behind the documented top-level selector
 * {@code spring.ai.model.chat} ({@code google-genai | deepseek | none}) —
 * "Enabling and disabling of the chat auto-configurations are now configured
 * via top level properties with the prefix {@code spring.ai.model.chat}"
 * (Spring AI reference 2.0.1: {@code api/chat/google-genai-chat.html} and
 * {@code api/chat/deepseek-chat.html}, "Chat Properties").
 *
 * <p>Why the optional {@code ChatModel} and not the auto-configured
 * {@code ChatClient.Builder}: the builder bean's factory method requires a
 * {@code ChatModel}, so with the selector at {@code none} its definition
 * exists but is un-instantiable — touching it eagerly fails the caller while
 * the context itself boots cleanly (measured: {@code getBeansOfType} on the
 * builder throws {@code UnsatisfiedDependencyException} with zero models
 * bound). Resolving the optional model instead keeps the OFF state honest:
 * nothing AI-related is ever instantiated. The client is built with the
 * documented {@code ChatClient.create} factory (reference 2.0.1:
 * {@code api/chatclient.html}).
 *
 * <p>No client-level customizers are registered anywhere in this codebase, so
 * no observability is bypassed by building the client here; call-site
 * observation stays at the consuming services' {@code @Observed} boundary
 * per the house commands-not-reads policy (the observation pin test guards
 * it). Client-level wiring arrives with the first consuming capability if
 * one ever needs it — no speculative abstraction.
 *
 * <p>Graceful-off contract (the PSP/MAIL house gate — "the capability is OFF,
 * not broken"): the default selector value is {@code none}, so no
 * {@code ChatModel} binds and the context still boots cleanly.
 * {@link #available()} reports the state and {@link #chat} answers 503
 * SU-001 while off — never a startup failure, never a health contribution.
 * The 503 detail reports the selector's <em>actual configured value</em> (read
 * from the {@link Environment} — an unmatched value binds no model either,
 * so the message must never assume {@code none}); selecting a provider
 * without its key is a broken selection, not an off state, and fails at the
 * provider's own startup assertion.
 */
@Service
public class AiChatGateway {

    /**
     * The Spring AI provider selector this gate mirrors. Single source of
     * truth stays in {@code application.yml} ({@code spring.ai.model.chat});
     * this constant only names it in the 503 detail.
     */
    public static final String CHAT_SELECTOR_PROPERTY = "spring.ai.model.chat";

    private final ObjectProvider<ChatModel> models;
    private final Environment environment;

    public AiChatGateway(ObjectProvider<ChatModel> models, Environment environment) {
        this.models = models;
        this.environment = environment;
    }

    /**
     * Whether a chat provider is currently bound (selector picked a provider
     * whose auto-configuration produced a {@code ChatModel}).
     */
    public boolean available() {
        return models.getIfAvailable() != null;
    }

    /**
     * Sends one user turn to the bound provider and returns its text answer.
     *
     * @throws ServiceUnavailableException (503 SU-001) when the capability is
     *         OFF — no provider selected or bound. The detail names the
     *         selector property with its actually configured value (or that
     *         it is unset), never an assumed one.
     */
    public String chat(String userText) {
        ChatModel model = models.getIfAvailable();
        if (model == null) {
            throw new ServiceUnavailableException(
                    "The AI chat capability is OFF: no ChatModel is bound for " + CHAT_SELECTOR_PROPERTY
                            + selectorState() + " — select a provider (google-genai | deepseek) with its API key to turn it on");
        }
        return ChatClient.create(model).prompt().user(userText).call().content();
    }

    /**
     * The selector's configured value as reported in the 503 detail — the
     * measured fact from the {@link Environment}, not an assumption: any
     * value other than a configured provider (including {@code none} or an
     * unmatched string, or no value at all) leaves no {@code ChatModel} bound.
     */
    private String selectorState() {
        String selector = environment.getProperty(CHAT_SELECTOR_PROPERTY);
        return selector == null ? " (unset)" : "=" + selector;
    }
}
