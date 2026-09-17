package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The neighborhood feed's own repository (L42). The
 * {@link JpaSpecificationExecutor} arm is the official Spring Data JPA
 * Specifications entry point — "an extensible set of predicates …
 * removing the need to declare a query (method) for every needed
 * combination" — which the feed composes through
 * {@link NeighborhoodPostSpecifications} (D-N5: hasLocation/hasCategory/
 * VISIBLE-only, ordered on the complete sort key {@code created_at DESC,
 * id DESC}).
 *
 * <p>Hibernate's {@code @SoftDelete} filter hides author-deleted posts
 * from every derived query, so the feed never sees them without any
 * predicate of its own. The RevisionRepository arm carries the Envers
 * trail (V24 convention): every post and author delete is a revision the
 * moderation (L45) and export surfaces read.
 */
public interface NeighborhoodPostRepository
        extends JpaRepository<NeighborhoodPost, UUID>,
        JpaSpecificationExecutor<NeighborhoodPost>,
        RevisionRepository<NeighborhoodPost, UUID, Integer> {
}
