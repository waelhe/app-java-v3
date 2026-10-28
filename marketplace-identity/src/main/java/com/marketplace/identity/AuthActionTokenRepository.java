package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A-04: the auth action token store. The three access shapes map exactly
 * to the V112 indexes — hash redemption, the live single-flight pair, and
 * the administrative outstanding scan.
 *
 * <p><b>Public on purpose (the smoke-boot measurement, 2026-10-10):</b> a
 * non-public repository interface breaks Spring Data's repository proxy
 * registration when the application context is loaded through a classloader
 * that differs from the configuration one — the measured signature is
 * "non-public interface is not defined by the given loader" at
 * {@code @EnableJpaRepositories} under the {@code spring-boot:test-run}
 * smoke boot (the restartedMain context). The module's own convention is
 * the public interface (its two siblings: UserRepository,
 * ProviderFollowRepository) — this declaration joins it.</p>
 */
public interface AuthActionTokenRepository extends JpaRepository<AuthActionToken, UUID> {

    /** The redemption lookup — the unique hash index answers it. */
    Optional<AuthActionToken> findByTokenHashAndPurpose(String tokenHash, AuthActionTokenPurpose purpose);

    /** The latest row for one (account, purpose) — the verification-state read. */
    Optional<AuthActionToken> findFirstByUsernameAndPurposeOrderByCreatedAtDesc(
            String username, AuthActionTokenPurpose purpose);

    /** The live single-flight read for the re-request/replace path. */
    Optional<AuthActionToken> findFirstByUsernameAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String username, AuthActionTokenPurpose purpose);

    /**
     * The pre-redemption identity read as a PROJECTION — the part-2
     * review round 1 adoption (row 8's own "the re-read inside the wall
     * stays the single source of the verdict" rule, hardened): loading
     * the ENTITY here lets the persistence context return the same
     * managed instance to {@code consume}'s re-read, so {@code isConsumed()}
     * could read a first-level-cache state instead of the committed row
     * (the {@code @Version} floor already turns the unsafe WRITE into a
     * 400 — this read just stops depending on that interplay). The
     * username-only select touches no entity state at all.
     */
    @Query("select t.username from AuthActionToken t "
            + "where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<String> findUsernameByTokenHashAndPurpose(
            @Param("hash") String tokenHash,
            @Param("purpose") AuthActionTokenPurpose purpose);

    /**
     * Every live token for an account, any purpose — the administrative
     * invalidation scan (disable/pseudonymize consume them all: a banned
     * account must hold no redemption right, and the pseudonymization's
     * {@code deleteUser} would violate the V112 FK otherwise).
     */
    @Query("select t from AuthActionToken t where t.username = :username and t.consumedAt is null")
    List<AuthActionToken> findOutstanding(@Param("username") String username);

    /**
     * Agent 1 (the CodeRabbit integrity round — the login-identity move's
     * FK leg): EVERY row for the account, outstanding AND consumed — the
     * read shape the purge decision and the audit story ride.
     */
    List<AuthActionToken> findAllByUsername(String username);

    /**
     * Agent 1 (the CodeRabbit integrity round, take 2): the PHYSICAL
     * delete the login-identity move's FK leg requires. BaseEntity's
     * Hibernate {@code @SoftDelete} translates both {@code delete(entity)}
     * and bulk JPQL deletes into {@code is_deleted} updates — the row
     * survives and V112's plain FK to {@code auth_users} keeps rejecting
     * the login row's death. The native statement bypasses the
     * soft-delete translation (and the entity listeners): the token
     * rows' lifecycle revisions stay in {@code auth_action_tokens_aud}
     * (the issue + consumption audit), the base rows physically leave so
     * the FK releases the login row.
     */
    @Modifying
    @Query(value = "DELETE FROM auth_action_tokens WHERE username = :username", nativeQuery = true)
    int physicallyDeleteByUsername(@Param("username") String username);
}
