package com.marketplace.messaging;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * R7 (comprehensive-review-ar fix plan §4, Wave 5 — the unified WebSocket
 * identity): the boundary's first-party principal — the token
 * authentication wearing the STABLE USER ID as its name.
 *
 * <p><b>Why a first-party type and not a re-minted
 * {@code JwtAuthenticationToken(jwt, authorities, name)}</b> (the official
 * name-carrying constructor): that constructor IS the official mechanism
 * for BUILDING a principal from a token (what
 * {@code JwtAuthenticationConverter} uses with a non-{@code sub} principal
 * claim) — but it cannot REPLACE one here. {@code AbstractAuthenticationToken.equals}
 * compares authorities/details/credentials/principal, NEVER the name
 * (bytecode-verified against spring-security-core 7.1.1), so the re-minted
 * token is value-equal to the lifted one; and
 * {@code MessageHeaderAccessor.setHeader} SKIPS value-equal writes
 * (bytecode-verified against spring-messaging 7.0.9: the
 * {@code nullSafeEquals} branch jumps past the map put) — the replacement
 * would be a silent no-op on the header. This type has identity equality
 * (no {@code equals} override), so the boundary translation always lands.
 *
 * <p><b>Delegation contract:</b> everything but the name delegates to the
 * wrapped token authentication — the authorities (the message layer's
 * authorization decisions keep working), the authenticated flag, the
 * credentials, the details, and the principal (the {@link org.springframework.security.oauth2.jwt.Jwt}
 * itself — anything that inspects it sees the real token). The
 * {@link #getName()} — the one property every downstream consumer on this
 * boundary reads (the notifications topic guard's
 * {@code #userId == authentication.name}, the conversation guard's
 * {@code UUID.fromString(auth.getName())}, the STOMP controller's
 * {@code UUID.fromString(principal.getName())}) — answers the stable user
 * id.
 */
final class WebSocketUserIdentity implements Authentication {

    private final Authentication delegate;
    private final String userId;

    WebSocketUserIdentity(Authentication delegate, String userId) {
        this.delegate = delegate;
        this.userId = userId;
    }

    /** The stable user id — the ONE translated property. */
    @Override
    public String getName() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public Object getCredentials() {
        return delegate.getCredentials();
    }

    @Override
    public Object getDetails() {
        return delegate.getDetails();
    }

    /** The wrapped token authentication's principal — the real Jwt. */
    @Override
    public Object getPrincipal() {
        return delegate.getPrincipal();
    }

    @Override
    public boolean isAuthenticated() {
        return delegate.isAuthenticated();
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        delegate.setAuthenticated(isAuthenticated);
    }

    /** The wrapped token authentication — for tests and boundary inspection. */
    Authentication delegate() {
        return delegate;
    }
}
