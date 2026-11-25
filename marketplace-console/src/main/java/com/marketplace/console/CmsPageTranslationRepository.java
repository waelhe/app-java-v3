package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 1: the page translation repository (derived queries). */
public interface CmsPageTranslationRepository extends JpaRepository<CmsPageTranslation, UUID>,
        RevisionRepository<CmsPageTranslation, UUID, Integer> {

    Optional<CmsPageTranslation> findByPageIdAndLanguageCode(UUID pageId, String languageCode);

    List<CmsPageTranslation> findAllByPageId(UUID pageId);

    /**
     * The 2026-11-23 #810 round review (Minor · Performance): the scroll
     * path's batch load — every translation of every page in the result
     * window in ONE statement (the per-page {@link #findAllByPageId}
     * loop was an N+1 over the window: one statement per row plus one
     * page-type read per row).
     */
    List<CmsPageTranslation> findAllByPageIdIn(Collection<UUID> pageIds);
}
