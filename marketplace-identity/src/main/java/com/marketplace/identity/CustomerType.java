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
 * customer-type row — the Saleor 3.23 B2B customer segmentation registry
 * ({@code customerType*} mutations + the {@code customerType(s)} reads).
 *
 * <p>The type classifies the single identity store's customer accounts;
 * the assigned attribute set lives in {@link CustomerTypeAttribute} (the
 * catalog registry's attribute IDs — existence validated at the
 * composition boundary through the catalog module's declared read SPI).
 * The one-default invariant (exactly one live default row) is owned by
 * the use case's swap under the registry's lock.</p>
 */
@Entity
@Table(name = "customer_types")
@Audited
public class CustomerType extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "is_default", nullable = false)
    private boolean defaultType;

    protected CustomerType() {
    }

    private CustomerType(UUID id, String name, String slug, boolean defaultType) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.defaultType = defaultType;
    }

    public static CustomerType create(String name, String slug, boolean defaultType) {
        return new CustomerType(UUID.randomUUID(), name.trim(), slug, defaultType);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public boolean isDefault() {
        return defaultType;
    }

    /** The update's own field set — null means "no information" (kept). */
    void apply(String name, String slug) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (slug != null && !slug.isBlank()) {
            this.slug = slug.trim();
        }
    }

    void markDefault(boolean value) {
        this.defaultType = value;
    }
}
