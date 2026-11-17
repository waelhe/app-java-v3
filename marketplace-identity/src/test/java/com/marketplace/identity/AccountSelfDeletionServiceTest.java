package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;

/**
 * A-05 unit guards — the self-service deletion's contracts (A.3): the
 * double verification (the Bearer token's subject + the current password —
 * a wrong password mutates NOTHING), the honest negatives (the
 * already-deleted account's 404; the projection-without-login-row 404), and
 * the exact delegation to the standing I7 erasure operation with the fixed
 * self-service reason and the register-precedent "self" actor.
 */
@ExtendWith(MockitoExtension.class)
class AccountSelfDeletionServiceTest {

    private static final String EMAIL = "member@example.com";
    private static final String RAW_PASSWORD = "correct-password";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private UserService userService;
    @Mock
    private ObjectProvider<PasswordEncoder> passwordEncoder;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccountSelfDeletionService service;

    @BeforeEach
    void setUp() {
        service = new AccountSelfDeletionService(
                userRepository, userDetailsManager, userService, passwordEncoder, jdbcTemplate);
        // The real delegating encoder — the same primitive the login gate's
        // DaoAuthenticationProvider consults, so the {bcrypt}/{noop} stored
        // forms verify exactly as production would.
        lenient().when(passwordEncoder.getObject()).thenReturn(createDelegatingPasswordEncoder());
    }

    /**
     * B2 round 2 (CodeRabbit Minor 2026-11-13): the lock statement's own
     * stub — the row exists and is locked on the caller's transaction.
     */
    private void lockedAccountRow() {
        when(jdbcTemplate.queryForList(
                eq(AccountSelfDeletionService.LOCK_ACCOUNT_ROW), eq(String.class), eq(EMAIL)))
                .thenReturn(List.of(EMAIL));
    }

    private User member() {
        return User.create(EMAIL, EMAIL, "Member", UserRole.CONSUMER);
    }

    private UserDetails loginRow() {
        return org.springframework.security.core.userdetails.User
                .withUsername(EMAIL)
                .password("{noop}" + RAW_PASSWORD)
                .roles("CONSUMER")
                .build();
    }

    @Test
    void deleteOwnAccount_verifiedPasswordTriggersTheStandingErasureWithTheFixedReasonAndSelfActor() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(loginRow());
        lockedAccountRow();

        service.deleteOwnAccount(EMAIL, RAW_PASSWORD);

