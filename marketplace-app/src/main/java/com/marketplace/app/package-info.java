@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-storage",
        "catalog :: catalog-api",
        "catalog :: catalog-spi",
        "identity :: identity-admin-spi",
        "messaging :: messaging-api"
    }
)
package com.marketplace.app;
