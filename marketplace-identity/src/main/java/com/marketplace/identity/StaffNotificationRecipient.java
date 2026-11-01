package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * staff notification recipient registry row behind
 * {@code staffNotificationRecipientCreate/Update/Delete} — the routing
 * table for staff-targeted operational mail (order events). Oneof-shaped:
 * either a staff user reference (the user's own email at send time) or a
 * bare email address; the V249 CHECK keeps exactly one of the pair set.
 */
@Entity
@Table(name = "staff_notification_recipients")
@Audited
public class StaffNotificationRecipient extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected StaffNotificationRecipient() {
    }

    private StaffNotificationRecipient(UUID id, UUID userId, String email, boolean active) {
        this.id = id;
        this.userId = userId;
        this.email = email;
        this.active = active;
    }

    public static StaffNotificationRecipient forUser(UUID userId, boolean active) {
        return new StaffNotificationRecipient(UUID.randomUUID(), userId, null, active);
    }

    public static StaffNotificationRecipient forEmail(String email, boolean active) {
        return new StaffNotificationRecipient(UUID.randomUUID(), null, email.trim(), active);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }

    /** The update's own field set — the oneof pair is re-validated by the owning use case. */
    void apply(UUID userId, String email, Boolean active) {
        if (userId != null) {
            this.userId = userId;
            this.email = null;
        }
        if (email != null && !email.isBlank()) {
            this.email = email.trim();
            this.userId = null;
        }
        if (active != null) {
            this.active = active;
        }
    }
}
