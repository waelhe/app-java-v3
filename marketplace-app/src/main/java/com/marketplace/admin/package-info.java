@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "booking :: booking-spi",
        "catalog :: catalog-spi",
        "identity",
        "payments"
    }
)
@org.springframework.modulith.NamedInterface("admin-api")
package com.marketplace.admin;
