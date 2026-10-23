package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * S6 (comprehensive repair plan §10/2.3): one row of the listing category
 * registry — the controlled vocabulary the listing's {@code category} column
 * references.
 *
 * <p>Lifecycle: reference data (the V47 {@code GeoLocation} precedent). The
 * registry is seeded by the {@code V70} migration with the platform's
 * code-documented starter value ("stay"); the vocabulary itself is DATA —
 * the business model document declares the listing-classification vocabulary
 * an open product gate ("تصنيف نصوص اللوحات"), so it evolves by INSERT, never
 * by migration. The entity is {@code @Audited} per the AGENTS.md rule, so
 * every amendment leaves an Envers trail through the {@code categories_aud}
 * mirror (the seed itself bypasses Envers by nature — the documented geo
 * D-E11 precedent).
 *
 * <p>{@code code} is the stable API-facing key (what create/update carry —
 * the {@code uq_categories_code_active} partial unique index keeps ACTIVE
 * rows unique while a soft-deleted row releases its code for reuse, the V47
 * slug precedent); the DB CHECK pins its shape (lowercase latin/digits/
 * dashes — no casing drift can ever reach the column).
 */
@Entity
@Table(name = "categories")
@Audited
public class Category extends BaseEntity {

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

    /** Display order for the public read (ascending). */
    @Column(name = "position", nullable = false)
    private Integer position;

    protected Category() {
    }

    private Category(UUID id, String code, String nameEn, String nameAr, Integer position) {
        this.id = id;
        this.code = code;
        this.nameEn = nameEn;
        this.nameAr = nameAr;
        this.position = position;
    }

    /** Factory for tests and future admin surfaces — the registry's write shape. */
    public static Category create(String code, String nameEn, String nameAr, Integer position) {
        return new Category(UUID.randomUUID(), code, nameEn, nameAr, position);
    }

    @Override
    public UUID getId() {
        return id;
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
}
