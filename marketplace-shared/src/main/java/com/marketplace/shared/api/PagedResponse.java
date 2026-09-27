package com.marketplace.shared.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generic paged response wrapper for REST API list endpoints.
 */
public record PagedResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }

    /**
     * The honest empty page for a neutral request — the ports'
     * early-exit form (an empty whitelist or empty restriction set is an
     * honest empty page, never a query): same shape
     * {@code Page.empty(pageable)} produced, derived without any
     * framework type.
     */
    public static <T> PagedResponse<T> empty(PagedRequest request) {
        return new PagedResponse<T>(
                List.of(),
                request.page(),
                request.size(),
                0L,
                0,
                true
        );
    }

    /** Whether the page carries no content ({@code Page.isEmpty()} analog). */
    public boolean isEmpty() {
        return content.isEmpty();
    }

    /**
     * Maps the page's content, keeping the page metadata — the analog of
     * Spring Data's {@code Page.map(Function)} (the REST layer and the
     * tests already speak this shape on pages).
     */
    public <R> PagedResponse<R> map(java.util.function.Function<? super T, ? extends R> mapper) {
        // Spring Data's own PageImpl.map formulation: a typed local pins the
        // stream's element type to exactly R (a wildcard-typed mapper passed
        // straight through would leave javac a capture — CI round 2's
        // "cannot infer type arguments" at the diamond), and the explicit
        // type argument on the construction removes the last inference corner
        List<R> mapped = content.stream().map(mapper::apply).toList();
        return new PagedResponse<R>(mapped, pageNumber, pageSize, totalElements, totalPages, last);
    }
}
