package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * D-03 (community platform execution plan Stage 1): the fresh-read path to
 * an account's role SET — the repository discipline the {@code users}
 * cache taught (a cache hit hands back a detached copy; role decisions
 * read the source of truth through here, never through the cached
 * aggregate).
 */
public interface AccountRoleRepository extends JpaRepository<AccountRole, UUID> {

    /** The account's LIVE role rows (the {@code @SoftDelete} filter applies). */
    List<AccountRole> findByUserId(UUID userId);

    boolean existsByUserIdAndRole(UUID userId, UserRole role);

    Optional<AccountRole> findByUserIdAndRole(UUID userId, UserRole role);
}
