package com.marketplace.console;

import org.springframework.modulith.PackageInfo;

/**
 * B-15 (compliance plan C.5 — the platform identity's «الأنظمة العرضية»
 * row: the advanced console): feature flags + remote configuration on
 * the two measured halves of Boot's own external-config machinery —
 * the STATIC half bound at boot through
 * {@code @ConfigurationProperties} (the {@link ConsoleProperties}
 * record), the OPERATIONAL half as DATA rows checked at request time
 * (the C.10 measured limit verbatim: Boot's configuration governs the
 * static at boot EXCLUSIVELY — never a geographic/operational
 * {@code @ConditionalOnProperty}, its mechanism is boot-time not
 * request-time). The console's ceiling is the identity statement's own
 * active limit: «اللوحة إعداد وتشغيل ومحتوى وسياسات؛ والقدرة غير
 * الموجودة كوداً تبقى تطويراً» — the console configures, operates, and
 * reads what EXISTS in code; a capability not present in code stays
 * development.
 */
@PackageInfo
public final class ConsoleModule {
    private ConsoleModule() {
    }
}
