@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "booking",
        "catalog",
        "identity",
        "payments"
    }
)
@org.springframework.modulith.NamedInterface("admin-api")
package com.marketplace.admin;
