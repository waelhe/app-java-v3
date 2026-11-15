package com.marketplace.shared.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the production Resend HTTPS transport.
 *
 * <p>The API key is supplied only by the deployment secret store. It is never
 * included in validation messages or application logs.
 */
@ConfigurationProperties(prefix = "marketplace.mail.resend")
public record ResendProperties(String apiKey) {

    public ResendProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "marketplace.mail.resend.api-key is required when MARKETPLACE_MAIL_PROVIDER=resend; "
                            + "provision RESEND_API_KEY in the deployment secret store.");
        }
    }
}
