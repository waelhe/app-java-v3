package com.marketplace.identity.spi;

import java.util.Set;
import java.util.UUID;

/**
 * P1 (the canonical Saleor plan §5-P1 — the Saleor {@code permissionGroups}/
 * {@code permissionGroup} read roots): the identity owner's bounded
 * permission-group projection — one element of the platform's
 * permission-group collection rendered behind the Saleor {@code Group} wire
 * type.
 *
 * <p><b>The honest mapping (the bounded-projection method the User read
 * slice established):</b> the platform owns no stored group table — its
 * permission-group collection IS the closed {@code UserRole} vocabulary
 * (CONSUMER/PROVIDER/ADMIN — the V187 CHECK's own list): each role is a
 * named bundle of granted capabilities that accounts hold as the primary
 * role or as ACTIVE multi-role assignments. The projection therefore
 * carries only facts the identity owner actually owns:</p>
 * <ul>
 *   <li>{@code id} — the group's stable native handle: the deterministic
 *       name-based UUID (RFC 4122 v3) the owner derives from the role name
 *       (see the identity service's derivation, documented there). The
 *       GraphQL boundary encodes it as a typed {@code Group} global ID —
 *       never a raw UUID on the wire.</li>
 *   <li>{@code name} — the role's display name (the humanized
 *       closed-vocabulary name; no separate group-name store exists).</li>
 *   <li>{@code grantedRoles} — the platform roles whose capabilities the
 *       group grants, in the owner's own vocabulary — the SAME seam
 *       {@code UserAccountView.effectiveRoles} uses. The GraphQL boundary
 *       renders these as Saleor permission codes through the tested
 *       {@code SaleorPermissionCatalog} (the single source of the
 *       platform-RBAC-to-Saleor-code rendering — the mapping never
 *       duplicates into this module).</li>
 *   <li>{@code userCanManage} — the honest manage-right fact:
 *       {@code true} exactly when the authenticated caller holds the
 *       platform ADMIN capability (the same role whose grant/revoke
 *       commands the identity module's administrative surface gates).</li>
 * </ul>
 *
 * <p>Fields with no owning fact (the group's member list, channel access)
 * are absent by design: the GraphQL boundary records their absence in the
 * divergence registry rather than declaring fake fields.</p>
 */
public record PermissionGroupView(
        UUID id,
        String name,
        Set<String> grantedRoles,
        boolean userCanManage
) {
}
