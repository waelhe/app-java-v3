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
        "community :: neighborhood-feed-spi",
        // §11-Phase-7 — the second composite journey (the post/source
        // detail read, the readiness doc's P0-1 closure): the GraphQL
        // surface's two detail fields delegate to the community module's
        // declared post-detail SPI (the detail + comments legs — the
        // same getPost/getComments use cases the REST surface invokes).
        // The neighborhood-feed-spi pattern extended to the journey's
        // own seam (the interface, never the internals).
        "community :: neighborhood-post-detail-spi",
        // P1/P2 (the Saleor parity plan's catalog read, buyer commerce,
        // and seller fulfillment waves) — the GraphQL surface's seams
        // delegate to the owning modules' declared SPIs: media's admin
        // seam, orders' commerce and seller-management seams, payments'
        // client seam (the ProductAdminSpi pattern; the ports moved out
        // of shared.api by the ArchitectureRulesTest framework-neutrality
        // verdict, and these allowlist entries make each dependency an
        // explicit, verified fact — the catalog-spi precedent).
        "media :: product-media-admin",
        "orders :: order-commerce",
        "payments :: payment-client",
        "orders :: seller-order-management"
    }
)
package com.marketplace.app;
