package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Group;
import com.marketplace.app.graphql.generated.types.Permission;
import com.marketplace.identity.spi.PermissionGroupView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P1 (the canonical Saleor plan §5-P1): the transport mapping of the
 * identity owner's bounded permission-group projection onto the
 * Saleor-shaped {@code Group} wire type. This class owns ONLY transport
 * mapping — the global-ID encode and the permission rendering — never a
 * business rule (the same boundary discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * deterministic native handle behind it. {@code permissions} is the tested
 * {@link SaleorPermissionCatalog} rendering of the roles the group grants
 * (the single source of the platform-RBAC-to-Saleor-code mapping — the
 * identity view travels in the owner's own role vocabulary, exactly the
 * seam {@code UserAccountView.effectiveRoles} established for the account
 * read).</p>
 */
@Component
public class PermissionGroupGraphQlMapper {

    /** The GraphQL type tag of the Saleor Group type — the codec's own contract. */
    static final String GROUP_TYPE = "Group";

    private final SaleorGlobalIdCodec globalIdCodec;

    public PermissionGroupGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one permission-group projection onto the wire type.
     *
     * @param view the identity owner's bounded permission-group projection
     * @return the Saleor-shaped group projection
     */
    public Group toGraphQlGroup(PermissionGroupView view) {
        List<Permission> permissions = new ArrayList<>();
        for (var code : SaleorPermissionCatalog.permissionsForRoles(view.grantedRoles())) {
            permissions.add(Permission.newBuilder()
                    .code(code)
                    .name(SaleorPermissionCatalog.displayName(code))
                    .build());
        }
        return Group.newBuilder()
                .id(globalIdCodec.encode(GROUP_TYPE, view.id()))
                .name(view.name())
                .permissions(permissions)
                .userCanManage(view.userCanManage())
                .build();
    }
}
