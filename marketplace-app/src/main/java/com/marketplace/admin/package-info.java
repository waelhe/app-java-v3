@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-jpa",
        "booking :: booking-spi",
        "catalog :: catalog-spi",
        "identity :: identity-spi",
        "payments :: payments-spi"
    }
)
package com.marketplace.admin;
