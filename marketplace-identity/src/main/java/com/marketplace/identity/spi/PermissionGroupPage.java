package com.marketplace.identity.spi;

import org.springframework.data.domain.Window;

import java.util.List;

/**
 * P1 (the canonical Saleor plan §5-P1): one page of the permission-group
 * keyset scroll — the {@code Window} of group projections plus the total
 * count Saleor's {@code GroupCountableConnection.totalCount} carries. The
 * collection total is the closed role vocabulary's size (no filter executes
 * on this first slice, so the filtered count and the collection count are
 * the same number — the registry records that equivalence's scope, exactly
 * as the staff-list page does).
 *
 * @param window one bounded page of permission groups in the requested sort
 * @param total  the total number of permission groups in the collection
 */
public record PermissionGroupPage(Window<PermissionGroupView> window, long total) {

    /** The page's elements in scroll order. */
    public List<PermissionGroupView> content() {
        return window.getContent();
    }
}
