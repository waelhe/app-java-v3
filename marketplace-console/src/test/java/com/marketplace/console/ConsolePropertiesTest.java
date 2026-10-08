package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-15 (compliance plan C.5 — «اختبار خصائص»): the console's
 * type-safe configuration binding, on the
 * {@code SearchPropertiesValidationTest}/{@code GlobalExceptionHandlerI18nTest}
 * house pattern — the runner registers the module's REAL
 * {@code ConsoleConfig} ({@code @EnableConfigurationProperties(
 * ConsoleProperties.class)} — the MessagingConfig house pattern) so the
 * guard exercises the exact wiring the application uses.
 *
 * <p>The two-half design's static half: the flags' fail-mode default —
 * fail-closed ({@code false}: an unregistered flag is OFF, a typo'd key
 * can never silently enable a capability), calibratable through the
 * environment for the migration window.</p>
 */
class ConsolePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(ConsoleConfig.class));

    @Test
    void absentSectionBindsToTheConservativeFailClosedDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConsoleProperties.class)
                    .flags().defaultEnabled()).isFalse();
        });
    }

    @Test
    void theEnvironmentCanOpenTheFailModeForTheMigrationWindow() {
        runner.withPropertyValues("marketplace.console.flags.default-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConsoleProperties.class)
                            .flags().defaultEnabled()).isTrue();
                });
    }
}
