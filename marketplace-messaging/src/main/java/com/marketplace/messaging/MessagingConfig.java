package com.marketplace.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * L34 (realestate systems plan §5 — lead capture): registers the module's
 * {@link MessagingProperties} (the {@code MediaConfig} house pattern for
 * module-owned configuration).
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfig {
}
