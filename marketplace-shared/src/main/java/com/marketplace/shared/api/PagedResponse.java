package com.marketplace.shared.api;

import org.springframework.data.domain.Page;

import java.io.Serializable;
import java.util.List;

/**
 * Generic paged response wrapper for REST API list endpoints — and, since
 * the shared ports went framework-neutral, the ports' paged answer type.
 *
 * <p><b>Serializable for the Redis cache value path</b> (the contract
 * {@code ListingSummary}'s javadoc documents): the catalog's four
 * {@code @Cacheable} sites cache this record ({@code JdkSerializationRedisSerializer});
 * the previous cached type was {@code PageImpl}, which Spring ships
 * Serializable. For record classes the Object Serialization Specification
 * declares serialVersionUID as 0L unless explicitly declared and waives the
 * match requirement — the canonical-constructor form is the serialization
 * contract.
 */
public record PagedResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
) implements Serializable {
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
        // zero-inference formulation: a wildcard-typed mapper fed through
        // Stream.map leaves javac a capture (List<capture of ? extends R>
        // is NOT a List<R> — CI rounds 2 and 3 measured both the diamond
        // failure and the explicit-type-argument failure), while the plain
        // loop adds each ? extends R value into the List<R> directly —
        // subtypes assign to their bound, no inference anywhere
        List<R> mapped = new java.util.ArrayList<>(content.size());
        for (T element : content) {
            mapped.add(mapper.apply(element));
        }
        return new PagedResponse<R>(mapped, pageNumber, pageSize, totalElements, totalPages, last);
    }
}
