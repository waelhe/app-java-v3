package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/** One allowed choice for a CHOICE attribute; codes are stable within the definition. */
@Entity
@Table(name = "catalog_attribute_options")
@Audited
public class CatalogAttributeOption extends BaseEntity {
    @Id private UUID id;
    @Column(name = "attribute_definition_id", nullable = false) private UUID attributeDefinitionId;
    @Column(name = "code", nullable = false, length = 50) private String code;
    @Column(name = "name_en", length = 100) private String nameEn;
    @Column(name = "name_ar", length = 100) private String nameAr;
    @Column(name = "position", nullable = false) private int position;

    protected CatalogAttributeOption() { }

    private CatalogAttributeOption(UUID id, UUID attributeDefinitionId, String code,
                                   String nameEn, String nameAr, int position) {
        this.id = id; this.attributeDefinitionId = attributeDefinitionId; this.code = code;
        this.nameEn = nameEn; this.nameAr = nameAr; this.position = position;
    }

    public static CatalogAttributeOption create(UUID attributeDefinitionId, String code,
                                                 String nameEn, String nameAr, int position) {
        if (attributeDefinitionId == null) throw new IllegalArgumentException("attributeDefinitionId is required");
        if (code == null || code.length() > 50 || !code.matches("^[a-z][a-z0-9-]*$"))
            throw new IllegalArgumentException("option code must be a lowercase slug (max 50)");
        validateNames(nameEn, nameAr);
        if (position < 0) throw new IllegalArgumentException("position must be non-negative");
        return new CatalogAttributeOption(UUID.randomUUID(), attributeDefinitionId, code,
                blankToNull(nameEn), blankToNull(nameAr), position);
    }

    private static void validateNames(String nameEn, String nameAr) {
        if ((nameEn == null || nameEn.isBlank()) && (nameAr == null || nameAr.isBlank()))
            throw new IllegalArgumentException("at least one of nameEn or nameAr is required");
        if (nameEn != null && nameEn.length() > 100) throw new IllegalArgumentException("nameEn must be at most 100 characters");
        if (nameAr != null && nameAr.length() > 100) throw new IllegalArgumentException("nameAr must be at most 100 characters");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    /**
     * The option row's amendment shape — the ADMIN write use case's own
     * seam (the Saleor {@code attributeValueUpdate} root mapped onto the
     * registry's bilingual columns; the {@code Category.revise}
     * precedent). Each argument is the ALREADY-VALIDATED replacement
     * value (the use case owns validation and the immutable-code
     * contract); {@code null} preserves the column (partial update, the
     * Saleor input's omit-to-preserve semantics). The code, the owning
     * attribute reference and the position are stable identity — never
     * amended here (position moves only through the reorder root's own
     * seam).
     */
    void revise(String newNameEn, String newNameAr) {
        validateNames(newNameEn, newNameAr);
        this.nameEn = blankToNull(newNameEn);
        this.nameAr = blankToNull(newNameAr);
    }

    @Override public UUID getId() { return id; }
    public UUID getAttributeDefinitionId() { return attributeDefinitionId; }
    public String getCode() { return code; }
    public String getNameEn() { return nameEn; }
    public String getNameAr() { return nameAr; }
    public int getPosition() { return position; }
}
