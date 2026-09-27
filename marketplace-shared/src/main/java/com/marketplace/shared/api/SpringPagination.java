package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * The Spring Data interop corner of the neutral pagination contracts —
 * the one class that translates between {@link PagedRequest}/
 * {@link PagedResponse} and {@code Pageable}/{@code Page}.
 *
 * <p><b>Why it exists:</b> the shared ports' signatures are
 * framework-neutral (that is the point of {@link PagedRequest}), but the
 * modules behind and around them are Spring-backed — Spring Data
 * repositories page in {@code Pageable}, controllers resolve request
 * parameters into {@code Pageable}, and services expose Spring-typed
 * APIs of their own. Every translation between the two worlds goes
 * through THIS class so the mapping exists exactly once (the same
 * discipline {@link PagedResponse#of(Page)} already established for the
 * response side).
 *
 * <p><b>Round-trip fidelity (measured against the sort vocabulary that
 * crosses the ports):</b> page, size, and every sort step — property
 * name and direction, in priority order — survive
 * {@code toPageable(toPagedRequest(pageable))} unchanged. The direction
 * is binary in both representations, so nothing is lost in either
 * direction.
 */
public final class SpringPagination {

    private SpringPagination() {
    }

    /** Maps the Spring request onto the neutral port request. */
    public static PagedRequest toPagedRequest(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return PagedRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        }
        List<PagedRequest.Order> orders = pageable.getSort().stream()
                .map(order -> new PagedRequest.Order(
                        order.getProperty(), order.getDirection().isDescending()))
                .toList();
        return new PagedRequest(pageable.getPageNumber(), pageable.getPageSize(), orders);
    }

    /**
     * Maps the neutral port request back onto the Spring Data request —
     * the adapters' entry conversion (the repositories and specification
     * paths keep operating on {@code Pageable} exactly as before).
     */
    public static Pageable toPageable(PagedRequest request) {
        if (request.sort().isEmpty()) {
            return PageRequest.of(request.page(), request.size());
        }
        List<Sort.Order> orders = request.sort().stream()
                .map(order -> Sort.Order.by(order.property())
                        .with(order.descending() ? Sort.Direction.DESC : Sort.Direction.ASC))
                .toList();
        return PageRequest.of(request.page(), request.size(), Sort.by(orders));
    }

    /**
     * Wraps a neutral port result back into the Spring page a
     * Spring-typed service API returns — the content and total come from
     * the port, the page metadata from the caller's own original
     * {@code Pageable} (the honest-metadata contract: the empty and
     * assembled pages carry the caller's request shape, not a synthetic
     * one).
     */
    public static <T> Page<T> toPage(PagedResponse<T> response, Pageable original) {
        return new PageImpl<>(response.content(), original, response.totalElements());
    }
}
