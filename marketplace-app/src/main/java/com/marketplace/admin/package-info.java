@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "booking :: booking-spi",
        "catalog :: catalog-spi",
        "identity :: identity-spi",
        "payments :: payments-spi"
    }
)
package com.marketplace.admin;
