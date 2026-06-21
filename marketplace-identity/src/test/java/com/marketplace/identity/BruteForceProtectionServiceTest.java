package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BruteForceProtectionService}.
 *
 * <p>Verifies the atomic-counter design (PostgreSQL UPDATE ... RETURNING):
 * <ul>
 *   <li>Counter increment uses a single atomic UPDATE statement (no SELECT-then-UPDATE race)</li>
 *   <li>Account is locked after {@code maxFailedAttempts} (default 5)</li>
 *   <li>Already-locked accounts are not re-locked</li>
 *   <li>Counter is reset on successful login or admin unlock</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#account-lockout">OWASP Account Lockout</a>
 * @see <a href="https://www.postgresql.org/docs/current/sql-update.html">PostgreSQL UPDATE — RETURNING clause</a>
 */
@ExtendWith(MockitoExtension.class)
class BruteForceProtectionServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AuthAuditService auditService;

    private BruteForceProtectionService bruteForceService;

    @BeforeEach
    void setUp() {
        // Cannot use @InjectMocks — the constructor takes @Value int primitives
        // which Mockito cannot resolve. Construct manually with explicit test values.
        bruteForceService = new BruteForceProtectionService(jdbcTemplate, auditService, 5, 15);
    }

    @Test
    void recordFailedAttempt_incrementsCounterAtomically() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(null);
        // The atomic UPDATE ... RETURNING returns the new counter value (3 after this attempt).
        when(jdbcTemplate.query(eq("UPDATE auth_users SET failed_attempts = failed_attempts + 1 " +
                        "WHERE username = ? AND (locked_until IS NULL OR locked_until <= NOW()) " +
                        "RETURNING failed_attempts"),
                any(RowMapper.class),
                eq("user@test.com")))
                .thenReturn(List.of(3));

        bruteForceService.recordFailedAttempt("user@test.com");

        // Below threshold — no lock activation UPDATE should run.
        verify(jdbcTemplate, never()).update(
                eq("UPDATE auth_users SET locked_until = ? WHERE username = ? AND locked_until IS NULL"),
                any(), any());
    }

    @Test
    void recordFailedAttempt_locksAfterMaxAttempts() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(null);
        // Counter reaches 6 (already past the 5 threshold) in this attempt.
        when(jdbcTemplate.query(eq("UPDATE auth_users SET failed_attempts = failed_attempts + 1 " +
                        "WHERE username = ? AND (locked_until IS NULL OR locked_until <= NOW()) " +
                        "RETURNING failed_attempts"),
                any(RowMapper.class),
                eq("user@test.com")))
                .thenReturn(List.of(6));

        bruteForceService.recordFailedAttempt("user@test.com");

        verify(jdbcTemplate).update(
                eq("UPDATE auth_users SET locked_until = ? WHERE username = ? AND locked_until IS NULL"),
                any(Instant.class), eq("user@test.com"));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.ACCOUNT_LOCKED), anyString());
    }

    @Test
    void recordFailedAttempt_skipsWhenAlreadyLocked() {
        // isLocked returns true — service must short-circuit before any SQL runs.
        Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(future);

        bruteForceService.recordFailedAttempt("user@test.com");

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any());
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    void recordFailedAttempt_handlesUnknownUserGracefully() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("unknown@user.com")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        // Should not throw — service must handle unknown user gracefully.
        assertDoesNotThrow(() -> bruteForceService.recordFailedAttempt("unknown@user.com"));

        // EmptyResultDataAccessException from isLocked short-circuits to "false",
        // then the UPDATE ... RETURNING will return an empty list (user doesn't exist).
        // We mock the UPDATE to also return empty list.
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("unknown@user.com")))
                .thenReturn(List.of());
        assertDoesNotThrow(() -> bruteForceService.recordFailedAttempt("unknown@user.com"));
    }

    @Test
    void resetFailedAttempts_resetsToZero() {
        bruteForceService.resetFailedAttempts("user@test.com");

        verify(jdbcTemplate).update(eq("UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?"),
                eq("user@test.com"));
    }

    @Test
    void isLocked_returnsTrueWhenLocked() {
        Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(future);

        assertTrue(bruteForceService.isLocked("user@test.com"));
    }

    @Test
    void isLocked_returnsFalseWhenNotLocked() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(null);

        assertFalse(bruteForceService.isLocked("user@test.com"));
    }

    @Test
    void isLocked_returnsFalseWhenExpired() {
        Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(past);

        assertFalse(bruteForceService.isLocked("user@test.com"));
    }

    @Test
    void unlockAccount_resetsCounter() {
        bruteForceService.unlockAccount("user@test.com");

        verify(jdbcTemplate).update(eq("UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?"),
                eq("user@test.com"));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.ACCOUNT_UNLOCKED), anyString());
    }
}
