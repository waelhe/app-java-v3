package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BruteForceProtectionServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AuthAuditService auditService;

    private BruteForceProtectionService bruteForceService;

    @BeforeEach
    void setUp() {
        bruteForceService = new BruteForceProtectionService(jdbcTemplate, auditService, 5, 15);
    }

    @Test
    void recordFailedAttempt_incrementsCounter() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(null);
        when(jdbcTemplate.queryForObject(eq("SELECT failed_attempts FROM auth_users WHERE username = ?"),
                eq(Integer.class), eq("user@test.com"))).thenReturn(2);

        bruteForceService.recordFailedAttempt("user@test.com");

        verify(jdbcTemplate).update(eq("UPDATE auth_users SET failed_attempts = ? WHERE username = ?"),
                eq(3), eq("user@test.com"));
    }

    @Test
    void recordFailedAttempt_locksAfterMaxAttempts() {
        when(jdbcTemplate.queryForObject(eq("SELECT locked_until FROM auth_users WHERE username = ?"),
                eq(Instant.class), eq("user@test.com"))).thenReturn(null);
        when(jdbcTemplate.queryForObject(eq("SELECT failed_attempts FROM auth_users WHERE username = ?"),
                eq(Integer.class), eq("user@test.com"))).thenReturn(5);

        bruteForceService.recordFailedAttempt("user@test.com");

        verify(jdbcTemplate).update(eq("UPDATE auth_users SET failed_attempts = ?, locked_until = ? WHERE username = ?"),
                eq(6), any(Instant.class), eq("user@test.com"));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.ACCOUNT_LOCKED), anyString());
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
