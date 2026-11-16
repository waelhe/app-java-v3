package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): one row of the dynamic
 * per-category attribute registry — the {@code category_attributes} table
 * (V97). The plan's wording: «سمات فئة ديناميكية (جدول سمات لكل فئة —
 * G15، مبدأ "السمات بيانات" نفسه)».
 *
 * <p><b>The categories table's open side:</b> {@code categories} (V70) is
 * the closed half — what a listing may classify as. This table declares
 * WHAT structured attributes a category's listings carry (wifi, parking,
 * electronic payment) — attribute definitions as DATA rows, so a new
 * attribute for a category is an administrator's INSERT, never a
 * migration (the categories' own lifecycle rule, applied one level
 * deeper). The gap's evidence: «الفئة نص حر؛ سمات منظمة لكل فئة ...
 * غائبة | category varchar(100) بلا جدول سمات».
 *
 * <p><b>Identity:</b> {@code code} is the stable API-facing key, unique
 * per category over the live rows (V97's
 * {@code uq_category_attributes_category_code} — the V70
 * {@code uq_categories_code} shape, scoped one level deeper). The DB
 * CHECK pins the same lowercase slug shape V70 pins.
 *
 * <p>Lifecycle: reference data (the V47/V70 precedent) — the registry
 * evolves by INSERT through the administrative surface; the seed bypasses
 * Envers by nature, and every amendment leaves its trail in the
 * {@code category_attributes_aud} mirror (V24 shape).
 */
@Entity
@Table(name = "category_attributes")
@Audited
public class CategoryAttribute extends BaseEntity {

    @Id
    private UUID id;

    /** The owning category row ({@code categories.id}, V70). */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** The stable API-facing key (lowercase latin/digits/dashes — the V70/V97 CHECK shape). */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /** English display label — the storefront's default name. */
    @Column(name = "label_en", length = 100)
    private String labelEn;

    /** Arabic display label — the primary market's name. */
    @Column(name = "label_ar", length = 100)
    private String labelAr;

    /**
     * The declared shape of this attribute's VALUE — the closed
     * three-type vocabulary (TEXT, NUMBER, BOOLEAN; V97's CHECK). A
     * fourth type is a migration-widened decision, never a silent
     * insertion (the V44 discipline).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private CategoryAttributeType valueType;

    /** The registry's display order within the category (non-negative; unique per category over live rows). */
    @Column(name = "position", nullable = false)
    private int position;

    protected CategoryAttribute() {
    }

    private CategoryAttribute(UUID id, UUID categoryId, String code, String labelEn,
                              String labelAr, CategoryAttributeType valueType, int position) {
        this.id = id;
        this.categoryId = categoryId;
        this.code = code;
        this.labelEn = labelEn;
        this.labelAr = labelAr;
        this.valueType = valueType;
        this.position = position;
    }

    /**
     * Factory for the administrative surface: validates the code's slug
     * shape (the V97 CHECK's own definition) and the position's sign —
     * the entity never holds a shape the database would reject.
     */
    public static CategoryAttribute create(UUID categoryId, String code, String labelEn,
                                           String labelAr, CategoryAttributeType valueType,
                                           int position) {
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId is required");
        }
        if (code == null || !code.matches("^[a-z][a-z0-9-]*$") || code.length() > 50) {
            throw new IllegalArgumentException(
                    "code must be a lowercase slug (latin/digits/dashes, max 50)");
        }
        if (valueType == null) {
            throw new IllegalArgumentException("valueType is required");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        return new CategoryAttribute(UUID.randomUUID(), categoryId, code, labelEn, labelAr,
                valueType, position);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getCode() {
        return code;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabelAr() {
        return labelAr;
    }

    public CategoryAttributeType getValueType() {
        return valueType;
    }

    public int getPosition() {
        return position;
    }

    /**
     * The administrative update (PUT replacement semantics — the
     * reference-data registry's own contract): the identity pair
     * (category, code) is immutable — a re-keyed attribute is a new row
     * (the V70 identity rule); labels, type and position replace.
     */
    public void update(String newLabelEn, String newLabelAr,
                       CategoryAttributeType newValueType, int newPosition) {
        if (newValueType == null) {
            throw new IllegalArgumentException("valueType is required");
        }
        if (newPosition < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        this.labelEn = newLabelEn;
        this.labelAr = newLabelAr;
        this.valueType = newValueType;
        this.position = newPosition;
    }
}
