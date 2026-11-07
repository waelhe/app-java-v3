package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeView;
import com.netflix.graphql.dgs.DgsDataLoader;
import org.dataloader.BatchLoaderEnvironment;
import org.dataloader.MappedBatchLoaderWithContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The {@code Product.productType} field's batch resolver — the official
 * DGS data-loader pattern (dgs-starter, DGS 12.1.0:
 * {@code @DgsDataLoader} + {@code MappedBatchLoaderWithContext}) closing
 * the 2026-11-03 #806 review's N+1 finding (Nitpick · Performance &amp;
 * Scalability): the field fetcher used to call
 * {@code ProductTypeReadSpi.findProductTypeById} once per product, so a
 * full page of {@code products} (the pagination hard maximum, 100)
 * executed up to 100 sequential owner lookups — the same per-object
 * pattern riding every {@code Collection.products} page. The loader
 * batches the page's decoded references into ONE
 * {@code findProductTypesByIds} owner call.
 *
 * <p><b>The caller travels with the key</b> — the framework's own
 * per-load context ({@code DataLoader.load(key, keyContext)}): the field
 * fetcher passes its caller's {@link Authentication}, captured on the
 * request thread where the security context lives, and this batch reads
 * it back through {@link BatchLoaderEnvironment#getKeyContexts()}. The
 * alternative — reading {@link SecurityContextHolder} inside the batch —
 * is WRONG by construction: the registry dispatches batches on its own
 * scheduled thread, where the request thread's context never arrives.
 * Establishing the captured context around the owner call (set, invoke,
 * restore — the {@code DelegatingSecurityContextRunnable} discipline)
 * keeps the caller's identity available to the owner seam on ANY
 * dispatch thread. (The 2026-11-04 #806 round: the batch seam is the
 * PUBLIC catalog read — the {@code Product.productType} field serves
 * every caller who can see a product — so it carries no staff gate; the
 * captured-context discipline stays for the owner's caller-scoped
 * behavior and any future gating at the owning use case.)</p>
 *
 * <p><b>Values are never null</b> — the java-dataloader contract: every
 * requested key answers its {@link Optional} (an unresolvable reference
 * the owner's own safe absence, {@code Optional.empty()}, never a null
 * future value), and the owner seam answers every key by its own
 * contract. {@code caching} stays on (the framework default): the same
 * type referenced by several products on one page — or by the
 * {@code product} root and a list in one document — answers from ONE
 * owner entry. {@code maxBatchSize} matches the boundary's pagination
 * hard maximum (100): the SQL {@code IN} clause rides the same bound
 * every list field already carries.</p>
 */
@DgsDataLoader(name = ProductTypeDataLoader.NAME, caching = true, batching = true, maxBatchSize = 100)
public class ProductTypeDataLoader
        implements MappedBatchLoaderWithContext<UUID, Optional<ProductTypeView>> {

    /** The registry name the field fetcher loads through. */
    public static final String NAME = "productTypeById";

    private final ProductTypeReadSpi productTypeReadSpi;

    public ProductTypeDataLoader(ProductTypeReadSpi productTypeReadSpi) {
        this.productTypeReadSpi = productTypeReadSpi;
    }

    @Override
    public CompletionStage<Map<UUID, Optional<ProductTypeView>>> load(Set<UUID> ids,
                                                                      BatchLoaderEnvironment environment) {
        if (ids == null || ids.isEmpty()) {
            // The framework never dispatches an empty batch; the guard
            // keeps the method total on its own contract.
            return CompletableFuture.completedFuture(Map.of());
        }
        // One request, one caller: DGS builds the loader registry per
        // execution, so every load in this batch carries the SAME
        // Authentication as its key context. The consistency check is the
        // honest wall — a mixed batch cannot happen under the framework's
        // own request scoping, and a load without a context would mean a
        // fetcher bypassed the captured-caller contract.
        Map<Object, Object> keyContexts = environment.getKeyContexts();
        Authentication caller = null;
        for (UUID id : ids) {
            Object context = keyContexts.get(id);
            if (!(context instanceof Authentication candidate)) {
                throw new IllegalStateException(
                        "The productType data loader requires every field fetcher to pass its "
                                + "caller's Authentication as the load context");
            }
            if (caller == null) {
                caller = candidate;
            } else if (!caller.equals(candidate)) {
                throw new IllegalStateException(
                        "The productType data loader batch spans multiple callers — impossible "
                                + "under the framework's request-scoped loader registry");
            }
        }
        // The captured caller's context around the owner call: set, invoke,
        // restore — the security context the owner's @PreAuthorize decides
        // on is the one the field fetcher captured, on ANY dispatch thread.
        SecurityContext batchContext = SecurityContextHolder.createEmptyContext();
        batchContext.setAuthentication(caller);
        SecurityContext original = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(batchContext);
        try {
            return CompletableFuture.completedFuture(
                    productTypeReadSpi.findProductTypesByIds(ids, caller));
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }
}
