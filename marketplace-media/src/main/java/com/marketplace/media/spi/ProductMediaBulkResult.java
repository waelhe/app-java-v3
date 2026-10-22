package com.marketplace.media.spi;

import java.util.List;

/**
 * N-slice: the {@code productMediaBulkDelete} outcome — the
 * honestly-affected count alone (the pinned payload's own member set:
 * count + errors; the per-row projections are not part of the pinned
 * bulk-delete shape).
 *
 * @param count  the rows the registry itself deleted (unknown or
 *               stranger ids are the honest skip — never fabricated,
 *               never an existence disclosure)
 * @param errors the domain validation outcomes (empty IS the success
 *               verdict)
 */
public record ProductMediaBulkResult(int count, List<ProductMediaMutationError> errors) {
}
