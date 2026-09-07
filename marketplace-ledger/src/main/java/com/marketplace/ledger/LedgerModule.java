package com.marketplace.ledger;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Ledger",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa", "shared :: shared-security"})
public class LedgerModule {
}
