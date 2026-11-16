@org.springframework.modulith.NamedInterface("realestate")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-jpa",
        "catalog :: catalog-spi"
    }
)
package com.marketplace.realestate;
