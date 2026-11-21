package com.marketplace.shared.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Resend credential's binding gate (the MailChannelPropertiesTest
 * pattern): the API key is a production secret, and the two failure shapes
 * that must read as CONFIGURATION at startup — never as a runtime 401
 * campaign or a log leak — are pinned here through the same
 * {@code @EnableConfigurationProperties} binding path
 * {@link ResendEmailSenderAutoConfiguration} takes.
 *
 * <ul>
 *   <li><b>The unresolved placeholder</b> (CodeRabbit round 1): when no
 *       source resolves {@code ${RESEND_API_KEY}}, the binding keeps the
 *       placeholder text literally — treated as ABSENT, startup fails.</li>
 *   <li><b>The control-character key</b> (CodeRabbit round 2): a key
 *       carrying an ISO control character (a trailing line feed from a
 *       copy-pasted secret) would pass {@code isBlank()} and reach the
 *       Authorization header, whose HTTP-client error message embeds the
 *       FULL key — leaking the secret into logs. Binding rejects it, and
 *       the operational message NEVER echoes the value.</li>
 * </ul>
 */
class ResendPropertiesTest {

    /** The minimal wiring under test — the properties record alone. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ResendProperties.class)
    static class ResendPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(ResendPropertiesOnly.class));

    /** A provisioned key binds and reaches the record untouched. */
    @Test
    void aProvisionedKeyBinds() {
        runner.withPropertyValues("marketplace.mail.resend.api-key=re_live_provisioned_key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ResendProperties.class).apiKey())
                            .isEqualTo("re_live_provisioned_key");
                });
    }

    /**
     * The unresolved production placeholder is ABSENT: startup fails at
     * binding time with the operational message naming the deployment
     * variable (the D6 fail-fast law — the app never boots to answer 401s).
     */
    @Test
    void theUnresolvedPlaceholderFailsStartupAsAbsent() {
        runner.withPropertyValues("marketplace.mail.resend.api-key=${RESEND_API_KEY}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chainMessagesOf(context)).contains("marketplace.mail.resend.api-key")
                            .contains("RESEND_API_KEY");
                });
    }

    /**
     * A key carrying a control character fails STARTUP at binding time, and
     * the failure message NEVER contains the key itself: the value would
     * otherwise leak into the logs exactly the way the HTTP client's header
     * error would have embedded it. The test key carries a trailing line
     * feed AND an embedded carriage return to pin both shapes.
     */
    @Test
    void aControlCharacterKeyFailsStartupWithoutEchoingTheValue() {
        String leakyKey = "re_live_leaky_key_with_\r_control\n";
        runner.withPropertyValues("marketplace.mail.resend.api-key=" + leakyKey)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String messages = chainMessagesOf(context);
                    assertThat(messages).contains("marketplace.mail.resend.api-key")
                            .contains("ISO control characters");
                    assertThat(messages).doesNotContain("re_live_leaky_key");
                });
    }

    /**
     * Every message in the startup failure's cause chain, concatenated —
     * the record invariant's operational text rides a binding wrapper, so
     * the assertions match anywhere in the chain, not only at the root.
     */
    private static String chainMessagesOf(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        StringBuilder messages = new StringBuilder();
        Throwable current = context.getStartupFailure();
        while (current != null) {
            messages.append(String.valueOf(current.getMessage())).append(" | ");
            current = current.getCause();
        }
        return messages.toString();
    }
}
