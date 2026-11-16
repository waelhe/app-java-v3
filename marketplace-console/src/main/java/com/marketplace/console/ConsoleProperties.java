package com.marketplace.console;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * B-15 (compliance plan C.5 — «Remote Config بخصائص Boot»): the console
 * module's type-safe configuration (constructor binding, primed with an
 * empty {@link DefaultValue} section per the house binding rule —
 * AGENTS.md). This is the STATIC half of the design — bound once at
 * boot, exactly as Boot's external-config reference prescribes; the
 * OPERATIONAL values (the flags' enabled state, the remote config
 * values) are DATA rows read at request time, never boot-time
 * conditionals (the C.10 measured limit).
 *
 * <p>The one policy this record carries is the flags' fail-mode: what
 * {@code isEnabled} answers for a key with NO row — fail-open or
 * fail-closed. The conservative default is fail-closed ({@code false}:
 * an unregistered flag is OFF — a typo'd key can never silently enable
 * a capability), calibratable through the environment
 * ({@code MARKETPLACE_CONSOLE_FLAGS_DEFAULT_ENABLED=true} for the
 * migration window where flags land before their rows do).</p>
 */
@ConfigurationProperties(prefix = "marketplace.console")
public record ConsoleProperties(
        @DefaultValue Flags flags
) {

    public record Flags(
            /**
             * The answer for a flag key with no row: {@code false} =
             * fail-closed (the conservative default — an unregistered flag
             * is OFF), {@code true} = fail-open (the migration window).
             */
            @DefaultValue("false") boolean defaultEnabled
    ) {}
}
