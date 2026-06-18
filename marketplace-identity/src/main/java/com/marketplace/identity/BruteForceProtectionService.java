package com.marketplace.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Brute force attack protection.
 * <p>OWASP Authentication Cheat Sheet:
 * <ul>
 *   <li>Lock account after {@code maxFailedAttempts} (default: 5)</li>
 *   <li>Lock duration: {@code lockDurationMinutes} (default: 15)</li>
 *   <li>Reset counter on successful login</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#account-lockout">OWASP Account Lockout</a>
 */
@Service
public class BruteForceProtectionService {

    private static final Logger log = LoggerFactory.getLogger(BruteForceProtectionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuthAuditService auditService;

    private final int maxFailedAttempts;
    private final int lockDurationMinutes;

    public BruteForceProtectionService(JdbcTemplate jdbcTemplate,
                                       AuthAuditService auditService,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.max-failed-attempts:5}") int maxFailedAttempts,
                                       @org.springframework.beans.factory.annotation.Value("${marketplace.security.lock-duration-minutes:15}") int lockDurationMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDurationMinutes = lockDurationMinutes;
    }

    @Transactional
    public void recordFailedAttempt(String username) {
        if (isLocked(username)) {
            return;
        }

        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM auth_users WHERE username = ?",
                Integer.class, username
        );

        if (attempts == null) return;

        int newAttempts = attempts + 1;

        if (newAttempts >= maxFailedAttempts) {
            Instant lockUntil = Instant.now().plus(lockDurationMinutes, ChronoUnit.MINUTES);
            jdbcTemplate.update(
                    "UPDATE auth_users SET failed_attempts = ?, locked_until = ? WHERE username = ?",
                    newAttempts, lockUntil, username
            );
            auditService.log(username, AuthEventType.ACCOUNT_LOCKED,
                    "Locked after " + newAttempts + " failed attempts for " + lockDurationMinutes + " minutes");
            log.warn("Account locked: username={}, attempts={}", username, newAttempts);
        } else {
            jdbcTemplate.update(
                    "UPDATE auth_users SET failed_attempts = ? WHERE username = ?",
                    newAttempts, username
            );
            log.warn("Failed login attempt: username={}, attempts={}/{}", username, newAttempts, maxFailedAttempts);
        }
    }

    @Transactional
    public void resetFailedAttempts(String username) {
        jdbcTemplate.update(
                "UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                username
        );
    }

    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        try {
            Instant lockedUntil = jdbcTemplate.queryForObject(
                    "SELECT locked_until FROM auth_users WHERE username = ?",
                    Instant.class, username
            );
            return lockedUntil != null && Instant.now().isBefore(lockedUntil);
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void unlockAccount(String username) {
        jdbcTemplate.update(
                "UPDATE auth_users SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                username
        );
        auditService.log(username, AuthEventType.ACCOUNT_UNLOCKED, "Account unlocked by admin");
    }
}
