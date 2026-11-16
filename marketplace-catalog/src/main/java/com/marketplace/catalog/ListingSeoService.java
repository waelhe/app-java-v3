package com.marketplace.catalog;

import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the
 * JSON-LD composition for the public listing detail read — the L38
 * leaf-composition architecture verbatim (the cross-module reads ride
 * the leaf, never the service layer: {@code RealestateService}
 * implements {@code PropertyDetailsPort} while depending on
 * {@code CatalogService}, so injecting ports into the service would
 * close a cycle the controller composition keeps structurally
 * impossible).
 *
 * <p><b>One port call per read:</b> the address chain resolves from the
 * geo module's CACHED tree ({@link GeoLookupPort#getTree()} — the
 * {@code geo-tree} cache with AFTER_COMMIT invalidation on admin
 * writes), not from per-node {@code getLocation()} lookups (measured:
 * the per-node path is uncached — up to four database round trips per
 * read; the tree is "small by design — hundreds of rows at city
 * scale", one cached call). A location id that is absent from the tree
 * yields an empty chain — the JSON-LD simply omits the address (the
 * listing's location stays in its property block for display); no
 * invented facts, no failed read.
 *
 * <p>The block exists only for listings with the L31 property block —
 * a service listing is not a schema.org {@code RealEstateListing}.
 */
@Service
public class ListingSeoService {

    private final GeoLookupPort geoLookupPort;
    private final CatalogProperties catalogProperties;

    public ListingSeoService(GeoLookupPort geoLookupPort,
                             CatalogProperties catalogProperties) {
        this.geoLookupPort = geoLookupPort;
        this.catalogProperties = catalogProperties;
    }

    /**
     * The JSON-LD block of one composed public listing detail — empty
     * when the listing carries no real-estate property block.
     */
    public Optional<RealEstateListingJsonLd> jsonLdFor(ListingResponse listing) {
        if (listing.property() == null) {
            return Optional.empty();
        }
        return Optional.of(RealEstateListingJsonLd.of(
                listing,
                addressChain(listing.property().locationId()),
                catalogProperties.seo().listingUrl(listing.id())));
    }

    /**
     * The L30 chain of the attached location: the node itself and every
     * ancestor up to the root, attached-node first. Empty when the
     * property block carries no location or the node is not in the
     * (cached) tree.
     */
    List<GeoNode> addressChain(UUID locationId) {
        if (locationId == null) {
            return List.of();
        }
        Map<UUID, GeoNode> byId = indexById(geoLookupPort.getTree());
        GeoNode node = byId.get(locationId);
        if (node == null) {
            return List.of();
        }
        List<GeoNode> chain = new ArrayList<>(4);
        chain.add(node);
        UUID parentId = node.parentId();
        int guard = 0; // the hierarchy is 4 levels; the guard makes a corrupt cycle fail loud, not loop
        while (parentId != null && guard++ < 16) {
            GeoNode parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            chain.add(parent);
            parentId = parent.parentId();
        }
        return chain;
    }

    /** The cached tree flattened once per read — id → node (children dropped). */
    private static Map<UUID, GeoNode> indexById(GeoNode root) {
        Map<UUID, GeoNode> byId = new HashMap<>(64);
        flatten(root, byId);
        return byId;
    }

    private static void flatten(GeoNode node, Map<UUID, GeoNode> byId) {
        byId.put(node.id(), node);
        for (GeoNode child : node.children()) {
            flatten(child, byId);
        }
    }
}
