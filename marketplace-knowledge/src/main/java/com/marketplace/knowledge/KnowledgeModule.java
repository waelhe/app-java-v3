package com.marketplace.knowledge;

import org.springframework.modulith.PackageInfo;

/**
 * B-14 (compliance plan C.4 — the platform identity's «تعرف على» row:
 * a community-built integrated guide about the neighborhood and its
 * residents): the knowledge module on the reviews-pattern
 * self-contained shape, with the search integration riding EVENTS —
 * the entry's publish/withdraw facts (KnowledgeEntryPublishedEvent /
 * KnowledgeEntryWithdrawnEvent) live in shared/api per the
 * events-through-Modulith rule (the contracts ledger §1 placement:
 * cross-boundary records land there with the first cross-boundary
 * consumer), carried COMPLETE so the consumer never re-derives
 * anything.
 */
@PackageInfo
public final class KnowledgeModule {
    private KnowledgeModule() {
    }
}
