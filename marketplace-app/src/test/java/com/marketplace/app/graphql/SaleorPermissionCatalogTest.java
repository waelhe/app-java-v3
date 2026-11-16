package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.PermissionEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 (the canonical Saleor plan §5-P1): the tested role matrix — the
 * platform's server-side RBAC rendered in Saleor's permission vocabulary.
 * The matrix is the enforced mapping (the identity owner enforces the
 * ADMIN capability for the staff-read requirement), so every change here
 * is a behavior change with companion integration coverage in
 * {@code UserGraphQlIntegrationTest}.
 */
class SaleorPermissionCatalogTest {

    @Test
    void adminHoldsTheStaffUserOrderManagementSetTheP1SliceEnforces() {
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of("ADMIN")))
                .containsExactlyInAnyOrder(
                        PermissionEnum.MANAGE_STAFF,
                        PermissionEnum.MANAGE_USERS,
                        PermissionEnum.MANAGE_ORDERS);
    }

    @Test
    void providerHoldsOwnershipScopedProductAdministrationOnly() {
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of("PROVIDER")))
                .containsExactly(PermissionEnum.MANAGE_PRODUCTS);
    }

    @Test
    void consumerHoldsNoAdministrativeCapability() {
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of("CONSUMER"))).isEmpty();
    }

    @Test
    void effectiveRolesUnionAcrossTheV188MultiRoleTruth() {
        // A consumer with a granted administrative assignment renders the
        // assignment's capabilities — the effective-authorities truth, not
        // just the primary role.
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of("CONSUMER", "PROVIDER")))
                .containsExactly(PermissionEnum.MANAGE_PRODUCTS);
    }

    @Test
    void unknownOrAbsentRolesGrantNothing() {
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of())).isEmpty();
        assertThat(SaleorPermissionCatalog.permissionsForRoles(null)).isEmpty();
        assertThat(SaleorPermissionCatalog.permissionsForRoles(Set.of("NOT_A_ROLE"))).isEmpty();
    }

    @Test
    void staffReadRequirementMatchesTheSaleorDeclaredPermissionSet() {
        assertThat(SaleorPermissionCatalog.staffReadRequirement())
                .containsExactlyInAnyOrder(
                        PermissionEnum.MANAGE_STAFF,
                        PermissionEnum.MANAGE_USERS,
                        PermissionEnum.MANAGE_ORDERS);
    }

    @Test
    void displayNamesCarryTheSaleorLabels() {
        assertThat(SaleorPermissionCatalog.displayName(PermissionEnum.MANAGE_STAFF))
                .isEqualTo("Manage staff.");
        assertThat(SaleorPermissionCatalog.displayName(PermissionEnum.MANAGE_USERS))
                .isEqualTo("Manage users.");
        assertThat(SaleorPermissionCatalog.displayName(PermissionEnum.MANAGE_ORDERS))
                .isEqualTo("Manage orders.");
        assertThat(SaleorPermissionCatalog.displayName(PermissionEnum.MANAGE_PRODUCTS))
                .isEqualTo("Manage products.");
    }
}
