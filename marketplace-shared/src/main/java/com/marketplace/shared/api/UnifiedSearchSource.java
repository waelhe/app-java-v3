package com.marketplace.shared.api;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the vocabulary of the sources a unified search
 * consults. Stored/crossed as the enum name only (the shared-api
 * vocabulary convention — owning modules keep their own types home).
 */
public enum UnifiedSearchSource {
    COMMUNITY_POST,
    COMMUNITY_EVENT,
    KNOWLEDGE,
    INSTITUTION
}
