package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "users")
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

    public void updateProfile(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
    }
}