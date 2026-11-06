package com.marketplace.app.graphql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B24 (IDP-06 — the 2026-11-03 adversarial review, review-pr-806): the
 * identity family's shared authenticated-caller wall — the unit proof of
 * the exact shape the scan measured as missing on five fetchers (Address,
 * User, UserManagement, Customer, PermissionGroup): an
 * {@link AnonymousAuthenticationToken} answers {@code isAuthenticated()
 * == true}, so the null/authenticated pair alone ACCEPTS an anonymous
 * caller — the type check is the wall, and every identity fetcher now
 * shares this ONE authority.
 */
class AuthenticatedCallersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAnonymousTokenIsRefusedEvenThoughItAnswersAuthenticated() {
        // The scan's own measured shape: the anonymous token carries
        // isAuthenticated() == true — the weak pair accepted exactly this.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> AuthenticatedCallers.require("address operations"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("address operations");
    }

    @Test
    void anEmptyContextIsRefused() {
        assertThatThrownBy(() -> AuthenticatedCallers.require("user administration"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void anUnauthenticatedTokenIsRefused() {
        TestingAuthenticationToken unauthenticated =
                new TestingAuthenticationToken("subject", "credentials");
        unauthenticated.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);

        assertThatThrownBy(() -> AuthenticatedCallers.require("customer administration"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void aGenuinelyAuthenticatedCallerPassesThroughUnchanged() {
        TestingAuthenticationToken authenticated =
                new TestingAuthenticationToken("subject", "credentials", "ROLE_CONSUMER");
        SecurityContextHolder.getContext().setAuthentication(authenticated);

        assertThat(AuthenticatedCallers.require("permission-group administration"))
                .isSameAs(authenticated);
    }
}