        // The delegation is the EXISTING GDPR purge — same operation the
        // administrative surface triggers, with the journey's fixed reason
        // and the register-precedent actor (never the raw subject — the
        // CWE-532 discipline the audit line documents).
        verify(userService).pseudonymizeAccount(
                user.getId(), AccountSelfDeletionService.SELF_DELETION_REASON,
                AccountSelfDeletionService.SELF_ACTOR);
    }

    @Test
    void deleteOwnAccount_wrongPasswordAnswers401AndMutatesNothing() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(loginRow());

        // The critical security guard: the irreversible operation demands
        // the credential a stolen-but-unexpired access token does not hold —
        // and a failed verification leaves every store byte-identical.
        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(userService);
    }

    @Test
    void deleteOwnAccount_alreadyDeletedAccountAnswersTheHonest404() {
        // The pseudonymized row carries the derived replacement subject —
        // the original token's subject resolves to nothing.
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(userService);
        verify(userDetailsManager, never()).loadUserByUsername(anyString());
    }

    @Test
    void deleteOwnAccount_projectionRowWithoutALoginAccountAnswers404() {
        // The L23 defensive shape pseudonymizeAccount itself carries: no
        // authentication account backs the projection — there is no holder
        // whose password could be double-verified.
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(member()));
        when(userDetailsManager.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException(EMAIL));

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No authentication account for user:");

        verifyNoInteractions(userService);
    }

    @Test
    void deleteOwnAccount_verificationUsesTheStoredVerifierNotTheRawInput() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        // The bcrypt stored form — the matches() call must receive it
        // verbatim (the official PasswordEncoder contract: raw candidate,
        // stored verifier).
        UserDetails bcryptRow = org.springframework.security.core.userdetails.User
                .withUsername(EMAIL)
                .password("{bcrypt}$2a$10$invalidbutwellformedhashvalue0000000000000000000000")
                .roles("CONSUMER")
                .build();
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(bcryptRow);

        // A local mocked encoder pins the interaction: matches() receives
        // the raw candidate and the stored verifier verbatim.
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        when(encoder.matches(RAW_PASSWORD, bcryptRow.getPassword())).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<PasswordEncoder> localProvider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(localProvider.getObject()).thenReturn(encoder);
        AccountSelfDeletionService localService = new AccountSelfDeletionService(
                userRepository, userDetailsManager, userService, localProvider, jdbcTemplate);

        assertThatThrownBy(() -> localService.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(BadCredentialsException.class);

        verify(encoder).matches(RAW_PASSWORD, bcryptRow.getPassword());
        verifyNoInteractions(userService);
    }

    // ---- B2 (owner-gate batch 6a — the A-04 extension to the erasure door) ----

    /**
     * B2's own required proof (the documented gate recommendation): a
     * DISABLED account with its VALID password and a still-live access
     * token answers the honest 409 CONFLICT-001 and mutates nothing —
     * the suspended account does not write through the self-service
     * erasure door. {@code User.withUsername(...).disabled(true)} drives
     * the same enabled bit {@code updateUserStatus} flips through the
     * manager ({@code auth_users.enabled}).
     */
    @Test
    void deleteOwnAccount_disabledAccountAnswers409AndMutatesNothing() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        UserDetails disabledRow = org.springframework.security.core.userdetails.User
                .withUsername(EMAIL)
                .password("{noop}" + RAW_PASSWORD)
                .roles("CONSUMER")
                .disabled(true)
                .build();
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(disabledRow);
        lockedAccountRow();

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("disabled account cannot execute the self-service deletion");

        // Zero mutation: the standing erasure is never reached while the
        // account is under administrative suspension.
        verifyNoInteractions(userService);
    }

    /**
     * B2 order discipline: the disabled refusal runs AFTER the credential
     * proof — a disabled account with a WRONG password answers the same
     * 401 the healthy path answers (no state oracle before the password
     * gate; the double-verification contract stands).
     */
    @Test
    void deleteOwnAccount_disabledAccountWithWrongPasswordStillAnswers401Not409() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        UserDetails disabledRow = org.springframework.security.core.userdetails.User
                .withUsername(EMAIL)
                .password("{noop}" + RAW_PASSWORD)
                .roles("CONSUMER")
                .disabled(true)
                .build();
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(disabledRow);

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(userService);
    }

    /**
     * B2's seam guard (the B8 rule): the SELF door refuses while the
     * account is disabled — the ADMINISTRATIVE erasure seam stays live by
     * design. The guard is bound to this service's own door only: the
     * delegation target ({@code UserService.pseudonymizeAccount}) is the
     * SAME operation the administrative surface triggers, and no enabled
     * check was added anywhere on that channel (the untouched seam is
     * what makes the disabled account's erasure still executable — by
     * the owner's hand, not the suspended account's own).
     */
    @Test
    void deleteOwnAccount_enabledAccountPathIsUnchangedTheSeamStaysAdministrative() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(loginRow());
        lockedAccountRow();

        service.deleteOwnAccount(EMAIL, RAW_PASSWORD);

        // The healthy path delegates exactly as before — B2 added no
        // friction to the enabled account's own erasure.
        verify(userService).pseudonymizeAccount(
                user.getId(), AccountSelfDeletionService.SELF_DELETION_REASON,
                AccountSelfDeletionService.SELF_ACTOR);
    }

    // ---- B2 round 2 (CodeRabbit Minor 2026-11-13 — the serialization point) ----

    /**
     * B2 round 2's own race proof: the credential proof's pre-lock read can
     * be stale — an administrative disable committing between that read and
     * the purge would have sailed through the unlocked check. The row lock
     * serializes the disable decision against the delete decision, and the
     * fresh read UNDER the lock carries the committed truth: the pre-lock
     * row says ENABLED, the committed row says DISABLED, and the answer is
     * the honest 409 with the erasure never reached — the lock is verifiably
     * taken BEFORE the enabled verdict's read. B2 round 3 (the lock-order
     * discipline): the advisory invariant lock precedes the row lock — the
     * exact order every admin-affecting UserService path takes, pinned here
     * alongside the row lock.
     */
    @Test
    void deleteOwnAccount_disableCommittingInsideTheWindowIsSeenByTheCommittedRead() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        // The pre-lock read (the password proof's own copy): still enabled.
        // The committed read under the lock: the administrator's disable has
        // landed — the stale copy is exactly what the row lock retires.
        UserDetails disabledRow = org.springframework.security.core.userdetails.User
                .withUsername(EMAIL)
                .password("{noop}" + RAW_PASSWORD)
                .roles("CONSUMER")
                .disabled(true)
                .build();
        when(userDetailsManager.loadUserByUsername(EMAIL))
                .thenReturn(loginRow())
                .thenReturn(disabledRow);
        lockedAccountRow();

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("disabled account cannot execute the self-service deletion");

        // The lock order is pinned exactly (B2 round 3): the advisory
        // invariant lock FIRST, the account row lock second, the enabled
        // verdict's committed read AFTER both — never inverted, never
        // skipped.
        InOrder order = inOrder(jdbcTemplate, userDetailsManager);
        order.verify(jdbcTemplate).execute(UserService.LOCK_ACTIVE_ADMIN_INVARIANT);
        order.verify(jdbcTemplate).queryForList(
                eq(AccountSelfDeletionService.LOCK_ACCOUNT_ROW), eq(String.class), eq(EMAIL));
        order.verify(userDetailsManager).loadUserByUsername(EMAIL);

        verifyNoInteractions(userService);
    }

    /**
     * B2 round 2's defensive leg: the login row existed for the credential
     * proof but the lock finds no row — a concurrent administrative delete
     * removed the account inside the window. The self-service surface
     * answers the honest 404 (the L23 defensive shape), never the erasure.
     */
    @Test
    void deleteOwnAccount_accountDeletedInsideTheWindowAnswersTheHonest404() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(loginRow());
        when(jdbcTemplate.queryForList(
                eq(AccountSelfDeletionService.LOCK_ACCOUNT_ROW), eq(String.class), eq(EMAIL)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.deleteOwnAccount(EMAIL, RAW_PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No authentication account for user:");

        verifyNoInteractions(userService);
    }
}
