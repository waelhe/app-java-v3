package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {
    List<RecoveryCode> findByUserIdAndUsedFalse(UUID userId);

    /**
     * Atomically marks a recovery code as used only if it is currently unused.
     * <p>Returns 1 if the code was successfully claimed (single-use), 0 if the
     * code was already used (concurrent reuse attempt defeated).
     *
     * <p>This prevents the race where two concurrent requests with the same valid
     * recovery code both pass the in-memory check and both mark the code used.
     * The conditional UPDATE is atomic at the row level under PostgreSQL's
     * READ_COMMITTED isolation (PostgreSQL implicitly takes a row lock on the
     * updated row, serializing concurrent updates to it).
     *
     * <p><b>References</b>
     * <ul>
     *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet -- recovery codes must be single-use</a></li>
     *   <li><a href="https://www.postgresql.org/docs/current/sql-update.html">PostgreSQL UPDATE -- row-level locking</a></li>
     *   <li><a href="https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.modifying-queries">Spring Data JPA -- Modifying queries</a></li>
     * </ul>
     *
     * @param id the recovery-code ID to claim
     * @return 1 if claimed (success), 0 if already used (failure)
     */
    @Modifying
    @Query("UPDATE RecoveryCode rc SET rc.used = true WHERE rc.id = :id AND rc.used = false")
    int claimIfUnused(@Param("id") UUID id);
}
