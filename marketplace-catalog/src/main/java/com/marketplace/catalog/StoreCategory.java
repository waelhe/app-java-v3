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
     * migration; the V70 discipline the plan's wording pins). Validation
     * mirrors the V116 CHECK contracts (code shape, non-negative position)
     * so an invalid row fails before any database access — the
     * {@link ProductType#create} precedent.
     */
    public static StoreCategory register(String code, String nameEn, String nameAr, Integer position) {
        validateCode(code);
        validateDisplayNames(nameEn, nameAr);
        Integer safePosition = position == null ? Integer.valueOf(0) : position;
        if (safePosition < 0) {
            throw new IllegalArgumentException("position must be non-negative (the V116 CHECK contract)");
        }
        return new StoreCategory(UUID.randomUUID(), code, blankToNull(nameEn), blankToNull(nameAr), safePosition);
    }

    /**
     * Replaces display metadata and shelf position; the stable public code
     * remains immutable (the {@link ProductType#update} precedent — identity
     * values are never edited).
     */
    public void update(String newNameEn, String newNameAr, Integer newPosition) {
        validateDisplayNames(newNameEn, newNameAr);
        Integer safePosition = newPosition == null ? this.position : newPosition;
        if (safePosition < 0) {
            throw new IllegalArgumentException("position must be non-negative (the V116 CHECK contract)");
        }
        this.nameEn = blankToNull(newNameEn);
        this.nameAr = blankToNull(newNameAr);
        this.position = safePosition;
    }

    private static void validateCode(String code) {
        if (code == null || code.length() > 50
                || !code.matches("^[a-z0-9-]+$") || !code.equals(code.toLowerCase())) {
            throw new IllegalArgumentException("code must be a lowercase slug (latin/digits/dashes, max 50)");
        }
    }

    private static void validateDisplayNames(String en, String ar) {
        if ((en == null || en.isBlank()) && (ar == null || ar.isBlank())) {
            throw new IllegalArgumentException("at least one of nameEn or nameAr is required");
        }
        if (en != null && en.length() > 100) {
            throw new IllegalArgumentException("nameEn must be at most 100 characters");
        }
        if (ar != null && ar.length() > 100) {
            throw new IllegalArgumentException("nameAr must be at most 100 characters");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
