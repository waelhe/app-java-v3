package com.marketplace.identity.spi;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * P1 (the canonical Saleor plan §5-P1): the identity owner's bounded read
 * projection behind the Saleor Users-domain GraphQL operations
 * ({@code me}/{@code user}/{@code staffUsers}).
 *
 * <p>The projection carries only facts the {@code users} owner actually
 * owns — the same honesty rule every platform read projection follows
 * (the UserSummary precedent). Fields with no owning fact yet (first/last
 * name, addresses, orders, permission-group membership) are absent by
 * design: the GraphQL boundary records their absence in the divergence
 * registry rather than declaring fake fields.</p>
 *
 * @param id              the native UUID primary key (the GraphQL boundary
 *                        encodes it as a typed Saleor global ID — never a
 *                        raw UUID on the wire)
 * @param email           the account email; {@code null} when the account
 *                        has none (an OIDC account whose IdP dropped the
 *                        email scope, or a pseudonymized account)
 * @param staff           the platform's staff concept — the ADMIN primary
 *                        role (Saleor's {@code isStaff})
 * @param active          the honest login-identity fact the owner owns:
 *                        {@code false} once the account is pseudonymized
 *                        (the login identity is erased); the login-side
 *                        enabled-state read-back is a recorded follow-up
 * @param dateJoined      the account creation instant (Saleor's
 *                        {@code dateJoined})
 * @param lastModified    the row's last update instant (the
 *                        LAST_MODIFIED_AT keyset sort's own key)
 * @param effectiveRoles  the account's effective server-side roles — the
 *                        primary role unioned with the ACTIVE multi-role
 *                        assignments (the V188 effective-authorities
 *                        truth), which the GraphQL boundary renders as
 *                        Saleor permission codes
 */
public record UserAccountView(
        UUID id,
        String email,
        boolean staff,
        boolean active,
        Instant dateJoined,
        Instant lastModified,
        Set<String> effectiveRoles
) {
}
