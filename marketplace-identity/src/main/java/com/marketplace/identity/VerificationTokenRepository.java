package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByToken(String token);

    /**
     * Atomically marks a verification token as used only if it is currently unused.
     * <p>Returns 1 if the token was successfully claimed (single-use), 0 if the
     * token was already used (concurrent reuse attempt defeated).
     *
     * <p>This prevents the TOCTOU race where two concurrent requests with the same
     * valid token both pass the {@code validateToken} check (which runs in a
     * read-only transaction) and both proceed to reset the password. The conditional
     * UPDATE is atomic at the row level.
     *
     * <p>Reference: Spring Data JPA Reference — Modifying Queries:
     * "@Modifying @Query("update User u set u.firstname = ?1 where u.lastname = ?2")"
     * https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.modifying-queries
     *
     * @param id the verification-token ID to claim
     * @return 1 if claimed (success), 0 if already used (failure)
     */
    @Modifying
    @Query("UPDATE VerificationToken t SET t.used = true WHERE t.id = :id AND t.used = false")
    int claimIfUnused(@Param("id") UUID id);
}
