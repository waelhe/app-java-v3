package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * menu item's per-language name translation (the (item, language) pair
 * unique over live rows).
 */
@Entity
@Table(name = "cms_menu_item_translations")
@Audited
public class CmsMenuItemTranslation extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "menu_item_id", nullable = false)
    private UUID menuItemId;

    @Column(name = "language_code", nullable = false, length = 8)
    private String languageCode;

    @Column(name = "name", nullable = false)
    private String name;

    protected CmsMenuItemTranslation() {
    }

    private CmsMenuItemTranslation(UUID id, UUID menuItemId, String languageCode, String name) {
        this.id = id;
        this.menuItemId = menuItemId;
        this.languageCode = languageCode;
        this.name = name;
    }

    public static CmsMenuItemTranslation create(UUID menuItemId, String languageCode, String name) {
        return new CmsMenuItemTranslation(UUID.randomUUID(), menuItemId, languageCode, name);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getMenuItemId() {
        return menuItemId;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String getName() {
        return name;
    }

    void apply(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }
}
