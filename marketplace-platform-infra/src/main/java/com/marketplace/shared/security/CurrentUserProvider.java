package com.marketplace.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

public interface CurrentUserProvider {

    UUID getCurrentUserId(Authentication authentication);

    boolean isAdmin(Authentication authentication);

    /**
     * L34 (realestate systems plan §5 — lead capture): the optional-identity
     * seam for public write surfaces that accept both anonymous and
     * authenticated submissions ({@code POST /api/v1/listings/{id}/leads} —
     * "بلا مصادقة إلزامية"). Returns empty for the anonymous caller so the
     * surface can record the sender when a valid JWT is present without
     * forcing one; a presented-but-invalid token never reaches here (the
     * resource-server filter rejects it with 401 before the controller).
     *
     * <p>Default method on the interface deliberately: the contract lives
     * with its sibling ({@link #getCurrentUserId}), the strict semantics of
     * the existing method are unchanged for every authenticated-only
     * surface, and a presented JWT that does not resolve to a user row
     * still fails loudly through the strict path rather than silently
     * degrading to anonymous attribution.
     */
    default Optional<UUID> tryGetCurrentUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken)) {
            return Optional.empty();
        }
        return Optional.of(getCurrentUserId(authentication));
    }
}
