package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/** Agent 1: the menu item translation repository. */
public interface CmsMenuItemTranslationRepository extends JpaRepository<CmsMenuItemTranslation, UUID>,
        RevisionRepository<CmsMenuItemTranslation, UUID, Integer> {

    Optional<CmsMenuItemTranslation> findByMenuItemIdAndLanguageCode(UUID menuItemId, String languageCode);

    java.util.List<CmsMenuItemTranslation> findAllByMenuItemId(UUID menuItemId);
}
