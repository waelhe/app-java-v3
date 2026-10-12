package com.marketplace.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the publisher registry's repository — the admin
 * surface's writes are id-based reads plus saves (the
 * {@code InstitutionRepository} house shape); no secondary read surface
 * exists in this wave (the outlet registry ledger is a later surface's
 * ride).
 */
public interface NewsPublisherRepository extends JpaRepository<NewsPublisher, UUID> {
}
