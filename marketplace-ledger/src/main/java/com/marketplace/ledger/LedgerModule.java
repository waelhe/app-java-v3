package com.marketplace.ledger;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Ledger",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa"})
public class LedgerModule {
}
