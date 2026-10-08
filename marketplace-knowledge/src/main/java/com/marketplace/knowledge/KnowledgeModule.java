package com.marketplace.knowledge;

import org.springframework.modulith.PackageInfo;

/**
 * B-14 (compliance plan C.4 — the platform identity's «تعرف على» row:
 * a community-built integrated guide about the neighborhood and its
 * residents): the knowledge module on the reviews-pattern
 * self-contained shape, with the search integration riding EVENTS —
 * the entry's publish/withdraw facts are module-owned records on this
 * exposed interface (the B-06 disputes pattern), carried COMPLETE so
 * the eventual consumer (the search side — a reserve module today,
 * the late-lander rule's assignee) never re-derives anything.
 */
@PackageInfo
public final class KnowledgeModule {
    private KnowledgeModule() {
    }
}
