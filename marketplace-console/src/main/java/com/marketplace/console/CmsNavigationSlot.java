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
 * navigation slot assignment — one live row per slot (MAIN/SECONDARY),
 * referencing a live menu or carrying none (the detached state).
 */
@Entity
@Table(name = "cms_navigation_slots")
@Audited
public class CmsNavigationSlot extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "slot", nullable = false, length = 16)
    private String slot;

    @Column(name = "menu_id")
    private UUID menuId;

    protected CmsNavigationSlot() {
    }

    private CmsNavigationSlot(UUID id, String slot, UUID menuId) {
        this.id = id;
        this.slot = slot;
        this.menuId = menuId;
    }

    public static CmsNavigationSlot create(String slot, UUID menuId) {
        return new CmsNavigationSlot(UUID.randomUUID(), slot, menuId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getSlot() {
        return slot;
    }

    public UUID getMenuId() {
        return menuId;
    }

    void assign(UUID menuId) {
        this.menuId = menuId;
    }
}
