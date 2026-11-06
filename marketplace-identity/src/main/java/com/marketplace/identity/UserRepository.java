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

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the account-row serialization seam — the pessimistic write lock the
     * address service's first-default computation (and any other
     * read-then-mark window on the account's own row) takes BEFORE the
     * read, so two concurrent firsts cannot both pass the count check (the
     * official Spring Data JPA @Lock derived-query contract).
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

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

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the customer population EXCLUDES the pseudonymized accounts — a
     * deleted customer keeps its CONSUMER row (pseudonymized, the identity
     * gone), so the plain role predicate used to scroll and count the
     * tombstone in the admin surface (a Window row the operator cannot
     * act on, and a total nobody can reconcile). The same six-sorts shape
     * as the roster methods, plus the count, each with
     * {@code pseudonymizedAt IS NULL} (the admin surface's own visibility
     * law; soft-deleted rows are already absent via @SoftDelete).
     *
     * <p>The 2026-11-06 #810 round review (Major · Functional
     * Correctness): the EMAIL-vocabulary scrolls key on the SUBJECT
     * column, not the email column — the subject is the account's
     * ALWAYS-PRESENT identifier (the normalized address for password
     * accounts, the mandatory {@code sub} claim for OIDC-provisioned
     * ones), while the email column is NULL for an OIDC account whose
     * token carried no {@code email} claim; a nullable keyset sort key
     * silently drops rows from the windowed pages. Saleor's
     * UserSortingInput.EMAIL keeps its wire vocabulary — the executed
     * key is the platform's own non-null identifier.</p>
     */
    Window<User> findByRoleAndPseudonymizedAtIsNullOrderBySubjectAscIdAsc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleAndPseudonymizedAtIsNullOrderBySubjectDescIdDesc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleAndPseudonymizedAtIsNullOrderByCreatedAtAscIdAsc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleAndPseudonymizedAtIsNullOrderByCreatedAtDescIdDesc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleAndPseudonymizedAtIsNullOrderByUpdatedAtAscIdAsc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    Window<User> findByRoleAndPseudonymizedAtIsNullOrderByUpdatedAtDescIdDesc(
            UserRole role, KeysetScrollPosition position, Limit limit);

    /** The customer population's honest total — the pseudonymized excluded. */
    long countByRoleAndPseudonymizedAtIsNull(UserRole role);
}
