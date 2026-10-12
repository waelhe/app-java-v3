package com.marketplace.shared.security;

/**
 * Server-issued WebAuthn options paired with the one-time challenge handle used
 * to resolve the exact options when the Credential Manager response is returned.
 */
public record NativePasskeyChallengeResponse(String challengeId, String optionsJson) {
}
