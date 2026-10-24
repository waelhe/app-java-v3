package com.marketplace.identity;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, RevisionRepository<User, UUID, Integer> {

    Optional<User> findBySubject(String subject);

    Optional<User> findByEmail(String email);

    boolean existsBySubject(String subject);

    /**
     * I7 §9 (rotation row — resolved option 1, the keyring): existence check
     * across every candidate tombstone subject the keyring derives for one
     * raw subject (active + retained keys) — one statement for the
     * re-registration probe instead of one round trip per key. Derived query
     * ({@code Exists<Property>In}) per the official Spring Data JPA contract.
     */
    boolean existsBySubjectIn(Collection<String> subjects);

    /**
     * P1 (the canonical Saleor plan §5-P1 — the {@code staffUsers} keyset
     * scroll): forward-only keyset scrolling over the roster in the three
     * sorts with an owning column, each with the deterministic {@code id}
     * tiebreaker (the official Spring Data JPA scrolling contract — derived
     * query methods taking {@link KeysetScrollPosition} and {@link Limit}
     * and returning {@link Window}; the static ordering is baked into the
     * method name because keyset positions are only meaningful against one
     * fixed sort). Six methods, one per (field, direction) pair the
     * Saleor {@code UserSortingInput} subset executes.
     */
    Window<User> findByOrderByEmailAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<User> findByOrderByEmailDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<User> findByOrderByCreatedAtAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<User> findByOrderByCreatedAtDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<User> findByOrderByUpdatedAtAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<User> findByOrderByUpdatedAtDescIdDesc(KeysetScrollPosition position, Limit limit);

    /**
     * P1 (the canonical Saleor plan §5-P1 — the {@code customers} keyset
     * scroll): forward-only keyset scrolling over the CUSTOMER population
     * (the accounts whose primary role is {@code CONSUMER} — the platform's
     * customer/consumer role, the closed vocabulary the V187 CHECK pins) in
     * the three sorts with an owning column, each with the deterministic
     * {@code id} tiebreaker. The same official Spring Data JPA scrolling
     * contract as the roster methods above — derived query methods taking a
     * role predicate plus {@link KeysetScrollPosition} and {@link Limit} and
     * returning {@link Window}; the static ordering is baked into the method
     * name because keyset positions are only meaningful against one fixed
     * sort. Six methods, one per (field, direction) pair the Saleor
     * {@code UserSortingInput} subset executes. Soft-deleted rows are
     * absent by the entity's own {@code @SoftDelete} filter (the same
     * visibility every identity read applies).
     */
    Window<User> findByRoleOrderByEmailAscIdAsc(UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleOrderByEmailDescIdDesc(UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleOrderByCreatedAtAscIdAsc(UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleOrderByCreatedAtDescIdDesc(UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleOrderByUpdatedAtAscIdAsc(UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleOrderByUpdatedAtDescIdDesc(UserRole role, KeysetScrollPosition position, Limit limit);

    /**
     * P1 (the {@code customers} root's {@code totalCount}): the size of the
     * customer population — the count of live accounts whose primary role
     * is the given role (derived {@code CountBy<Property>} query per the
     * official Spring Data JPA contract; the connection's honest collection
     * total, never a fabricated number).
     */
    long countByRole(UserRole role);
}
