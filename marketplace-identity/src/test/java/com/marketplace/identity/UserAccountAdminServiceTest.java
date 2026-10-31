package com.marketplace.identity;

import com.marketplace.identity.spi.BulkRowResult;
import com.marketplace.identity.spi.UserWriteFields;
import com.marketplace.shared.api.ConflictException;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the staff-managed lifecycle's
 * owning guards — the administrative email move's complete login-identity
 * semantics (B8), the bulk error-policy split (B11: REJECT_EVERYTHING stays
 * all-or-nothing; IGNORE_FAILED runs each row in its own REQUIRES_NEW
 * transaction and answers per-row results), and the role walls.
 */
@ExtendWith(MockitoExtension.class)
class UserAccountAdminServiceTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final String CUSTOMER_EMAIL = "customer@example.com";
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final String NEW_EMAIL = "moved@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private UserService userService;
    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private StaffNotificationRecipientService recipientService;
    @Mock
    private AuthActionTokenService authActionTokenService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private UserAccountAdminService service;

    private Authentication admin;
    private User customerRow;
    private User staffRow;

    @BeforeEach
    void setUp() {
        service = new UserAccountAdminService(userRepository, userDetailsManager, userService,
                passwordResetService, recipientService, authActionTokenService, transactionManager);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        customerRow = User.create(CUSTOMER_EMAIL, CUSTOMER_EMAIL, "Customer", UserRole.CONSUMER);
        staffRow = User.create(STAFF_EMAIL, STAFF_EMAIL, "Staff", UserRole.ADMIN);
        // The per-row REQUIRES_NEW template executes its callback against
        // the mocked manager: a plain status + no-op commit.
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
    }

    private UserDetails loginRow(String username) {
        return org.springframework.security.core.userdetails.User.withUsername(username)
                .password("{bcrypt}hash")
                .authorities("ROLE_CONSUMER")
                .build();
    }

    @Test
    void createCustomerRegistersBothStores() {
        when(userService.register(eq(CUSTOMER_EMAIL), anyString(), any()))
                .thenReturn(customerRow);

        UUID id = service.createCustomer(admin,
                new UserWriteFields(CUSTOMER_EMAIL, "First", "Last", null), false);

        assertThat(id).isEqualTo(customerRow.getId());
        verify(passwordResetService, never()).requestReset(anyString());
    }

    @Test
    void createCustomerWithTheSetPasswordMailLeg() {
        when(userService.register(eq(CUSTOMER_EMAIL), anyString(), any()))
                .thenReturn(customerRow);

        service.createCustomer(admin,
                new UserWriteFields("Customer@Example.COM", "First", "Last", null), true);

        // requireEmail's lowercase discipline: the register and the mailed
        // set-password right both address the SAME normalized account.
        verify(userService).register(eq(CUSTOMER_EMAIL), anyString(), any());
        verify(passwordResetService).requestReset(CUSTOMER_EMAIL);
    }

    @Test
    void updateCustomerMovesTheWholeLoginIdentity() {
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.userExists(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(CUSTOMER_EMAIL)).thenReturn(loginRow(CUSTOMER_EMAIL));

        service.updateCustomer(admin, CUSTOMER_ID,
                new UserWriteFields(NEW_EMAIL, "NewName", null, null));

        // B8: the subject moved TOGETHER with the email, the V112 token
        // history purged BEFORE the old login row died, and the shared
        // aftermath (authorizations + caches) followed.
        assertThat(customerRow.getSubject()).isEqualTo(NEW_EMAIL);
        assertThat(customerRow.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(customerRow.getDisplayName()).isEqualTo("NewName");
        InOrder order = inOrder(authActionTokenService, userDetailsManager, userService);
        order.verify(userDetailsManager).createUser(any(UserDetails.class));
        order.verify(authActionTokenService).purgeHistory(CUSTOMER_EMAIL);
        order.verify(userDetailsManager).deleteUser(CUSTOMER_EMAIL);
        order.verify(userService).loginIdentityMoved(CUSTOMER_EMAIL);
    }

    @Test
    void updateCustomerRefusesATakenAddress() {
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.updateCustomer(admin, CUSTOMER_ID,
                new UserWriteFields(NEW_EMAIL, null, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(userDetailsManager, never()).deleteUser(anyString());
    }

    @Test
    void updateCustomerRefusesTheWrongRolesSurface() {
        when(userRepository.findById(STAFF_ID)).thenReturn(Optional.of(staffRow));

        assertThatThrownBy(() -> service.updateCustomer(admin, STAFF_ID,
                new UserWriteFields(null, "X", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteCustomerRidesTheStandingErasure() {
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));

        service.deleteCustomer(admin, CUSTOMER_ID);

        verify(userService).pseudonymizeAccount(CUSTOMER_ID,
                "customer deletion (dashboard)", "admin@example.com");
    }

    @Test
    void bulkUpdateRejectEverythingPropagatesTheFailure() {
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());
        when(userRepository.existsBySubject(anyString())).thenReturn(false);
        when(userDetailsManager.userExists(anyString())).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(anyString()))
                .thenReturn(loginRow(CUSTOMER_EMAIL));

        assertThatThrownBy(() -> service.bulkUpdateCustomers(admin,
                List.of(CUSTOMER_ID, unknown),
                List.of(new UserWriteFields(NEW_EMAIL, null, null, null),
                        new UserWriteFields(null, null, null, null)),
                false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void bulkUpdateIgnoreFailedAnswersPerRowResults() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.userExists(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(CUSTOMER_EMAIL))
                .thenReturn(loginRow(CUSTOMER_EMAIL));

        List<BulkRowResult> results = service.bulkUpdateCustomers(admin,
                List.of(CUSTOMER_ID, unknown),
                List.of(new UserWriteFields(NEW_EMAIL, "Moved", null, null),
                        new UserWriteFields(null, null, null, null)),
                true);

        // B11: the successful row commits in its own REQUIRES_NEW
        // transaction, the failing row records its per-row result, and the
        // call NEVER throws (the shared transaction is never marked
        // rollback-only by a caught row failure).
        assertThat(results).hasSize(2);
        assertThat(results.get(0).errorCode()).isNull();
        assertThat(results.get(1).errorCode()).isEqualTo("NOT_FOUND");
        assertThat(customerRow.getSubject()).isEqualTo(NEW_EMAIL);
    }

    @Test
    void bulkUpdateIgnoreFailedRollsBackTheFailingRowsPartialWrites() {
        // The first row's email move dies mid-flight: the login row exists
        // but the V112 purge path is reached only after createUser — a
        // ResourceNotFoundException from requireLogin answers the row's
        // per-row result and its REQUIRES_NEW rollback discards the
        // half-applied write.
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.userExists(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(CUSTOMER_EMAIL))
                .thenThrow(new org.springframework.security.core.userdetails
                        .UsernameNotFoundException("no login row"));

        List<BulkRowResult> results = service.bulkUpdateCustomers(admin,
                List.of(CUSTOMER_ID),
                List.of(new UserWriteFields(NEW_EMAIL, null, null, null)),
                true);

        assertThat(results.get(0).errorCode()).isEqualTo("NOT_FOUND");
        assertThat(customerRow.getSubject()).isEqualTo(CUSTOMER_EMAIL);
    }

    @Test
    void bulkSetActiveFlipsEveryKnownRow() {
        when(userRepository.existsById(CUSTOMER_ID)).thenReturn(true);
        when(userRepository.existsById(STAFF_ID)).thenReturn(false);

        int count = service.bulkSetActive(admin, List.of(CUSTOMER_ID, STAFF_ID), true);

        assertThat(count).isEqualTo(1);
        verify(userService).updateUserStatus(CUSTOMER_ID, "ENABLED",
                "dashboard bulk set-active", "admin@example.com");
    }

    @Test
    void createStaffRegistersTheAdminPopulation() {
        when(userService.registerStaff(eq(STAFF_EMAIL), anyString(), any(), anyBoolean()))
                .thenReturn(staffRow);

        UUID id = service.createStaff(admin,
                new UserWriteFields(STAFF_EMAIL, "Back", "Office", true), true);

        assertThat(id).isEqualTo(staffRow.getId());
        verify(passwordResetService).requestReset(STAFF_EMAIL);
    }

    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken(CUSTOMER_EMAIL, null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        assertThatThrownBy(() -> service.createCustomer(consumer,
                new UserWriteFields(CUSTOMER_EMAIL, null, null, null), false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteCustomer(consumer, CUSTOMER_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
