@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
@org.springframework.modulith.NamedInterface("catalog-spi")
package com.marketplace.catalog;
