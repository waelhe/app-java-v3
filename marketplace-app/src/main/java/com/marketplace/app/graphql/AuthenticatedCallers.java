package com.marketplace.app.graphql;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * B24 (IDP-06 — the 2026-11-03 adversarial review, review-pr-806): the
 * shared authenticated-caller wall for the identity family's fetchers.
 *
 * <p>The scan's measured lesson (the TokenDataFetcher fix
 * {@code 0d1d97dc} landed first): an {@link AnonymousAuthenticationToken}
 * answers {@code isAuthenticated() == true}, so the null/authenticated
 * pair alone silently ACCEPTS an anonymous caller — the type check is
 * the only honest wall. Five more identity fetchers (Address, User,
 * UserManagement, Customer, PermissionGroup) carried the weak shape
 * while the pre-login gate's deny-by-default hid it; the wall now lives
 * HERE as one authority every identity fetcher shares, so the gate's
 * allowlist widening can never silently re-open the family.</p>
 */
final class AuthenticatedCallers {

    private AuthenticatedCallers() {
    }

    /**
     * The caller's authentication, refused unless it is a genuinely
     * authenticated, non-anonymous principal.
     *
     * @param surface the calling surface's own name for the refusal message
     */
    static Authentication require(String surface) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for " + surface);
        }
        return authentication;
    }
}
