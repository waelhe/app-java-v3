package com.marketplace.community;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L45 — the queue predicate's own contract: the null-status predicate is
 * ABSENT (a conjunction touching nothing — the official Specifications
 * model, the same null discipline the feed's {@code hasCategory}
 * carries), the present-status predicate delegates to the criteria
 * builder's equal on the mapped field. The composition itself is proven
 * by the integration test against the real schema.
 */
class ContentReportSpecificationsTest {

    @SuppressWarnings("unchecked")
    private Root<ContentReport> root() {
        return mock(Root.class);
    }

    @Test
    void hasStatus_null_isTheAbsentConjunction() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        jakarta.persistence.criteria.Predicate conjunction = mock(jakarta.persistence.criteria.Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        Root<ContentReport> root = root();

        Specification<ContentReport> spec = ContentReportSpecifications.hasStatus(null);

        assertThat(spec.toPredicate(root, mock(CriteriaQuery.class), cb))
                .isSameAs(conjunction);
        // The absent predicate touches no field — the official model.
        verifyNoInteractions(root);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasStatus_present_delegatesToEqual() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<ContentReport> root = root();
        jakarta.persistence.criteria.Path<Object> statusPath = mock(jakarta.persistence.criteria.Path.class);
        when((jakarta.persistence.criteria.Path<Object>) root.get("status")).thenReturn(statusPath);

        ContentReportSpecifications.hasStatus(ReportStatus.OPEN)
                .toPredicate(root, mock(CriteriaQuery.class), cb);

        org.mockito.Mockito.verify(cb).equal(statusPath, ReportStatus.OPEN);
    }
}
