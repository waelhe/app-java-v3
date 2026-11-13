package com.marketplace.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 round 3 (CodeRabbit Minor 2026-11-13 — the lock-order discipline): the
 * deadlock guard the finding asked for, on the real database — a self-deleting
 * ADMIN racing an administrative disable of ANOTHER admin must serialize on
 * the advisory invariant lock, never deadlock.
 *
 * <p><b>The measured defect this guard keeps closed:</b> the pre-round-3
 * shape took the account's row lock ({@code LOCK_ACCOUNT_ROW}) first and then
 * requested the advisory invariant lock INSIDE
 * {@code UserService.pseudonymizeAccount} — the INVERSE of
 * {@code updateUserStatus}'s order (advisory first, row locks through
 * {@code LOCK_ACTIVE_ADMINS} after). A self-deleting admin racing an admin
 * disable could therefore hold exactly the lock the other waited for, in both
 * directions: the deletion holds the {@code auth_users} row the disable's
 * {@code LOCK_ACTIVE_ADMINS} wants, while the disable holds the advisory lock
 * the deletion's pseudonymization wants — PostgreSQL's deadlock detector
 * aborts one of the two transactions with a deadlock error. The fix takes the
 * advisory lock BEFORE the row lock (the documented order every
 * admin-affecting {@code UserService} path uses), so every taker of both
 * locks acquires them in one order and the cycle cannot form.</p>
 *
 * <p><b>Why a third bystander admin:</b> the last-active-ADMIN guard
 * ({@code LOCK_ACTIVE_ADMINS}'s counting) must stay clear of both racing
 * operations in either serialization order — with the bystander standing,
 * the deletion's count and the disable's count both see at least two active
 * admins whether they run first or second, so the guard's honest 409 can
 * never masquerade as (or mask) the deadlock outcome under assertion.</p>
 *
 * <p><b>The proof shape (the A3 race-proof precedent verbatim):</b> two
 * independent transactions started together through a {@link CyclicBarrier}
 * on a two-thread pool — each future must complete inside the deadline; a
 * PostgreSQL deadlock abort surfaces as an {@link java.util.concurrent.ExecutionException}
 * from {@code Future.get} and fails the guard. The post-state assertions
 * prove BOTH operations completed (not merely returned): the self-deleted
 * admin's login identity is gone, the other admin is disabled, the bystander
 * stands untouched.</p>
 *
 * <p><b>Official anchors (the pinned versions in the root POM):</b> Spring
 * Boot 4.1.2 — {@code @ServiceConnection} (Spring Boot reference, Testing ›
 * Testcontainers); PostgreSQL — {@code pg_advisory_xact_lock} transaction
 * scope and the deadlock detector (the PostgreSQL docs' explicit
 * lock-ordering guidance: "the best defense against deadlocks is to make
 * sure that all applications update rows or objects in a consistent order");
 * Testcontainers — {@code disabledWithoutDocker} (degrades to a skip where
 * no Docker exists, never a red).</p>
 */
@SpringBootTest(properties = {
        // The production-shaped schema: real Flyway, no ddl-auto (the V33
        // lesson — the constraints under test are the production constraints,
        // including V13's auth_users/auth_authorities and the
        // auth_effective_authorities view the guard counts through).
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // CI connection budget (the I5/L14 harness rationale — the same
        // numbers the purge guard carries; two racing transactions plus the
        // async listener dispatch fit the pool with room to spare).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AccountSelfDeletionLockOrderIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches IntegrationContainers' own return type (the established container pattern).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory (the established pair).
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** The double-verification credential the seeded admins carry ({noop}: the delegating encoder's own form). */
    private static final String PASSWORD = "it-lock-order-password";

    /** The unique-per-run seed tag (the ReviewModerationIntegrationTest fixture discipline). */
    private final String tag = "lockorder" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    private AccountSelfDeletionService selfDeletion;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String selfDeletedAdmin;
    private String disabledAdmin;
    private String bystanderAdmin;
    private UUID selfDeletedAdminId;
    private UUID disabledAdminId;
    private UUID bystanderAdminId;

    @BeforeEach
    void seedThreeActiveAdmins() {
        selfDeletedAdmin = tag + "-one@it.com";
        disabledAdmin = tag + "-two@it.com";
        bystanderAdmin = tag + "-three@it.com";
        selfDeletedAdminId = seedAdminAccount(selfDeletedAdmin);
        disabledAdminId = seedAdminAccount(disabledAdmin);
        bystanderAdminId = seedAdminAccount(bystanderAdmin);
    }

    @AfterEach
    void removeSeed() {
        // Login rows first (the manager's own deleteUser order — authorities
        // then the user row); the self-deleted admin's row is already gone
        // through the operation under test, so every delete is guarded.
        for (String subject : new String[] {selfDeletedAdmin, disabledAdmin, bystanderAdmin}) {
            try {
                if (userDetailsManager.userExists(subject)) {
                    userDetailsManager.deleteUser(subject);
                }
            } catch (RuntimeException alreadyGone) {
                // The operation under test already retired this login row.
            }
        }
        // The projection rows: the self-deleted admin's row survives
        // pseudonymization (subject replaced, id unchanged) — delete by id.
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                selfDeletedAdminId, disabledAdminId, bystanderAdminId);
    }

    /**
     * Seeds one ENABLED admin end to end: the {@code users} projection row
     * (the entity the deletion path resolves by subject) plus the
     * {@code auth_users}/{@code auth_authorities} rows through the framework
     * manager — the AuthorizationServerLoginGateIntegrationTest
     * {@code registerUser} shape, with ADMIN authority so both racing
     * operations walk their admin-guarded legs.
     */
    private UUID seedAdminAccount(String subject) {
        UUID id = userRepository.save(
                User.create(subject, subject, "Lock Order IT", UserRole.ADMIN)).getId();
        if (!userDetailsManager.userExists(subject)) {
            userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                    .withUsername(subject)
                    .password("{noop}" + PASSWORD)
                    .roles("ADMIN")
                    .build());
        }
        return id;
    }

    /**
     * The round-3 deadlock guard: the two admin-affecting operations race
     * through independent transactions started together — the self-service
     * erasure of one admin against the administrative disable of another.
     * Under the advisory-first lock order they serialize (whichever commits
     * second re-reads the committed truth); under the pre-fix inverted order
     * the two transactions wait for each other and PostgreSQL's deadlock
     * detector aborts one — surfacing here as a failed future.
     */
    @Test
    void concurrentAdminSelfDeletionAndAdminDisableSerializeWithoutDeadlock() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> {
                awaitStart(start);
                // No security context needed: the service narrows by the
                // double verification (subject + password), not by authority.
                selfDeletion.deleteOwnAccount(selfDeletedAdmin, PASSWORD);
                return "deleted";
            });
            var disable = pool.submit(() -> {
                awaitStart(start);
                // The administrative surface's own gate: hasRole('ADMIN') on
                // the service method — a real JwtAuthenticationToken with
                // ROLE_ADMIN, built the manual-shape way the reviews
                // moderation IT builds its worker-thread tokens; the
                // bystander admin is the acting hand (an administrator
                // disabling ANOTHER administrator, the finding's shape).
                runAsAdmin(bystanderAdmin, () -> userService.updateUserStatus(
                        disabledAdminId, "DISABLED", "lock-order IT disable", "it-lock-order"));
                return "disabled";
            });
            // A PostgreSQL deadlock abort surfaces as ExecutionException —
            // the guard's failure shape; both operations must complete.
            assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo("deleted");
            assertThat(disable.get(60, TimeUnit.SECONDS)).isEqualTo("disabled");
        } finally {
            pool.shutdownNow();
        }

        // BOTH operations completed — the post-state is the proof, not
        // merely the absence of a throw: the self-deleted admin's login
        // identity is gone, the other admin is disabled, the bystander
        // stands enabled (untouched by either transaction).
        assertThat(userDetailsManager.userExists(selfDeletedAdmin))
                .as("the self-deleted admin's login identity is dead")
                .isFalse();
        assertThat(userDetailsManager.loadUserByUsername(disabledAdmin).isEnabled())
                .as("the raced administrative disable committed")
                .isFalse();
        assertThat(userDetailsManager.loadUserByUsername(bystanderAdmin).isEnabled())
                .as("the bystander admin stands untouched")
                .isTrue();
    }

    /** The barrier hold (the A3 race-proof shape): both transactions start together or not at all. */
    private void awaitStart(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("race start failed: " + e.getMessage(), e);
        }
    }

    /**
     * The worker thread's ADMIN security context — method security is
     * ThreadLocal, so the disabler thread carries its own token (built the
     * manual-shape way the reviews moderation IT builds its
     * JwtAuthenticationToken), cleared after the operation exactly as
     * production request scoping does.
     */
    private void runAsAdmin(String actingSubject, Runnable operation) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("lock-order-it-token")
                .header("alg", "none")
                .subject(actingSubject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        try {
            operation.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
