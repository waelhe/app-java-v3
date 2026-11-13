package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 1: the page translation repository (derived queries). */
public interface CmsPageTranslationRepository extends JpaRepository<CmsPageTranslation, UUID>,
        RevisionRepository<CmsPageTranslation, UUID, Integer> {

    Optional<CmsPageTranslation> findByPageIdAndLanguageCode(UUID pageId, String languageCode);

    List<CmsPageTranslation> findAllByPageId(UUID pageId);
}
