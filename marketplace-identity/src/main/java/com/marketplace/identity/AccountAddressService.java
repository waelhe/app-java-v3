package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.identity.spi.AddressFields;
import com.marketplace.identity.spi.AddressView;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-ADDR-001 adopted decision): the owning use case for the user-account
 * address book behind the Saleor Users-domain address lifecycle
 * ({@code accountAddress*}/{@code address*} mutations, the {@code address}
 * read root, and the {@code User.addresses/defaultBillingAddress/
 * defaultShippingAddress} read-back).
 *
 * <p><b>Authorization at the owning boundary</b> (the frozen §4 contract):
 * the self-service {@code account*} legs resolve the TARGET account from
 * the caller's own authentication (the honest IDOR wall — a guessed
 * address/user pair answers the same 404 an unknown address would); the
 * staff-managed {@code address*} legs are ADMIN-enforced here (Saleor
 * requires MANAGE_USERS — the platform's mapped ADMIN capability, the RBAC
 * mapping divergence row). The impersonation argument Saleor reserves for
 * AUTHENTICATED_APP callers ({@code customerId}) stays rejected — the
 * platform has no app-impersonation grant to honor.</p>
 *
 * <p><b>The one-default-per-type invariant</b>: a create/update that sets
 * a default flag (or the explicit set-default legs) clears the previous
 * holder in the SAME transaction — at most one live row per account per
 * type, enforced under the account's row lock ordering (all writes load
 * the account first). Deletes clear dangling defaults; the account keeps
 * honest nulls when no default remains.</p>
 */
@Service
@Transactional
public class AccountAddressService implements com.marketplace.identity.spi.AddressManagementSpi {

    private static final Logger log = LoggerFactory.getLogger(AccountAddressService.class);

    private final AccountAddressRepository addressRepository;
    private final UserRepository userRepository;

    private static AddressView toView(AccountAddress row) {
        return new AddressView(row.getId(), row.getUserId(), row.getFirstName(), row.getLastName(),
                row.getCompanyName(), row.getStreetAddress1(), row.getStreetAddress2(),
                row.getCity(), row.getCityArea(), row.getPostalCode(), row.getCountry(),
                row.getCountryArea(), row.getPhone(),
                row.isDefaultShipping(), row.isDefaultBilling());
    }

        public AccountAddressService(AccountAddressRepository addressRepository,
                                 UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }


    // -------------------------------------------------------------------------
    // Self-service legs (accountAddress*) — the caller's own account only
    // -------------------------------------------------------------------------

    /**
     * {@code accountAddressCreate}: a new address on the CALLER's own
     * account. The optional {@code type} auto-assigns the new row as the
     * account's default of that type (Saleor's documented semantics).
     */
    @Observed(name = "account.address.create")
    public AddressView createForCaller(Authentication caller, AddressFields fields,
                                       String defaultType) {
        UUID userId = requireOwnAccount(caller);
        return createInternal(userId, fields, defaultType);
    }

    /**
     * {@code accountAddressUpdate}: the caller's own address — the IDOR
     * wall is the (id, caller's account) pair: another account's address
     * id answers the same 404 an unknown id would.
     */
    @Observed(name = "account.address.update")
    public AddressView updateForCaller(Authentication caller, UUID addressId, AddressFields fields) {
        UUID userId = requireOwnAccount(caller);
        return updateInternal(addressId, userId, fields);
    }

    /**
     * {@code accountAddressDelete}: the caller's own address; dangling
     * default flags die with the row.
     */
    @Observed(name = "account.address.delete")
    public AddressView deleteForCaller(Authentication caller, UUID addressId) {
        UUID userId = requireOwnAccount(caller);
        return deleteInternal(addressId, userId);
    }

    /**
     * {@code accountSetDefaultAddress}: the caller's own default flip —
     * clears the previous holder of the type in the same transaction.
     */
    @Observed(name = "account.address.set_default")
    public void setCallerDefault(Authentication caller, UUID addressId, String type) {
        UUID userId = requireOwnAccount(caller);
        setDefaultInternal(addressId, userId, type);
    }

