package com.marketplace.identity;

import com.marketplace.identity.spi.AddressFields;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the address book's owning
 * use-case guards — the one-default flush ordering (M6), the partial-update
 * merged-state contract (m4), and the IDOR wall's precedence (m10).
 */
@ExtendWith(MockitoExtension.class)
class AccountAddressServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ROW_ID = UUID.randomUUID();

    @Mock
    private AccountAddressRepository addressRepository;
    @Mock
    private UserRepository userRepository;

    private AccountAddressService service;

    private Authentication admin;
    private Authentication consumer;

    @BeforeEach
    void setUp() {
        service = new AccountAddressService(addressRepository, userRepository);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        consumer = new UsernamePasswordAuthenticationToken("member@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }

    private static AddressFields fullFields() {
        return new AddressFields("First", "Last", null, "1 Main St", null, "City", null,
                "12345", "US", null, null);
    }

    private static User userRow(UUID id, String subject) {
        // The explicit-id constructor: the public create() factory mints its
        // own random UUID, which would never match the stubbed pairs.
        return new User(id, subject, subject, "Name", UserRole.CONSUMER);
    }

    @Test
    void createSavesTheValidatedRowWithoutDefaults() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        when(addressRepository.countByUserId(USER_ID)).thenReturn(5L);

        service.createForCaller(consumer, fullFields(), null);

        verify(addressRepository).save(any(AccountAddress.class));
        verify(addressRepository, never()).flush();
    }

    @Test
    void createWithDefaultClearsThePreviousHolderThroughTheFlush() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        AccountAddress previous = AccountAddress.create(USER_ID);
        previous.applyFields(null, null, null, "Old", null, "OldCity", null, null, "US", null, null);
        previous.markDefaultShipping(true);
        when(addressRepository.findByUserIdAndDefaultShippingTrue(USER_ID))
                .thenReturn(List.of(previous));
        // The created row is captured from save(): the follow-up
        // set-default leg resolves it through the (id, owner) lookup the
        // owning service performs.
        AccountAddress[] saved = new AccountAddress[1];
        when(addressRepository.save(any(AccountAddress.class))).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        when(addressRepository.findByIdAndUserId(any(UUID.class), eq(USER_ID)))
                .thenAnswer(invocation -> Optional.ofNullable(saved[0]));

        service.createForCaller(consumer, fullFields(), "SHIPPING");

        // M6: the clear is written BEFORE the new default — the flush is
        // the working clears-before-set path, the partial unique index is
        // the backstop.
        assertThat(previous.isDefaultShipping()).isFalse();
        assertThat(saved[0].isDefaultShipping()).isTrue();
        verify(addressRepository).flush();
    }

    @Test
    void updateAcceptsAPartialPatchAgainstACompleteRow() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        UUID addressId = UUID.randomUUID();
        AccountAddress row = AccountAddress.create(USER_ID);
        row.applyFields("First", "Last", null, "1 Main St", null, "City", null, "12345", "US",
                null, null);
        when(addressRepository.findByIdAndUserId(addressId, USER_ID))
                .thenReturn(Optional.of(row));

        // m4: { city: "New City" } alone is a valid partial update — the
        // MERGED state carries the country and spine, not the patch.
        AddressFields patch = new AddressFields(null, null, null, null, null,
                "New City", null, null, null, null, null);
        var view = service.updateForCaller(consumer, addressId, patch);

        assertThat(view.city()).isEqualTo("New City");
        assertThat(view.country()).isEqualTo("US");
        assertThat(view.streetAddress1()).isEqualTo("1 Main St");
    }

    @Test
    void updateRejectsAMergedRowWithoutTheGeographicSpine() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        UUID addressId = UUID.randomUUID();
        AccountAddress row = AccountAddress.create(USER_ID);
        row.applyFields("First", "Last", null, "1 Main St", null, "City", null, "12345", "US",
                null, null);
        when(addressRepository.findByIdAndUserId(addressId, USER_ID))
                .thenReturn(Optional.of(row));

        // The patch clears the whole spine at once (blank = the explicit
        // clear of all three spine columns): the merged row has neither
        // city, street, nor postal code.
        AddressFields patch = new AddressFields(null, null, null, " ", null,
                " ", null, " ", null, null, null);
        assertThatThrownBy(() -> service.updateForCaller(consumer, addressId, patch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city, a street line or a postal code");
    }

    @Test
    void theIdorWallLeadsInputValidation() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        // m10: the wall resolves BEFORE validation — a garbage patch
        // against another account's address answers the 404, proving the
        // wall is the failure reason (not the input).
        when(addressRepository.findByIdAndUserId(OTHER_ROW_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateForCaller(consumer, OTHER_ROW_ID,
                new AddressFields(null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setDefaultClearsThenSetsWithTheFlushBetween() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        UUID addressId = UUID.randomUUID();
        AccountAddress target = AccountAddress.create(USER_ID);
        target.applyFields("First", "Last", null, "1 Main St", null, "City", null, "12345", "US",
                null, null);
        AccountAddress previous = AccountAddress.create(USER_ID);
        previous.markDefaultBilling(true);
        when(addressRepository.findByIdAndUserId(addressId, USER_ID))
                .thenReturn(Optional.of(target));
        when(addressRepository.findByUserIdAndDefaultBillingTrue(USER_ID))
                .thenReturn(List.of(previous));

        service.setCallerDefault(consumer, addressId, "BILLING");

        InOrder order = inOrder(addressRepository);
        // M6's exact ordering: clear the holders, FLUSH, then set the new
        // default.
        order.verify(addressRepository).findByUserIdAndDefaultBillingTrue(USER_ID);
        order.verify(addressRepository).flush();
        assertThat(previous.isDefaultBilling()).isFalse();
        assertThat(target.isDefaultBilling()).isTrue();
    }

    @Test
    void deleteRemovesTheRowAndAnswersTheView() {
        when(userRepository.findBySubject("member@example.com"))
                .thenReturn(Optional.of(userRow(USER_ID, "member@example.com")));
        UUID addressId = UUID.randomUUID();
        AccountAddress row = AccountAddress.create(USER_ID);
        row.applyFields("First", "Last", null, "1 Main St", null, "City", null, "12345", "US",
                null, null);
        when(addressRepository.findByIdAndUserId(addressId, USER_ID))
                .thenReturn(Optional.of(row));

        var view = service.deleteForCaller(consumer, addressId);

        verify(addressRepository).delete(row);
        assertThat(view.city()).isEqualTo("City");
    }

    @Test
    void theStaffReadLegIsAdminGated() {
        assertThatThrownBy(() -> service.find(consumer, OTHER_ROW_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(addressRepository.findById(OTHER_ROW_ID)).thenReturn(Optional.empty());
        assertThat(service.find(admin, OTHER_ROW_ID)).isEmpty();
    }
}
