package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.PermissionEnum;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * P1 (the canonical Saleor plan §5-P1): the platform's server-side RBAC
 * rendered in Saleor's permission vocabulary — the divergence registry's
 * "Authentication and staff permissions" ADAPT row for the Users read
 * surface.
 *
 * <p><b>The mapping is the enforced one, not a decorative one:</b> the
 * Saleor operations this platform implements declare their Saleor
 * permission requirement, and the identity owner enforces the mapped
 * platform capability at the use-case boundary (ADMIN for
 * MANAGE_STAFF/MANAGE_USERS/MANAGE_ORDERS on {@code user}/{@code
 * staffUsers}). The catalog therefore lists only capabilities whose
 * owning operations exist — it GROWS with the implemented waves (the
 * P2 product wave adds the product-family codes for the provider's
 * own-scope administration), and a code the platform never grants stays
 * inert SDL vocabulary, never an emitted or enforced claim.</p>
 */
public final class SaleorPermissionCatalog {

    private SaleorPermissionCatalog() {
    }

    /**
     * The Saleor display name of each granted permission — the pinned
     * Dashboard's own rendered labels (the API-reference "Manage X."
     * verbose-name convention the upstream permission model carries).
     */
    private static final Map<PermissionEnum, String> DISPLAY_NAMES = Map.of(
            PermissionEnum.MANAGE_STAFF, "Manage staff.",
            PermissionEnum.MANAGE_USERS, "Manage users.",
            PermissionEnum.MANAGE_ORDERS, "Manage orders.",
            PermissionEnum.MANAGE_PRODUCTS, "Manage products."
    );

    /**
     * The platform role each granted permission is mapped to. ADMIN holds
     * the staff/user/order management set — the operations the P1 slice
     * itself enforces; PROVIDER holds product administration over its OWN
     * listings (the ownership-scoped storeProducts journey — Saleor's
     * global MANAGE_PRODUCTS scope difference is the recorded RBAC
     * divergence, not a silent equality); CONSUMER holds no administrative
     * capability.
     */
    private static final Map<String, Set<PermissionEnum>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of(
                    PermissionEnum.MANAGE_STAFF,
                    PermissionEnum.MANAGE_USERS,
                    PermissionEnum.MANAGE_ORDERS),
            "PROVIDER", Set.of(
                    PermissionEnum.MANAGE_PRODUCTS),
            "CONSUMER", Set.of()
    );

    /**
     * The permissions the account's effective roles grant — the union
     * across roles (a consumer with a granted administrative assignment
     * renders the assignment's capabilities too: the V188 effective
     * authorities truth, not just the primary role).
     *
     * @param effectiveRoles the account's effective platform roles
     *                       (primary + ACTIVE assignments)
     * @return the granted permission codes in stable enum order
     */
    public static Set<PermissionEnum> permissionsForRoles(Set<String> effectiveRoles) {
        // EnumSet iteration is the enum's own declaration order — the stable
        // wire order every consumer and test can pin.
        Set<PermissionEnum> granted = EnumSet.noneOf(PermissionEnum.class);
        if (effectiveRoles == null) {
            return granted;
        }
        for (String role : effectiveRoles) {
            granted.addAll(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return granted;
    }

    /**
     * The display name of a granted permission — the Saleor label the
     * Dashboard renders. An unknown code answers the code's own name (the
     * enum vocabulary is closed, so this is a defensive fallback only).
     *
     * @param code the permission code
     * @return the human-readable permission name
     */
    public static String displayName(PermissionEnum code) {
        return DISPLAY_NAMES.getOrDefault(code, code.name());
    }

    /**
     * The Saleor permission codes the implemented Users read operations
     * declare — the requirement the identity owner maps to the platform's
     * ADMIN capability. Declared once here so the SDL documentation, the
     * service enforcement and the tests cite one source.
     *
     * @return the required permission codes for {@code user}/{@code staffUsers}
     */
    public static Set<PermissionEnum> staffReadRequirement() {
        return Set.of(
                PermissionEnum.MANAGE_STAFF,
                PermissionEnum.MANAGE_USERS,
                PermissionEnum.MANAGE_ORDERS);
    }
}
