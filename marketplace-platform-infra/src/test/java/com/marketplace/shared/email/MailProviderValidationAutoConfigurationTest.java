package com.marketplace.shared.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeRabbit round 2 on this branch (Major — the disputed round-1 fix): the
 * provider guard's context test, through the ACTUAL auto-configurations the
 * application loads — never a direct {@code @EnableConfigurationProperties}
 * registration (the round-1 test's measured gap: registering the record
 * directly exercised a binding path the typo case never reaches, because
 * neither channel condition matches to register anything).
 *
 * <p>The runner carries all THREE real auto-configurations: the SMTP
 * channel, the Resend channel, and the unconditional
 * {@link MailProviderValidationAutoConfiguration} guard. The typo case must
 * fail STARTUP through the guard regardless of which channel conditions
 * match — that is the entire point of the guard's conditionless
 * registration.</p>
 */
class MailProviderValidationAutoConfigurationTest {

    /** The real auto-configuration surface the application loads. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EmailServiceAutoConfiguration.class,
                    ResendEmailSenderAutoConfiguration.class,
                    MailProviderValidationAutoConfiguration.class));

    /**
     * The review's own reproducer: a typo'd provider ({@code resned})
     * matches NEITHER channel condition, so the round-1 validation (inside
     * the conditionally-registered {@link MailChannelProperties}) never ran
     * and the context booted with no mail channel — password-reset events
     * completed as delivered with no email and no retry. The unconditional
     * guard now fails STARTUP through the real surface: the misconfiguration
     * reads as configuration (the D6 fail-fast law).
     */
    @Test
    void aTypoProviderFailsStartupThroughTheRealAutoConfigurations() {
        runner.withPropertyValues("marketplace.mail.provider=resned")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chainMessagesOf(context)).contains("resned")
                            .contains("smtp, resend")
                            .contains("MARKETPLACE_MAIL_PROVIDER");
                });
    }

    /**
     * The absent provider keeps the documented default: the guard
     * normalizes to {@code smtp} and the context boots — the matchIfMissing
     * shape on {@link EmailServiceAutoConfiguration} stays intact (no
     * {@code JavaMailSender} bean here, so the SMTP channel itself stays
     * legitimately inactive; the GUARD is what must not fail).
     */
    @Test
    void anAbsentProviderBootsOnTheSmtpDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MailProviderProperties.class).provider()).isEqualTo("smtp");
        });
    }

    /**
     * Both legal transports pass the guard. The SMTP shape needs no channel
     * bean here (the {@code JavaMailSender} condition stays the channel's
     * own business — the GUARD is what must not fail). The Resend shape
     * matches its auto-configuration's property condition, so it carries
     * the full legal channel shape: the sender identity, a provisioned
     * (dummy) API key — {@link ResendProperties}'s own D6 fail-fast — and
     * the two collaborating beans its sender constructor needs.
     */
    @Test
    void bothLegalTransportsPassTheGuard() {
        runner.withPropertyValues("marketplace.mail.provider=smtp")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues(
                        "marketplace.mail.provider=resend",
                        "marketplace.mail.from=no-reply@marketplace.example",
                        "marketplace.mail.resend.api-key=re_dummy_key_for_the_binding_guard_test")
                .withBean(org.springframework.web.client.RestClient.Builder.class,
                        org.springframework.web.client.RestClient::builder)
                .withBean(org.thymeleaf.TemplateEngine.class, org.thymeleaf.TemplateEngine::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The {@code from} identity validation stays CONDITIONAL on an active
     * channel (the round-2 prescription): with no channel bean at all (no
     * {@code JavaMailSender} in the context), a missing sender keeps
     * booting — contexts that deliberately run no mail are legal. The
     * sender-identity gate itself is {@link MailChannelPropertiesTest}'s
     * own contract.
     */
    @Test
    void theSenderIdentityStaysConditionalOnAnActiveChannel() {
        runner.withPropertyValues("marketplace.mail.provider=smtp")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MailChannelProperties.class);
                });
    }

    /**
     * Every message in the startup failure's cause chain, concatenated —
     * {@link MailChannelPropertiesTest}'s own helper verbatim: the record
     * invariant's operational text rides a binding wrapper, so the
     * assertions match anywhere in the chain, not only at the root.
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
