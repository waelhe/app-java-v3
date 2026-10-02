package com.marketplace.provider;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): one declared geographic
 * service area — the {@code service_areas} table (V88). The Yelp
 * «أخدم هذه المناطق» block: «نطاق خدمة جغرافي معلن (service_areas — G13)».
 *
 * <p><b>A real place, never free text:</b> the row points INTO the
 * platform's geo tree ({@code geo_locations}, V47) — the same cached
 * tree every location in the system resolves through. A declared area is
 * therefore a node the platform already knows (a city, a district), the
 * public page renders its resolved name, and the JSON-LD block carries
 * it as the {@code areaServed} place. The gap's own evidence named the
 * distinction: «عمود الفئة في الإعلان ليس نطاق مزود» — a listing's
 * category field is not a provider's service area.
 *
 * <p><b>One declared area per (provider, location)</b> over the live rows
 * (V88's {@code uq_service_areas_provider_location} — the V64
 * one-per-target shape): declaring the same district twice is a data
 * defect, not a stronger claim.
 */
@Entity
@Table(name = "service_areas")
@Audited
public class ServiceArea extends BaseEntity {

    @Id
    private UUID id;

    /** The owning provider profile — the page this area renders on. */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The declared geo-tree node id ({@code geo_locations.id}, V47). */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    protected ServiceArea() {
    }

    private ServiceArea(UUID id, UUID providerId, UUID locationId) {
        this.id = id;
        this.providerId = providerId;
        this.locationId = locationId;
    }

    /** Factory for the write surface — both ids are required (a row without them is meaningless). */
    public static ServiceArea create(UUID providerId, UUID locationId) {
        if (providerId == null || locationId == null) {
            throw new IllegalArgumentException("providerId and locationId are both required");
        }
        return new ServiceArea(UUID.randomUUID(), providerId, locationId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getLocationId() {
        return locationId;
    }
}
