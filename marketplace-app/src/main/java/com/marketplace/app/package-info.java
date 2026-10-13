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
        "community :: community-post-search-spi"
    }
)
package com.marketplace.app;
