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
 * page type's assigned-attribute row — one live row per (type, attribute)
 * pair, positioned by the reorder operation.
 */
@Entity
@Table(name = "cms_page_type_attributes")
@Audited
public class CmsPageTypeAttribute extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "page_type_id", nullable = false)
    private UUID pageTypeId;

    @Column(name = "attribute_id", nullable = false)
    private UUID attributeId;

    @Column(name = "position", nullable = false)
    private int position;

    protected CmsPageTypeAttribute() {
    }

    private CmsPageTypeAttribute(UUID id, UUID pageTypeId, UUID attributeId, int position) {
        this.id = id;
        this.pageTypeId = pageTypeId;
        this.attributeId = attributeId;
        this.position = position;
    }

    public static CmsPageTypeAttribute create(UUID pageTypeId, UUID attributeId, int position) {
        return new CmsPageTypeAttribute(UUID.randomUUID(), pageTypeId, attributeId, position);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getPageTypeId() {
        return pageTypeId;
    }

    public UUID getAttributeId() {
        return attributeId;
    }

    public int getPosition() {
        return position;
    }

    void moveTo(int position) {
        this.position = position;
    }
}
