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

/** A reusable typed attribute definition; concrete values/options are separate records. */
@Entity
@Table(name = "catalog_attribute_definitions")
@Audited
public class CatalogAttributeDefinition extends BaseEntity {
    @Id private UUID id;
    @Column(name = "code", nullable = false, length = 50) private String code;
    @Column(name = "name_en", length = 100) private String nameEn;
    @Column(name = "name_ar", length = 100) private String nameAr;
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    private CatalogAttributeType valueType;
    @Column(name = "description", length = 1000) private String description;

    protected CatalogAttributeDefinition() { }

    private CatalogAttributeDefinition(UUID id, String code, String nameEn, String nameAr,
                                       CatalogAttributeType valueType, String description) {
        this.id = id; this.code = code; this.nameEn = nameEn; this.nameAr = nameAr;
        this.valueType = valueType; this.description = description;
    }

    public static CatalogAttributeDefinition create(String code, String nameEn, String nameAr,
                                                    CatalogAttributeType valueType, String description) {
        validateCode(code);
        validateLabels(nameEn, nameAr);
        if (valueType == null) throw new IllegalArgumentException("valueType is required");
        validateDescription(description);
        return new CatalogAttributeDefinition(UUID.randomUUID(), code, blankToNull(nameEn),
                blankToNull(nameAr), valueType, description);
    }

    /** Type and code are stable identity; only display metadata can be amended. */
    public void update(String newNameEn, String newNameAr, String newDescription) {
        validateLabels(newNameEn, newNameAr);
        validateDescription(newDescription);
        this.nameEn = blankToNull(newNameEn);
        this.nameAr = blankToNull(newNameAr);
        this.description = newDescription;
    }

    private static void validateCode(String code) {
        if (code == null || code.length() > 50 || !code.matches("^[a-z][a-z0-9-]*$"))
            throw new IllegalArgumentException("code must be a lowercase slug (latin/digits/dashes, max 50)");
    }
    private static void validateLabels(String en, String ar) {
        if ((en == null || en.isBlank()) && (ar == null || ar.isBlank()))
            throw new IllegalArgumentException("at least one of nameEn or nameAr is required");
        if (en != null && en.length() > 100) throw new IllegalArgumentException("nameEn must be at most 100 characters");
        if (ar != null && ar.length() > 100) throw new IllegalArgumentException("nameAr must be at most 100 characters");
    }
    private static void validateDescription(String description) {
        if (description != null && description.length() > 1000)
            throw new IllegalArgumentException("description must be at most 1000 characters");
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    @Override public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getNameEn() { return nameEn; }
    public String getNameAr() { return nameAr; }
    public CatalogAttributeType getValueType() { return valueType; }
    public String getDescription() { return description; }
}
