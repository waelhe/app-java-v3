@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "catalog :: catalog-api",
        "catalog :: catalog-spi",
        "messaging :: messaging-api",
        "ai",
        // §5.1 — the unified search's community leg: the dispatcher reads
        // through the community module's OWN named SPI (the search seam
        // d7ed2f20 wired; the interface, not the internals).
        "community :: community-post-search-spi",
        // §11-Phase-6 — the first composite journey (the home read): the
        // GraphQL surface's two home fields delegate to their owning
        // modules' declared read SPIs — the discovery rails and the
        // neighborhood live feed — the catalog-spi pattern extended to
        // the journey's two seams (the interface, never the internals:
        // the CI-measured verification round that caught the missing
        // pair, exactly what this allowlist exists to make explicit).
        "discovery :: discovery-spi",
        "community :: neighborhood-feed-spi"
    }
)
package com.marketplace.app;
