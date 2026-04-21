@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "booking",
        "catalog",
        "identity",
        "payments"
    }
)
package com.marketplace.admin;
