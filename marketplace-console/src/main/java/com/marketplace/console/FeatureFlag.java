package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-15 (compliance plan C.5 — «أعلام ميزات»): one feature flag — a
 * named operational switch the services read AT REQUEST TIME (never a
 * boot-time conditional — the C.10 measured limit). One LIVE row per
 * key (the V157 partial unique over the live rows — the V70 shape), the
 * BaseEntity auditing fields carrying the change history the console's
 * audit read surfaces (the Data JPA auditing reference's own fields:
 * who flipped it, when), the soft delete retiring a flag without
 * losing its trail.
 *
 * <p><b>The §0.1 active limit, embodied:</b> a flag row names a
 * capability; it never CREATES one. {@code isEnabled(key)} answers a
 * policy question about EXISTING code — «والقدرة غير الموجودة كوداً
 * تبقى تطويراً» (a capability not present in code stays development —
 * the flag cannot build it).</p>
 */
@Entity
@Table(name = "feature_flags")
@Audited
public class FeatureFlag extends BaseEntity {

    @Id
    private UUID id;

    /** The flag's key — the services' lookup name (e.g. {@code community.polls.enabled}). */
    @Column(name = "key_name", nullable = false, length = 200)
    private String key;

    /** What the flag gates — the human-facing description (the operator's own documentation). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** The flag's state — read at request time by the services that gate on it. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected FeatureFlag() {
    }

    private FeatureFlag(UUID id, String key, String description, boolean enabled) {
        this.id = id;
        this.key = key;
        this.description = description;
        this.enabled = enabled;
    }

    /** The registration factory — born in the state the operator chose (fail-closed by the console's own policy default). */
    public static FeatureFlag register(String key, String description, boolean enabled) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A feature flag's key cannot be blank");
        }
        return new FeatureFlag(UUID.randomUUID(), key, description, enabled);
    }

    /** The operator's flip — the auditing fields record who and when (the change-history read's own source). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The description's own revision (the operator's documentation edit). */
    public void describe(String description) {
        this.description = description;
    }

    @Override
    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
}
