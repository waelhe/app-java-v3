package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The account identity row — the {@code users} store of the identity module.
 *
 * <p><b>Multi-role model (ADR-0001, D-02/D-03 — plan §Phase 1):</b> the
 * account's roles are the {@link #roles} set persisted in the
 * {@code user_roles} join table (V171), while the scalar {@code users.role}
 * column remains the <em>primary-role mirror</em> — a deterministic
 * projection ({@link #PRIMARY_PRECEDENCE}) of the set kept in sync in the
 * same transaction, so every existing single-value read surface (the
 * {@code users_aud} Envers mirror, {@code UserSummary.role}, the data
 * export) keeps answering without a schema break. The set is the source of
 * truth; the column is its projection, never written independently.
 *
 * <p><b>Official basis:</b> Spring Data JPA › element collections
 * ({@code @ElementCollection} + {@code @CollectionTable}) — the standard
 * mapping for a multi-valued enum; {@code FetchType.EAGER} because cached
 * copies of this entity are JDK-serialized into Redis (BaseEntity's
 * serialization contract) and a lazy collection on a detached copy would
 * throw on every cold-cache read.
 */
@Entity
@Table(name = "users")
@Audited
public class User extends BaseEntity {

    /**
     * Deterministic primary-role precedence — the same order the login-side
     * authority reader has always applied (ADMIN first, PROVIDER second,
     * CONSUMER the default). The first role the set holds wins the mirror.
     */
    private static final UserRole[] PRIMARY_PRECEDENCE =
            { UserRole.ADMIN, UserRole.PROVIDER, UserRole.CONSUMER };

    @Id
    private UUID id;

    @Column(name = "subject", nullable = false, unique = true, length = 200)
    private String subject;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    /**
     * The primary-role mirror (see the class javadoc) — always a member of
     * {@link #roles}, derived by {@link #PRIMARY_PRECEDENCE}. Maintained
     * exclusively through the role mutators below; never a public setter.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    /**
     * The account's roles — the source of truth (the {@code user_roles}
     * table, V171). An account always holds at least one role: the
     * mutators reject every write that would leave the set empty (the
     * service layer answers the guard with the 409 contract, this entity
     * is the defense in depth for direct SPI callers).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<UserRole> roles = new LinkedHashSet<>();

    /**
     * I7 (account-pseudonymization-plan §5-أ step 4 / V46): the account's
     * pseudonymization marker. {@code null} = a live account; non-null = the
     * one-transaction operation ran — direct identifiers replaced, login
     * identity rows deleted. Doubles as the idempotence marker (the second
     * call is a documented no-op) and the "former member" switch for read
     * DTOs (the neutral label is rendered at the response level only).
     */
    @Column(name = "pseudonymized_at")
    private Instant pseudonymizedAt;

    protected User() {
        this.roles = new LinkedHashSet<>();
    }

    public User(UUID id, String subject, String email, String displayName, UserRole role) {
        this(id, subject, email, displayName, Set.of(role));
    }

    public User(UUID id, String subject, String email, String displayName, Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("An account must hold at least one role");
        }
        this.id = id;
        this.subject = subject;
        this.email = email;
        this.displayName = displayName;
        this.roles = new LinkedHashSet<>(roles);
        this.role = primaryOf(this.roles);
    }

    public static User create(String subject, String email, String displayName, UserRole role) {
        return new User(UUID.randomUUID(), subject, email, displayName, role);
    }

    public static User create(String subject, String email, String displayName, Set<UserRole> roles) {
        return new User(UUID.randomUUID(), subject, email, displayName, roles);
    }

    @Override
    public UUID getId() { return id; }

    public String getSubject() { return subject; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Instant getPseudonymizedAt() { return pseudonymizedAt; }

    /**
     * The primary role — the deterministic projection of {@link #getRoles()}
     * (ADMIN &gt; PROVIDER &gt; CONSUMER). Single-value surfaces keep reading
     * here; every multi-role consumer reads {@link #getRoles()} instead.
     */
    public UserRole getRole() { return role; }

    /**
     * The account's roles — the source of truth, read-only view.
     */
    public Set<UserRole> getRoles() { return Set.copyOf(roles); }

    /**
     * The deterministic primary-role derivation over a role set — the same
     * order the login-side authority reader applies (ADMIN, PROVIDER,
     * CONSUMER default).
     */
    public static UserRole primaryOf(Set<UserRole> roles) {
        for (UserRole candidate : PRIMARY_PRECEDENCE) {
            if (roles.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("An account must hold at least one role");
    }

    /**
     * S1/B1 (measured by the full-loop guard): an ABSENT value is not an
     * erase command. The native login's tokens carry no {@code email}/{@code name}
     * claims (the DaoAuthentication principal holds only the username and
     * authorities) — under the old unconditional-write semantics the FIRST
     * {@code /users/me} call after registration wiped the just-stored profile.
     * The same hazard exists in the OIDC world: an IdP that drops the email
     * scope must not erase every profile it touches. {@code null} now means
     * "no information — keep the stored value"; an explicit erase keeps its
     * own path ({@link #applyPseudonymization} — I7's deliberate nulling).
     */
    public boolean updateProfile(String email, String displayName) {
        boolean changed = false;
        if (email != null && !Objects.equals(this.email, email)) {
            this.email = email;
            changed = true;
        }
        if (displayName != null && !Objects.equals(this.displayName, displayName)) {
            this.displayName = displayName;
            changed = true;
        }
        return changed;
    }

    /**
     * Replaces the whole role set — the administrative "set the role"
     * contract ({@code updateUserRole} delegating here). The primary mirror
     * re-derives atomically; an empty replacement is rejected (an account
     * never becomes roleless).
     */
    public void replaceRoles(Set<UserRole> newRoles) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw new IllegalArgumentException("An account must hold at least one role");
        }
        this.roles = new LinkedHashSet<>(newRoles);
        this.role = primaryOf(this.roles);
    }

    /**
     * {@link #replaceRoles(Set)} with exactly one role — the historical
     * single-role signature kept for its existing callers.
     */
    public void changeRole(UserRole newRole) {
        replaceRoles(Set.of(newRole));
    }

    /**
     * Adds a role (idempotent when already held) — the grant path of the
     * multi-role model.
     */
    public void grantRole(UserRole newRole) {
        this.roles.add(newRole);
        this.role = primaryOf(this.roles);
    }

    /**
     * Removes a role — the revoke path. Removing the last remaining role is
     * rejected here (defense in depth; the service answers 409 first).
     */
    public void revokeRole(UserRole role) {
        if (this.roles.size() <= 1 && this.roles.contains(role)) {
            throw new IllegalStateException("An account must hold at least one role");
        }
        this.roles.remove(role);
        this.role = primaryOf(this.roles);
    }

    /**
     * I7 (account-pseudonymization-plan §5-أ step 3): replaces the direct
     * identifiers on the account row — the subject becomes the derived
     * replacement ({@code "anon-" + hex(HMAC-SHA256(K_env, subject))}, the
     * b-2(b) transformation), and the two profile columns (P2) go to
     * {@code null} (Art. 17(1)(a): identification is no longer necessary for
     * the marketplace purposes once the membership ends). The UUID primary
     * key and every referencing row are untouched on purpose — reference
     * integrity and the other parties' rights (Art. 17(3)(b) / 20(4)) keep
     * the records alive in pseudonymized form.
     *
     * <p>The read surfaces render the neutral "former member" label at the
     * response-DTO level ({@code UserMapper} / {@code toUserSummary}) — never
     * stored here: storage stays {@code null}.
     *
     * @param replacementSubject the deterministic HMAC-derived replacement
     *                            (produced by {@code SubjectPseudonymizer})
     */
    void applyPseudonymization(String replacementSubject) {
        this.subject = replacementSubject;
        this.email = null;
        this.displayName = null;
        this.pseudonymizedAt = Instant.now();
    }
}
