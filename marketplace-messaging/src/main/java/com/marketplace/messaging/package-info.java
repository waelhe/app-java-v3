@org.springframework.modulith.NamedInterface("messaging")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-jpa",
        "shared :: shared-config",
        "catalog :: catalog-spi"
    }
)
package com.marketplace.messaging;
