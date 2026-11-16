package com.marketplace.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The public retrieval boundary's withdrawal guard (row 32 of the agent
 * findings 2026-10-25, CWE-367) — a delegating {@link VectorStore} view
 * that drops every retrieved document belonging to a durably withdrawn
 * source.
 *
 * <p><b>Why retrieval needs its own guard when the write protocol already
 * serializes:</b> the publication/withdrawal protocol in
 * {@link AiKnowledgeGateway} closes the ordering race going forward, but
 * defense in depth is the finding's own requirement — whatever a stale
 * vector is (a row written before the protocol existed, a replay the
 * registry resubmits, an operator's manual insert), it must never disclose
 * withdrawn content on the public retrieval paths. This decorator is that
 * guarantee: it composes the exact-keyed {@link AiWithdrawnSourceStore}
 * read (never approximate vector recall — see the store's javadoc for why
 * ANN recall must not serve as an existence oracle) over every
 * {@code similaritySearch} result, at the two retrieval sites the AI
 * auto-configuration wires: the stock {@code QuestionAnswerAdvisor} path
 * and the optional TypeSafe RAG retriever.</p>
 *
 * <p><b>Scope discipline:</b> writes ({@code add} / {@code delete}) are
 * passed through untouched — they belong to the gateway's serialized
 * protocol, and this view must never mask or retry one. Documents whose
 * metadata carries no {@code sourceId} were not written by the knowledge
 * gateway and are therefore not knowledge sources — they pass through
 * untouched. The wrapped store's {@code getName()} and
 * {@code getNativeClient()} are preserved so observability and native
 * client access see the real store, not this view.</p>
 */
public final class AiWithdrawalGuardedVectorStore implements VectorStore {

    private final VectorStore delegate;
    private final AiWithdrawnSourceStore withdrawnSources;
    private final com.marketplace.shared.api.KnowledgeSourceStatePort knowledgeSourceState;

    /**
     * Row 26 (agent findings 2026-10-26 14:33 export): the authoritative
     * source-state port joins the guard — see {@code sourceTypeFilter}'s
     * javadoc below.
     */
    public AiWithdrawalGuardedVectorStore(VectorStore delegate, AiWithdrawnSourceStore withdrawnSources,
                                          com.marketplace.shared.api.KnowledgeSourceStatePort knowledgeSourceState) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.withdrawnSources = Objects.requireNonNull(withdrawnSources, "withdrawnSources must not be null");
        this.knowledgeSourceState = Objects.requireNonNull(
                knowledgeSourceState, "knowledgeSourceState must not be null");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        List<Document> retrieved = delegate.similaritySearch(request);
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        Set<String> sourceIds = retrieved.stream()
                .map(document -> document.getMetadata().get(AiKnowledgeGateway.SOURCE_ID_METADATA_KEY))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
        if (sourceIds.isEmpty()) {
            return retrieved;
        }
        Set<String> withdrawn = withdrawnSources.withdrawnAmong(sourceIds);
        return retrieved.stream()
                .filter(document -> {
                    Object sourceId = document.getMetadata().get(AiKnowledgeGateway.SOURCE_ID_METADATA_KEY);
                    return sourceId == null || !withdrawn.contains(sourceId.toString());
                })
                // Row 26 (agent findings 2026-10-26 14:33 export): the
                // AUTHORITATIVE source-state filter — the belt-and-braces
                // half's asynchronous twin. The durable withdrawal record is
                // written by an AFTER_COMMIT listener, so a window exists in
                // which a withdrawn-or-purged source's passages answer with
                // NO record (the record's absence was treated as
                // permission). The knowledge module's own live fact — the
                // entry exists and its authored text is not the purge
                // tombstone — is read synchronously per knowledge source in
                // the retrieval set (the DisputeLookupPort pattern: the data
                // owner answers, the consumer asks; bounded point reads,
                // never recall-wide), and a source that is not live is
                // dropped exactly like a durably withdrawn one. The
                // recommendation's own words: "check authoritative source
                // state before returning indexed passages. Keep
                // asynchronous vector deletion as cleanup."
                .filter(this::knowledgeSourceIsLive)
                .toList();
    }

    /**
     * Row 26's per-document authoritative check: knowledge-entry sources
     * (the {@code sourceType} the gateway stamps) must be live in the
     * knowledge module's own table. Documents from any other source type
     * — or without a resolvable entry id — are none of this guard's
     * business and pass through.
     */
    private boolean knowledgeSourceIsLive(Document document) {
        Object sourceType = document.getMetadata().get("sourceType");
        if (!"knowledge-entry".equals(sourceType)) {
            return true;
        }
        Object sourceId = document.getMetadata().get(AiKnowledgeGateway.SOURCE_ID_METADATA_KEY);
        if (sourceId == null) {
            return true;
        }
        // CodeRabbit review round on #741's part 4 (2026-10-28, Minor —
        // Security & Privacy): the try block wrapped the executor call
        // too, so an IllegalArgumentException thrown BY the authoritative
        // state read answered "live" and let a withdrawn source's
        // document pass — an open failure inside a security guard. The
        // parse stays inside the try (its unparseable stranger keeps the
        // documented pass-through); the executor now speaks for itself,
        // fail-closed at the caller on any error of its own.
        java.util.UUID entryId;
        try {
            entryId = java.util.UUID.fromString(sourceId.toString());
        } catch (IllegalArgumentException unparseable) {
            // Not an entry id at all — the metadata contract's own stranger.
            return true;
        }
        return knowledgeSourceState.isSourceContentLive(entryId);
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> documentIds) {
        delegate.delete(documentIds);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }
}
