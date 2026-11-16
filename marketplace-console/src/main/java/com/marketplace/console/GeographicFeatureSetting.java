package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-18 (compliance plan C.10 — إعدادات ميزات وارثة جغرافيًا): one
 * feature-setting row scoped to ONE node of the administrative geo
 * hierarchy — «بلد ← مدينة ← حي». The console's third settings surface,
 * extending {@link FeatureFlag}'s own semantics with the geographic
 * dimension: the SAME request-time gate discipline (never a boot-time
 * conditional — the C.10 measured limit: Boot's {@code external-config}
 * governs the STATIC at boot exclusively; the geographic-operational is
 * DATA read in the service layer AT REQUEST TIME).
 *
 * <p><b>The row's shape is C.10's own wording ({@code مفتاح ميزة +
 * نطاق جغرافي + قيمة}):</b> {@code key} (the feature's lookup name, the
 * same key space the services gate on), {@code locationId} (the geo
 * hierarchy node — a plain UUID column with no JPA relation, resolved
 * and gated through {@code GeoLookupPort} exactly like every house
 * cross-module location fact), and {@code enabled} (the feature-gate
 * value). One LIVE row per (key, location) — the V160 partial unique in
 * the V70/V157 shape: a soft-deleted (retired) setting never blocks its
 * own re-registration.
 *
 * <p><b>The resolution contract (the row's whole reason):</b> the
 * effective value at a location is the NEAREST ANCESTOR's live row —
 * «الأخص يغلب الأعم» (the neighborhood beats the city beats the
 * governorate beats the country), then the GLOBAL flag row, then the
 * fail-closed default. The walk itself lives in {@link ConsoleService}
 * over the port — this row only carries the fact.
 *
 * <p><b>The declared non-FK (the V70 categories' own reasoning,
 * measured):</b> {@code key_name} deliberately carries no database-level
 * dependency on {@code feature_flags.key_name} — the geographic settings
 * name the SAME key space the services gate on, and an enforced FK would
 * either break the independent-scoping tests or pollute the flag registry
 * with test artifacts. The application read composes the two halves
 * honestly: a geographic row on an unregistered key resolves ON/OFF at
 * its own geography while everywhere else answers the fail-closed default
 * (an unregistered flag is OFF — a typo'd key can never silently enable
 * a capability).
 */
@Entity
@Table(name = "geographic_feature_settings")
@Audited
public class GeographicFeatureSetting extends BaseEntity {

    @Id
    private UUID id;

    /** The feature's key — the same lookup name the services gate on (e.g. {@code community.polls.enabled}). */
    @Column(name = "key_name", nullable = false, length = 200)
    private String key;

    /** The geo hierarchy node this row scopes — level 0..3, gated through {@code GeoLookupPort} at every write. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** The feature-gate value at this scope — read at request time by the resolution walk. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected GeographicFeatureSetting() {
    }

    private GeographicFeatureSetting(UUID id, String key, UUID locationId, boolean enabled) {
        this.id = id;
        this.key = key;
        this.locationId = locationId;
        this.enabled = enabled;
    }

    /**
     * The registration factory — born in the state the operator chose.
     * The location's existence and level are the SERVICE's gate (the
     * port's own 404 BEFORE any write — the L31 discipline); the
     * duplicate-live-pair 409 is the service's polite face of the V160
     * partial unique.
     */
    public static GeographicFeatureSetting register(String key, UUID locationId, boolean enabled) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A geographic feature setting's key cannot be blank");
        }
        return new GeographicFeatureSetting(UUID.randomUUID(), key, locationId, enabled);
    }

    /** The operator's flip — the auditing fields record who and when (the change-history read's own source). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public UUID getId() { return id; }
    public String getKey() { return key; }
    public UUID getLocationId() { return locationId; }
    public boolean isEnabled() { return enabled; }
}
