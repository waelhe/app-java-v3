package com.marketplace.identity.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * additive address-lifecycle seam of the {@code identity-spi} named
 * interface — the user-account address book behind the Saleor
 * Users-domain {@code accountAddress*}/{@code address*} mutations, the
 * {@code address} read root and the {@code User.addresses} read-back
 * (the D-ADDR-001 adopted owner).
 */
@NamedInterface("identity-spi")
public interface AddressManagementSpi {

    AddressView createForCaller(Authentication caller, AddressFields fields, String defaultType);

    AddressView updateForCaller(Authentication caller, UUID addressId, AddressFields fields);

    AddressView deleteForCaller(Authentication caller, UUID addressId);

    void setCallerDefault(Authentication caller, UUID addressId, String type);

    AddressView createForUser(Authentication caller, UUID userId, AddressFields fields);

    AddressView updateForUser(Authentication caller, UUID addressId, AddressFields fields);

    AddressView deleteForUser(Authentication caller, UUID addressId);

    void setUserDefault(Authentication caller, UUID addressId, String type, UUID userId);

    Optional<AddressView> find(Authentication caller, UUID addressId);

    List<AddressView> listByUser(UUID userId);

    Optional<AddressView> findDefault(UUID userId, String type);
}
