package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A-04: the auth action token store. The three access shapes map exactly
 * to the V112 indexes — hash redemption, the live single-flight pair, and
 * the administrative outstanding scan.
 */
interface AuthActionTokenRepository extends JpaRepository<AuthActionToken, UUID> {

    /** The redemption lookup — the unique hash index answers it. */
    Optional<AuthActionToken> findByTokenHashAndPurpose(String tokenHash, AuthActionTokenPurpose purpose);

    /** The latest row for one (account, purpose) — the verification-state read. */
    Optional<AuthActionToken> findFirstByUsernameAndPurposeOrderByCreatedAtDesc(
            String username, AuthActionTokenPurpose purpose);

    /** The live single-flight read for the re-request/replace path. */
    Optional<AuthActionToken> findFirstByUsernameAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String username, AuthActionTokenPurpose purpose);

    /**
     * Every live token for an account, any purpose — the administrative
     * invalidation scan (disable/pseudonymize consume them all: a banned
     * account must hold no redemption right, and the pseudonymization's
     * {@code deleteUser} would violate the V112 FK otherwise).
     */
    @Query("select t from AuthActionToken t where t.username = :username and t.consumedAt is null")
    List<AuthActionToken> findOutstanding(@Param("username") String username);
}
