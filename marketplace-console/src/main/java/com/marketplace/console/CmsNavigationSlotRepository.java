package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;

/** Agent 1: the navigation slot repository. */
public interface CmsNavigationSlotRepository extends JpaRepository<CmsNavigationSlot, java.util.UUID>,
        RevisionRepository<CmsNavigationSlot, java.util.UUID, Integer> {

    Optional<CmsNavigationSlot> findBySlot(String slot);
}
