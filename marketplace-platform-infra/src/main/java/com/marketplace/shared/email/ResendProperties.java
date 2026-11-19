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
        // CodeRabbit round 1 (major — stability): when NO source resolves the
        // variable, the binding keeps the PRODUCTION placeholder text
        // "${RESEND_API_KEY}" literally (the framework's documented behavior
        // for an unresolvable default-less placeholder in some profiles) —
        // isBlank() passes, the app boots, and Resend rejects every request
        // with an invalid Authorization header at runtime. A literal
        // unresolved placeholder is therefore treated as ABSENT: startup
        // fails here, reading as configuration (the D6 fail-fast law),
        // never as a mid-incident 401 campaign.
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalArgumentException(
                    "marketplace.mail.resend.api-key is required when MARKETPLACE_MAIL_PROVIDER=resend; "
                            + "provision RESEND_API_KEY in the deployment secret store"
                            + (apiKey != null && apiKey.startsWith("${")
                                    ? " (the binding received the unresolved placeholder '"
                                    + apiKey
                                    + "' — the variable was NOT resolved by any source)"
                                    : "") + ".");
        }
        // CodeRabbit round 2 (major — security): a key carrying an ISO
        // control character (e.g. a trailing line feed from a copy-pasted
        // secret) passes isBlank() yet the HTTP header builder rejects it
        // when composing Authorization — and the thrown
        // IllegalArgumentException's message embeds the FULL header value,
        // so the key would reach the application logs through the send
        // path's error logging. Control characters are rejected HERE, at
        // binding time, and this message NEVER echoes the value.
        if (apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "marketplace.mail.resend.api-key contains ISO control characters — "
                            + "rejected at binding time (a control character in the "
                            + "Authorization header makes the HTTP client's error message "
                            + "embed the full key, leaking the secret into logs). Fix the "
                            + "RESEND_API_KEY value in the deployment secret store.");
        }
    }
}
