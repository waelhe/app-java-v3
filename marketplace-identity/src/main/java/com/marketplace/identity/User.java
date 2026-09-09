package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
@Audited
public class User extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "subject", nullable = false, unique = true, length = 200)
    private String subject;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

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
    }

    public User(UUID id, String subject, String email, String displayName, UserRole role) {
        this.id = id;
        this.subject = subject;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public static User create(String subject, String email, String displayName, UserRole role) {
        return new User(UUID.randomUUID(), subject, email, displayName, role);
    }

    @Override
    public UUID getId() { return id; }

    public String getSubject() { return subject; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public UserRole getRole() { return role; }
    public Instant getPseudonymizedAt() { return pseudonymizedAt; }

    public boolean updateProfile(String email, String displayName) {
        boolean changed = !Objects.equals(this.email, email)
                || !Objects.equals(this.displayName, displayName);
        this.email = email;
        this.displayName = displayName;
        return changed;
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
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