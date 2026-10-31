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
 * menu item — one node of a menu's tree. The target is at most ONE of an
 * external URL, a catalog CATEGORY id or a CMS PAGE id (the V283 oneof
 * CHECK). The parent edge forms the forest; cycle prevention and the
 * cross-menu guard live in the owning use case (the move operation walks
 * the ancestor chain before re-parenting).
 */
@Entity
@Table(name = "cms_menu_items")
@Audited
public class CmsMenuItem extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "menu_id", nullable = false)
    private UUID menuId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "url", length = 2048)
    private String url;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "position", nullable = false)
    private int position;

    protected CmsMenuItem() {
    }

    private CmsMenuItem(UUID id, UUID menuId, UUID parentId, String name) {
        this.id = id;
        this.menuId = menuId;
        this.parentId = parentId;
        this.name = name;
    }

    public static CmsMenuItem create(UUID menuId, UUID parentId, String name) {
        return new CmsMenuItem(UUID.randomUUID(), menuId, parentId, name);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getMenuId() {
        return menuId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getPageId() {
        return pageId;
    }

    public int getPosition() {
        return position;
    }

    /** The update's own field set — null means "no information" (kept). */
    void apply(String name, String url, UUID categoryId, UUID pageId) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (url != null) {
            // A non-blank URL switches the oneof target: the category and
            // page pointers must clear in the SAME write, or the merged row
            // carries two targets and the V283 at-most-one-target CHECK
            // rejects the flush (the CodeRabbit data-integrity round: the
            // update path failed with a raw database error instead of
            // switching the target). A blank URL is the explicit clear of
            // the URL target alone — it leaves the other pointers untouched
            // (the row may then carry no target at all, which the CHECK
            // allows).
            boolean switchingToUrl = !url.isBlank();
            this.url = switchingToUrl ? url.trim() : null;
            if (switchingToUrl) {
                this.categoryId = null;
                this.pageId = null;
            }
        }
        if (categoryId != null) {
            this.categoryId = categoryId;
            this.pageId = null;
            this.url = null;
        }
        if (pageId != null) {
            this.pageId = pageId;
            this.categoryId = null;
            this.url = null;
        }
    }

    void reParent(UUID parentId) {
        this.parentId = parentId;
    }

    void moveTo(int position) {
        this.position = position;
    }
}
