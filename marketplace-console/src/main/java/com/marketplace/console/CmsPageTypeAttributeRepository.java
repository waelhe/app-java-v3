package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 1: the page type's assigned-attribute repository. */
public interface CmsPageTypeAttributeRepository extends JpaRepository<CmsPageTypeAttribute, UUID>,
        RevisionRepository<CmsPageTypeAttribute, UUID, Integer> {

    List<CmsPageTypeAttribute> findAllByPageTypeIdOrderByPositionAscIdAsc(UUID pageTypeId);

    Optional<CmsPageTypeAttribute> findByPageTypeIdAndAttributeId(UUID pageTypeId, UUID attributeId);
}
