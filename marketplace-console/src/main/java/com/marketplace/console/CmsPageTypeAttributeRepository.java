package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 1: the page type's assigned-attribute repository. */
public interface CmsPageTypeAttributeRepository extends JpaRepository<CmsPageTypeAttribute, UUID>,
        RevisionRepository<CmsPageTypeAttribute, UUID, Integer> {

    List<CmsPageTypeAttribute> findAllByPageTypeIdOrderByPositionAscIdAsc(UUID pageTypeId);
    /**
     * The 2026-11-23 #810 round review (Minor · Performance): the
     * type scroll window's batch load — every attribute of every type
     * in the result window in ONE statement (the per-type loop was an
     * N+1 over the window). The per-group insertion order rides the
     * query's own position ordering.
     */
    List<CmsPageTypeAttribute> findAllByPageTypeIdIn(Collection<UUID> pageTypeIds);

    Optional<CmsPageTypeAttribute> findByPageTypeIdAndAttributeId(UUID pageTypeId, UUID attributeId);
}
