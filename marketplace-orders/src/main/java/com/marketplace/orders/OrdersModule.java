package com.marketplace.orders;

import org.springframework.modulith.PackageInfo;

/**
 * A-11 (compliance plan wave C: C.1) — the orders module's explicit
 * {@link PackageInfo} declaration (the type-level alternative the framework
 * documents), mirroring the {@code reviews} module precedent: the package's
 * module contract itself is declared in {@code package-info.java}
 * (allowedDependencies on the shared foundation's three named interfaces
 * only), and this type anchors the module for verification tooling.
 */
public final class OrdersModule {
    private OrdersModule() {
    }
}