    // -------------------------------------------------------------------------
    // Staff-managed legs (address*) — MANAGE_USERS, ADMIN-enforced here
    // -------------------------------------------------------------------------

    /** {@code addressCreate}: the ADMIN-managed variant over any account. */
    @Observed(name = "user.address.create")
    public AddressView createForUser(Authentication caller, UUID userId, AddressFields fields) {
        requireAdmin(caller);
        requireUser(userId);
        return createInternal(userId, fields, null);
    }

    /** {@code addressUpdate}: the ADMIN-managed variant. */
    @Observed(name = "user.address.update")
    public AddressView updateForUser(Authentication caller, UUID addressId, AddressFields fields) {
        requireAdmin(caller);
        AccountAddress row = requireAddress(addressId);
        return updateInternal(addressId, row.getUserId(), fields);
    }

    /** {@code addressDelete}: the ADMIN-managed variant. */
    @Observed(name = "user.address.delete")
    public AddressView deleteForUser(Authentication caller, UUID addressId) {
        requireAdmin(caller);
        AccountAddress row = requireAddress(addressId);
        return deleteInternal(addressId, row.getUserId());
    }

    /** {@code addressSetDefault}: the ADMIN-managed default flip. */
    @Observed(name = "user.address.set_default")
    public void setUserDefault(Authentication caller, UUID addressId, String type, UUID userId) {
        requireAdmin(caller);
        requireUser(userId);
        setDefaultInternal(addressId, userId, type);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /** The {@code address} read root — ADMIN-gated (Saleor's MANAGE_USERS). */
    @Transactional(readOnly = true)
    public Optional<AddressView> find(Authentication caller, UUID addressId) {
        requireAdmin(caller);
        return addressRepository.findById(addressId).map(com.marketplace.identity.AccountAddressService::toView);
    }

    /** The {@code User.addresses} read-back — the account's live address book. */
    @Transactional(readOnly = true)
    public List<AddressView> listByUser(UUID userId) {
        return addressRepository.findAllByUserIdOrderByCreatedAtAscIdAsc(userId)
                .stream().map(com.marketplace.identity.AccountAddressService::toView).toList();
    }

    /** The {@code User.defaultShippingAddress/defaultBillingAddress} read-back. */
    @Transactional(readOnly = true)
    public Optional<AddressView> findDefault(UUID userId, String type) {
        List<AccountAddress> rows = "SHIPPING".equalsIgnoreCase(type)
                ? addressRepository.findByUserIdAndDefaultShippingTrue(userId)
                : addressRepository.findByUserIdAndDefaultBillingTrue(userId);
        return rows.stream().findFirst().map(com.marketplace.identity.AccountAddressService::toView);
    }

    // -------------------------------------------------------------------------
    // Internals — the shared mutation core both legs ride
    // -------------------------------------------------------------------------

    private AddressView createInternal(UUID userId, AddressFields fields, String defaultType) {
        fields.validate();
        AccountAddress row = AccountAddress.create(userId);
        row.applyFields(fields.firstName(), fields.lastName(), fields.companyName(),
                fields.streetAddress1(), fields.streetAddress2(), fields.city(),
                fields.cityArea(), fields.postalCode(), normalizeCountry(fields.country()),
                fields.countryArea(), fields.phone());
        addressRepository.save(row);
        if (defaultType != null) {
            setDefaultInternal(row.getId(), userId, defaultType);
        } else {
            // Saleor's convenience: the FIRST address of an account becomes
            // its default for both types unless the caller said otherwise.
            if (addressRepository.countByUserId(userId) == 1) {
                row.markDefaultShipping(true);
                row.markDefaultBilling(true);
            }
        }
        log.info("Account address created: addressId={}, userId={}", row.getId(), userId);
        return toView(row);
    }

    /**
     * The merged-state address contract — the SAME rules
     * {@code AddressFields.validate()} enforces on a whole input, checked
     * against the row AFTER a partial apply (m4, the CodeRabbit
     * correctness round): {@code applyFields} treats null fields as
     * "keep", so the patch {@code { city: "X" }} is a valid update of a
     * row that already carries the country and spine — the previous
     * patch-side validation rejected it.
     */
    private static void validateMergedState(AccountAddress row) {
        if (row.getCountry() == null || !row.getCountry().matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException(
                    "A two-letter ISO 3166-1 country code is required for an address");
        }
        if (isBlank(row.getCity()) && isBlank(row.getStreetAddress1())
                && isBlank(row.getPostalCode())) {
            throw new IllegalArgumentException(
                    "An address requires at least a city, a street line or a postal code");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AddressView updateInternal(UUID addressId, UUID userId, AddressFields fields) {
        // The IDOR wall LEADS (m10, the CodeRabbit test-quality round): the
        // (id, userId) pair resolves before any input validation, so another
        // account's address id answers the same 404 an unknown id would —
        // regardless of what the patch carries.
        AccountAddress row = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No address " + addressId + " for the owning account"));
        // The partial apply (m4): null fields mean "keep", and the MERGED
        // state carries the address contract — not the patch.
        row.applyFields(fields.firstName(), fields.lastName(), fields.companyName(),
                fields.streetAddress1(), fields.streetAddress2(), fields.city(),
                fields.cityArea(), fields.postalCode(), normalizeCountry(fields.country()),
                fields.countryArea(), fields.phone());
        validateMergedState(row);
        log.info("Account address updated: addressId={}, userId={}", addressId, userId);
        return toView(row);
    }

    private AddressView deleteInternal(UUID addressId, UUID userId) {
        AccountAddress row = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No address " + addressId + " for the owning account"));
        addressRepository.delete(row);
        log.info("Account address deleted: addressId={}, userId={}", addressId, userId);
        return toView(row);
    }

    private void setDefaultInternal(UUID addressId, UUID userId, String type) {
        String normalized = normalizeType(type);
        AccountAddress row = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No address " + addressId + " for the owning account"));
        if ("SHIPPING".equals(normalized)) {
            addressRepository.findByUserIdAndDefaultShippingTrue(userId)
                    .forEach(previous -> previous.markDefaultShipping(false));
        } else {
            addressRepository.findByUserIdAndDefaultBillingTrue(userId)
                    .forEach(previous -> previous.markDefaultBilling(false));
        }
        // M6 (the CodeRabbit integrity round): the cleared holders FLUSH
        // before the new default writes. V248's
        // user_addresses_user_default_{shipping,billing}_live_uq partial
        // unique indexes reject the swap otherwise — Hibernate's action
        // queue runs INSERTs before UPDATEs (the create-with-default path)
        // and two dirty UPDATEs carry no guaranteed order. The flush is the
        // working clears-before-set path; the unique index is the backstop.
        addressRepository.flush();
        if ("SHIPPING".equals(normalized)) {
            row.markDefaultShipping(true);
        } else {
            row.markDefaultBilling(true);
        }
        log.info("Account default {} address set: addressId={}, userId={}", normalized, addressId, userId);
    }

    private static String normalizeType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("An address type (BILLING or SHIPPING) is required");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!"SHIPPING".equals(normalized) && !"BILLING".equals(normalized)) {
            throw new IllegalArgumentException("Unknown address type: " + type);
        }
        return normalized;
    }

    private static String normalizeCountry(String country) {
        return country == null ? null : country.trim().toUpperCase(Locale.ROOT);
    }

    private UUID requireOwnAccount(Authentication caller) {
        User user = userRepository.findBySubject(caller.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the authenticated subject: " + caller.getName()));
        return user.getId();
    }

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("No user " + userId);
        }
    }

    private AccountAddress requireAddress(UUID addressId) {
        return addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("No address " + addressId));
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Address administration requires the ADMIN capability");
        }
    }
}
