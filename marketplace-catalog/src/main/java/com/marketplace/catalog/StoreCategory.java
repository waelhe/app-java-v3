package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * A-17 (compliance plan C.7 — the M1 store root): one row of the store
 * categories dictionary — «قاموس فئات المتجر (بياناتًا لا ترحيلات)», the
 * V70 listing-category registry discipline verbatim, as the plan's own
 * wording demands.
 *
 * <p>Lifecycle: reference data (the V47 {@code GeoLocation} and V70
 * {@code Category} precedents). The dictionary TABLE lands with the V116
 * migration; the vocabulary itself is DATA — no starter value is invented
 * here (the V70 seed carried the platform's code-documented "stay" because
 * the code documented it; the store vocabulary has no documented value, so
 * the migration seeds NOTHING and the rows arrive as data operations).
 *
 * <p>{@code code} is the stable API-facing key (what product registration
 * carries — the {@code uq_store_categories_code_active} partial unique
 * keeps live rows unique while a soft-deleted row releases its code for
 * reuse, the V47/V70 precedent); the DB CHECK pins its shape (lowercase
 * latin/digits/dashes). The entity is {@code @Audited} per the AGENTS.md
 * rule, so every amendment leaves an Envers trail through the
 * {@code store_categories_aud} mirror.
 */
@Entity
@Table(name = "store_categories")
@Audited
public class StoreCategory extends BaseEntity {

    @Id
    private UUID id;

    /** The stable API-facing key (lowercase latin/digits/dashes — the V70 CHECK shape). */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /** English display name — the storefront's default label. */
    @Column(name = "name_en", length = 100)
    private String nameEn;

    /** Arabic display name — the primary market's label. */
    @Column(name = "name_ar", length = 100)
    private String nameAr;

    /** Display order for the storefront read (ascending). */
    @Column(name = "position", nullable = false)
    private Integer position;

    protected StoreCategory() {
    }

    private StoreCategory(UUID id, String code, String nameEn, String nameAr, Integer position) {
        this.id = id;
        this.code = code;
        this.nameEn = nameEn;
        this.nameAr = nameAr;
        this.position = position;
    }

    /**
     * Registers one dictionary row — the data-operation path (never a
     * migration; the V70 discipline the plan's wording pins).
     */
    public static StoreCategory register(String code, String nameEn, String nameAr, Integer position) {
        return new StoreCategory(UUID.randomUUID(), code, nameEn, nameAr, position);
    }

    public String getCode() {
        return code;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getNameAr() {
        return nameAr;
    }

    public Integer getPosition() {
        return position;
    }

    public UUID getId() {
        return id;
    }
}
