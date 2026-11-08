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
        // The 2026-11-08 #810 round-5 review: the creation rides the
        // enabled-aware overload (a null isActive keeps the A-04 hold).
        when(userService.register(eq(CUSTOMER_EMAIL), anyString(), any(), eq(false)))
                .thenReturn(customerRow);

        UUID id = service.createCustomer(admin,
                new UserWriteFields(CUSTOMER_EMAIL, "First", "Last", null), false);

        assertThat(id).isEqualTo(customerRow.getId());
        verify(passwordResetService, never()).requestReset(anyString());
    }

    @Test
    void createCustomerWithTheSetPasswordMailLeg() {
        when(userService.register(eq(CUSTOMER_EMAIL), anyString(), any(), eq(false)))
                .thenReturn(customerRow);

        service.createCustomer(admin,
                new UserWriteFields("Customer@Example.COM", "First", "Last", null), true);

        // requireEmail's lowercase discipline: the register and the mailed
        // set-password right both address the SAME normalized account.
        verify(userService).register(eq(CUSTOMER_EMAIL), anyString(), any(), eq(false));
        verify(passwordResetService).requestReset(CUSTOMER_EMAIL);
    }

    /**
     * The 2026-11-08 #810 round-5 review (Minor · Functional Correctness):
     * the requested activation rides the creation — an explicitly
     * requested {@code isActive=true} answers a BORN-ENABLED login (the
     * staff twin's law), while the A-04 hold stays the default the null /
     * false cases keep.
     */
    @Test
    void createCustomerHonorsTheRequestedActivation() {
        when(userService.register(eq(CUSTOMER_EMAIL), anyString(), any(), eq(true)))
                .thenReturn(customerRow);

        UUID id = service.createCustomer(admin,
                new UserWriteFields(CUSTOMER_EMAIL, "First", "Last", true), false);

        assertThat(id).isEqualTo(customerRow.getId());
        verify(userService).register(eq(CUSTOMER_EMAIL), anyString(), any(), eq(true));
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
    void bulkUpdateIgnoreFailedKeepsTheRawIaeMessageInternal() {
        // The 2026-11-03 preemptive review: the broad IllegalArgumentException
        // catch cannot tell the use case's authored validation from a
        // library-raised IAE — the wire payload answers the platform's own
        // constant refusal (the SaleorMutationErrors.safeMessage law), and
        // the raw text rides DEBUG only.
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));

        List<BulkRowResult> results = service.bulkUpdateCustomers(admin,
                List.of(CUSTOMER_ID),
                List.of(new UserWriteFields("not-an-email", null, null, null)),
                true);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).errorCode()).isEqualTo("INVALID");
        assertThat(results.get(0).errorMessage()).isEqualTo("The row input was rejected");
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

    /**
     * B23 (IDP-05 — the 2026-11-03 adversarial review, review-pr-806): the
     * FULL admin sweep — every public leg of UserAccountAdminService, invoked with a
     * CONSUMER caller and inert arguments, must answer Spring Security's
     * own AccessDeniedException BEFORE any argument is touched (the
     * programmatic requireAdmin gate leads every leg; a future leg that
     * forgets it fails here on its first null-argument symptom instead).
     * A primitive parameter cannot carry null — Method.invoke would fail
     * its unboxing BEFORE the leg's gate could answer — so primitive slots
     * ride their zero-value default (see {@link #inert(Class)}). The five
     * staff-notification-recipient legs are UserManagementSpi pass-throughs
     * whose gate owns elsewhere (see {@link #RECIPIENT_SPI_LEGS}).
     */
    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken("m@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        for (java.lang.reflect.Method leg : UserAccountAdminService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(leg.getModifiers()) || leg.isSynthetic()) {
                continue;
            }
            // The five staff-notification-recipient legs are UserManagementSpi
            // pass-throughs: their ADMIN gate lives in the OWNING use case
            // (StaffNotificationRecipientService), which this unit test mocks —
            // sweeping them here would prove the mock, not the gate. The
            // owner's own sweep (StaffNotificationRecipientServiceTest
            // .everyLegIsAdminGated) refuses a CONSUMER caller on all five
            // legs against the real gate.
            if (RECIPIENT_SPI_LEGS.contains(leg.getName())) {
                continue;
            }
            Object[] args = new Object[leg.getParameterCount()];
            if (args.length > 0) {
                args[0] = consumer;
            }
            for (int i = 1; i < args.length; i++) {
                args[i] = inert(leg.getParameterTypes()[i]);
            }
            assertThatThrownBy(() -> leg.invoke(service, args))
                    .as("%s.%s must refuse a CONSUMER caller at its gate", "UserAccountAdminService", leg.getName())
                    .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                    .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    /**
     * The UserManagementSpi pass-through legs whose ADMIN gate owns at
     * StaffNotificationRecipientService (swept there against the real
     * gate) — see the B23 sweep's skip comment.
     */
    private static final java.util.Set<String> RECIPIENT_SPI_LEGS = java.util.Set.of(
            "createRecipient", "updateRecipient", "deleteRecipient", "findRecipient",
            "listRecipients");

    /**
     * The reflection harness's inert argument for one parameter slot: a
     * primitive slot cannot carry null — Method.invoke would fail the
     * unboxing with IllegalArgumentException BEFORE the leg's gate could
     * answer — so it rides its zero-value default instead (the CI round on
     * the B23 sweep: bulkUpdateCustomers' {@code boolean ignoreFailed}).
     * Every reference slot stays null: a leg that touches its arguments
     * before the gate still fails on its first null-argument symptom,
     * exactly as the sweep promises.
     */
    private static Object inert(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        throw new IllegalArgumentException("Unknown primitive type: " + type);
    }
    /**
     * The 2026-11-08 #810 round-5 review (Major · Security & Privacy): the
     * email move takes the per-user auth-state advisory lock BEFORE the
     * login row is read — the disable rides the same lock, so the two can
     * never interleave their read-then-write windows.
     */
    @Test
    void updateCustomerTakesTheAuthStateLockBeforeTheLoginRead() {
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.userExists(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(CUSTOMER_EMAIL)).thenReturn(loginRow(CUSTOMER_EMAIL));

        service.updateCustomer(admin, CUSTOMER_ID,
                new UserWriteFields(NEW_EMAIL, null, null, null));

        org.mockito.InOrder lockFirst = org.mockito.Mockito.inOrder(userRepository);
        // The lock rides the loaded row's OWN id (the stable identifier —
        // the subject is what moves).
        lockFirst.verify(userRepository).lockUserAuth(customerRow.getId().toString());
        lockFirst.verify(userRepository).existsBySubject(NEW_EMAIL);
    }

}
