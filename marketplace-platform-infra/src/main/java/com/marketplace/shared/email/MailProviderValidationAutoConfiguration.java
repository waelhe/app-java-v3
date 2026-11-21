package com.marketplace.shared.email;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * CodeRabbit round 2 on this branch (Major — the disputed round-1 fix): the
 * provider legality guard, registered UNCONDITIONALLY so no channel
 * condition can skip it.
 *
 * <p><b>Why an auto-configuration of its own:</b> the Boot reference's own
 * condition-evaluation order is the root cause this class closes —
 * {@code @ConditionalOnProperty} on the two channel auto-configurations is
 * evaluated BEFORE any {@code @EnableConfigurationProperties} they carry
 * could bind, so a value that matches neither condition skipped the
 * validation entirely (the round-1 fix's measured gap). This class carries
 * NO condition: every application context that loads the auto-configuration
 * surface binds {@link MailProviderProperties} and validates the provider
 * value at STARTUP. The channel conditions then match only values that can
 * legally exist, and the {@code from} identity validation stays in
 * {@link MailChannelProperties} conditional on an ACTIVE channel (contexts
 * that deliberately run no mail keep booting without a sender).</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(MailProviderProperties.class)
public class MailProviderValidationAutoConfiguration {
}
