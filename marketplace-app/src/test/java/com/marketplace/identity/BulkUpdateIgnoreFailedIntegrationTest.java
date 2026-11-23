package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import com.marketplace.identity.spi.BulkRowResult;
import com.marketplace.identity.spi.UserWriteFields;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * The 2026-11-23 #810 review's deferred nitpick — the integration pair
 * for {@code bulkUpdateCustomers} with {@code IGNORE_FAILED} (the
 * REQUIRES_NEW per-row law) the unit suite could never prove: the unit
 * fixtures mock the transaction manager, so the rollback of a failing
 * row's PARTIAL writes never ran against a real database.
 *
 * <p>Both journeys ride the REAL Spring services (the real
 * {@code UserAccountAdminService}, the real {@code JdbcUserDetailsManager}
 * login store, the real {@code TransactionTemplate} on the real
 * {@code PlatformTransactionManager}) against the production schema
 * ({@code spring.flyway.enabled=true} + {@code ddl-auto=none} — the
 * AccountSelfDeletionIntegrationTest precedent's own wiring). The ONLY
 * mocked collaborator is the failure INJECTION point: the
 * {@code AuthActionTokenService} bean whose {@code purgeHistory} leg
 * fails mid-row — AFTER the move's login-side insert (the alt login)
 * and BEFORE the old login row dies — exactly the review's specified
 * seam.</p>
 *
 * <p><b>The failure journey proves the three claims the review asked
 * for:</b> the alt login (the partial write the failing row already
 * committed inside its REQUIRES_NEW row transaction) is REMOVED by the
 * rollback; the ORIGINAL login row stays (the account keeps working);
 * and the NEXT row in the same {@code IGNORE_FAILED} batch still
 * succeeds (the failing row never poisons the batch). The success twin
 * proves the alt login is SAVED when the same move completes — the
 * email change's own durable effect.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BulkUpdateIgnoreFailedIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UserAccountAdminService service;

    @Autowired
    private UserService userService;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The ONLY mocked collaborator — the failure injection seam. */
    @MockitoBean
    private AuthActionTokenService authActionTokenService;

    private static final String PASSWORD = "it-bulk-ignore-password";

    /**
     * Every address rides this short unique tag — the registerable-email
     * bound the REAL UserService enforces is at most 50 characters
     * ({@code requireRegisterableEmail}'s own law, the
     * {@code auth_users.username} column's bound): the first CI cut's
     * full-UUID form measured 64-68 characters and died at the register
     * gate. The AccountSelfDeletionLockOrderIntegrationTest precedent's
     * own shape — 8 hex chars of uniqueness, PER_METHOD re-randomized.
     */
    private final String tag = "itbulk" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private final UsernamePasswordAuthenticationToken admin =
            new UsernamePasswordAuthenticationToken("it-bulk-admin@example.com", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    /**
     * The failure journey: row 1's email move fails INSIDE the move
     * (purgeHistory throws after the alt login was already inserted) —
     * the REQUIRES_NEW row transaction's rollback removes the partial
     * write (the alt login row), the ORIGINAL login row stays alive,
     * and the NEXT row of the SAME IGNORE_FAILED batch still succeeds
     * (its own row transaction, untouched by the failure before it).
     */
    @Test
    void ignoreFailedRollsBackTheFailingRowsPartialWritesAndTheNextRowStillSucceeds() {
        // Row 1 — the account whose move fails mid-way.
        String failingOld = tag + "-f@it.com";
        String failingNew = tag + "-fn@it.com";
        User failingUser = userService.register(failingOld, PASSWORD, "Failing Row");
        // Row 2 — the healthy sibling the same batch still processes.
        String healthyOld = tag + "-h@it.com";
        String healthyNew = tag + "-hn@it.com";
        User healthyUser = userService.register(healthyOld, PASSWORD, "Healthy Row");

        // The injection: purgeHistory throws AFTER the alt login insert —
        // the move's own order is lockUserAuth → probe → loadUser →
        // createUser(new) → purgeHistory(old) → deleteUser(old). The
        // throw lands with the alt login already written.
        doThrow(new IllegalArgumentException("injected purge failure"))
                .when(authActionTokenService).purgeHistory(anyString());

        List<BulkRowResult> results = service.bulkUpdateCustomers(admin,
                List.of(failingUser.getId(), healthyUser.getId()),
                List.of(new UserWriteFields(failingNew, null, null, null),
                        new UserWriteFields(healthyNew, null, null, null)),
                true);

        // The failing row answers its per-row result; the healthy row's
        // result is a success.
        assertThat(results).hasSize(2);
        assertThat(results.get(0).errorCode()).isEqualTo("INVALID");
        assertThat(results.get(1).errorCode()).isNull();

        // THE review's first claim: the alt login (the partial write)
        // is REMOVED — the REQUIRES_NEW rollback undid the insert.
        assertThat(userDetailsManager.userExists(failingNew))
                .as("the failing row's partial alt-login write rolled back")
                .isFalse();

        // THE review's second claim: the ORIGINAL login row stays —
        // the account keeps its working login under the old address.
        assertThat(userDetailsManager.userExists(failingOld))
                .as("the failing row's original login survives the rollback")
                .isTrue();

        // THE review's third claim: the NEXT row in the same batch
        // succeeded — its own email move landed end to end: the alt
        // login exists at the new address and the old login is gone.
        assertThat(userDetailsManager.userExists(healthyNew))
                .as("the batch's next row committed its email move")
                .isTrue();
        assertThat(userDetailsManager.userExists(healthyOld))
                .as("the healthy row's old login died with its move")
                .isFalse();
        String storedSubject = jdbcTemplate.queryForObject(
                "SELECT subject FROM users WHERE id = ?", String.class, healthyUser.getId());
        assertThat(storedSubject).isEqualTo(healthyNew);
    }

    /**
     * The success twin: the same email move, unpoisoned — the alt login
     * is SAVED at the new address (the move's durable effect), the old
     * login row is gone, and the users row's subject moved with it.
     */
    @Test
    void ignoreFailedSavesTheAltLoginWhenTheMoveSucceeds() {
        String oldEmail = tag + "-s@it.com";
        String newEmail = tag + "-sn@it.com";
        User user = userService.register(oldEmail, PASSWORD, "Success Row");

        List<BulkRowResult> results = service.bulkUpdateCustomers(admin,
                List.of(user.getId()),
                List.of(new UserWriteFields(newEmail, null, null, null)),
                true);

        assertThat(results.get(0).errorCode()).isNull();
        assertThat(userDetailsManager.userExists(newEmail))
                .as("the successful move saved the alt login at the new address")
                .isTrue();
        assertThat(userDetailsManager.userExists(oldEmail))
                .as("the old login row died with the completed move")
                .isFalse();
        String storedSubject = jdbcTemplate.queryForObject(
                "SELECT subject FROM users WHERE id = ?", String.class, user.getId());
        assertThat(storedSubject).isEqualTo(newEmail);
    }
}
