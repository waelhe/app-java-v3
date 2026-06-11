@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "catalog :: catalog-api",
        "messaging :: messaging-api"
    }
)
package com.marketplace.app;
