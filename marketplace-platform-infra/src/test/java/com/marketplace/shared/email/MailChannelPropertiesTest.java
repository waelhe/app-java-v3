package com.marketplace.shared.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mail channel's sender-identity binding gate (the
 * GraphQlPropertiesTest pattern): the sender is operational contract —
 * incident #682 measured what a channel without one does (every submission
 * rejected at the MAIL FROM stage, the mail leg of every
 * {@code @ApplicationModuleListener} incomplete, the event-bus health
 * indicator honestly reporting the platform DOWN while liveness stayed UP).
 * A non-deployable sender therefore fails STARTUP at binding time, never as
 * a bounced submission at runtime.
 *
 * <p>The runner registers the properties through the same
 * {@code @EnableConfigurationProperties} shape
 * {@link EmailServiceAutoConfiguration} uses, so the guard exercises the
 * exact binding path the application takes when the channel is active.
 */
class MailChannelPropertiesTest {

    /** The minimal wiring under test — the properties record alone. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MailChannelProperties.class)
    static class MailPropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(MailPropertiesOnly.class));

    /**
     * Both provider-documented shapes bind: the bare address and the
     * display-name form — the strict {@code InternetAddress} parse keeps
     * the routable address available to the sender seam.
     */
    @Test
    void bothDocumentedSenderShapesBind() {
        runner.withPropertyValues("marketplace.mail.from=no-reply@marketplace.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MailChannelProperties.class).from())
                            .isEqualTo("no-reply@marketplace.example");
                });
        runner.withPropertyValues("marketplace.mail.from=Marketplace <no-reply@marketplace.example>")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MailChannelProperties.class).from())
                            .isEqualTo("Marketplace <no-reply@marketplace.example>");
                });
    }

    /**
     * A missing or blank sender fails STARTUP at binding time with the
     * operational message naming the key and the deployment variable —
     * the INV-2/D6 contract (an activated channel without an identity is
     * the measured root of incident #682, and it must never boot silently
     * again). The record invariant's exception rides the binding wrapper's
     * CAUSE chain, so the assertion walks to the root.
     */
    @Test
    void missingSenderFailsStartupWithTheOperationalMessage() {
        for (String value : new String[] {"", "   "}) {
            runner.withPropertyValues("marketplace.mail.from=" + value)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(chainMessagesOf(context)).contains("marketplace.mail.from")
                                .contains("MAIL_FROM");
                    });
        }
    }

    /**
     * A syntactically broken sender fails STARTUP at binding time as well —
     * configuration errors must read as configuration, not as runtime
     * bounces. The message carries the offending value and the two
     * documented shapes.
     */
    @Test
    void brokenSenderFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.mail.from=not-a-sender")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chainMessagesOf(context)).contains("marketplace.mail.from")
                            .contains("not-a-sender");
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
