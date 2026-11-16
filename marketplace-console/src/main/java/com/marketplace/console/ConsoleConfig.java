package com.marketplace.console;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * B-15 (compliance plan C.5): registers the console's
 * {@link ConsoleProperties} — the {@code MessagingConfig}/
 * {@code SearchConfig} house pattern ({@code @EnableConfigurationProperties}
 * on the module's own configuration class, the exact wiring the
 * application uses and the properties test exercises).
 */
@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleConfig {
}
