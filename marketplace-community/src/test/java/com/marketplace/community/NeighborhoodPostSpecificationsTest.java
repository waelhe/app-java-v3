package com.marketplace.community;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L42 — the feed predicates' own contract: the null-category predicate
 * is ABSENT (a conjunction touching nothing — the official
 * Specifications model), the present-category and location predicates
 * delegate to the criteria builder's equal on the mapped field, and the
 * VISIBLE floor pins the moderation read. The composition itself (the
 * .and chain the service builds) is proven by the integration test
 * against the real schema.
 */
class NeighborhoodPostSpecificationsTest {

    @SuppressWarnings("unchecked")
    private Root<NeighborhoodPost> root() {
        return mock(Root.class);
    }

    @Test
    void hasCategory_null_isTheAbsentConjunction() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        jakarta.persistence.criteria.Predicate conjunction = mock(jakarta.persistence.criteria.Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        // ONE Root mock for both the call and the verify — the instance
        // the predicate actually receives is the one whose interactions
        // the assertion reads (the CodeRabbit round-1 adoption).
        Root<NeighborhoodPost> root = root();

        Specification<NeighborhoodPost> spec =
                NeighborhoodPostSpecifications.hasCategory(null);

        assertThat(spec.toPredicate(root, mock(CriteriaQuery.class), cb))
                .isSameAs(conjunction);
        // The absent predicate touches no field — the official model.
        verifyNoInteractions(root);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasCategory_present_delegatesToEqual() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<NeighborhoodPost> root = root();
        jakarta.persistence.criteria.Path<Object> categoryPath = mock(jakarta.persistence.criteria.Path.class);
        when((jakarta.persistence.criteria.Path<Object>) root.get("category")).thenReturn(categoryPath);

        NeighborhoodPostSpecifications.hasCategory(PostCategory.LOST_FOUND)
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        org.mockito.Mockito.verify(cb).equal(categoryPath, PostCategory.LOST_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasLocation_delegatesToEqualOnTheMappedField() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<NeighborhoodPost> root = root();
        UUID locationId = UUID.randomUUID();
        jakarta.persistence.criteria.Path<Object> locationPath = mock(jakarta.persistence.criteria.Path.class);
        when((jakarta.persistence.criteria.Path<Object>) root.get("locationId")).thenReturn(locationPath);

        NeighborhoodPostSpecifications.hasLocation(locationId)
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        org.mockito.Mockito.verify(cb).equal(locationPath, locationId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void isVisible_pinsTheModerationFloor() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<NeighborhoodPost> root = root();
        jakarta.persistence.criteria.Path<Object> statusPath = mock(jakarta.persistence.criteria.Path.class);
        when((jakarta.persistence.criteria.Path<Object>) root.get("status")).thenReturn(statusPath);

        NeighborhoodPostSpecifications.isVisible()
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        org.mockito.Mockito.verify(cb).equal(statusPath, PostStatus.VISIBLE);
    }
}
