package com.marketplace.shared.security;

import tools.jackson.databind.JsonNode;

/**
 * A WebAuthn ceremony's opaque, one-time challenge identifier and the
 * standards-shaped public-key options returned by the relying party.
 */
public record NativePasskeyChallengeResponse(String challengeId, JsonNode options) {
}
