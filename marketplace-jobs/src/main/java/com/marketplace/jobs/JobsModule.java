package com.marketplace.jobs;

import org.springframework.modulith.PackageInfo;

/**
 * B-12 (compliance plan C.2 — the jobs module): the market's employment
 * vertical, built on the reviews pattern (the house's strongest
 * self-contained module shape: entity + repository + service + controller,
 * zero cross-module dependencies beyond the shared named interfaces, zero
 * application events — the module catalog stays untouched).
 */
@PackageInfo
public final class JobsModule {
    private JobsModule() {
    }
}
