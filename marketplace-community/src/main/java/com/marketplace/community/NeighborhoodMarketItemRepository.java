package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The market board's own repository (L50). The board read rides the
 * official Specifications (D-N5 — the NeighborhoodEventRepository shape
 * verbatim: an extensible predicate set, no query method per
 * combination: location scope, the text read, the category axis and
 * the mine axis compose freely in the service).
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every item's lifecycle revision the export surface
 * reads. No locked find here — unlike the events' serialized seats,
 * the market board has no count-then-insert race to serialize (one
 * row's own lifecycle, no capacity); the house soft delete carries the
 * withdraw.
 */
public interface NeighborhoodMarketItemRepository
        extends JpaRepository<NeighborhoodMarketItem, UUID>,
        JpaSpecificationExecutor<NeighborhoodMarketItem>,
        RevisionRepository<NeighborhoodMarketItem, UUID, Integer> {
}
