package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.User;
import com.marketplace.app.graphql.generated.types.UserPermission;
import com.marketplace.identity.spi.UserAccountView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P1 (the canonical Saleor plan §5-P1): the transport mapping of the
 * identity owner's bounded account projection onto the Saleor-shaped
 * {@code User} wire type. This class owns ONLY transport mapping — the
 * global-ID encode and the permission rendering — never a business rule
 * (the same boundary discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, native UUID
 * behind it. {@code userPermissions} is the tested
 * {@link SaleorPermissionCatalog} mapping of the account's effective
 * roles. Temporal fields render as ISO-8601 strings per the surface's
 * recorded no-new-scalar decision.</p>
 */
@Component
public class UserGraphQlMapper {

    /** The GraphQL type tag of the Saleor User type — the codec's own contract. */
    static final String USER_TYPE = "User";

    private final SaleorGlobalIdCodec globalIdCodec;

    public UserGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one account projection onto the wire type.
     *
     * @param view the identity owner's bounded account projection
     * @return the Saleor-shaped user projection
     */
    public User toGraphQlUser(UserAccountView view) {
        List<UserPermission> permissions = new ArrayList<>();
        for (var code : SaleorPermissionCatalog.permissionsForRoles(view.effectiveRoles())) {
            permissions.add(UserPermission.newBuilder()
                    .code(code)
                    .name(SaleorPermissionCatalog.displayName(code))
                    .build());
        }
        return User.newBuilder()
                .id(globalIdCodec.encode(USER_TYPE, view.id()))
                .email(view.email())
                .isStaff(view.staff())
                .isActive(view.active())
                .dateJoined(view.dateJoined() == null ? null : view.dateJoined().toString())
                .userPermissions(permissions)
                .build();
    }
}
