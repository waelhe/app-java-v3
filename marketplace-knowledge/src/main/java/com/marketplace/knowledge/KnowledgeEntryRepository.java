package com.marketplace.knowledge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * B-14 (compliance plan C.4): the guide's repository — the house's mixed
 * discipline (derived queries for the fixed-shape reads, a declared
 * NATIVE {@code @Query} for the full-text path — the
 * {@code ProviderListingRepository.searchFullText} precedent verbatim:
 * the official PostgreSQL {@code websearch_to_tsquery}, the
 * {@code ts_rank} relevance order with the {@code id} tiebreak, the
 * explicit {@code is_deleted = false} the native path must carry
 * itself), the SERVICE passing every plain sort (the stable-order L32
 * lesson).
 */
public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, UUID> {

    /**
     * The guide's board for one neighborhood: the category axis optional
     * (null = the whole guide), the stable {@code (created_at, id)}
     * order keeping the page boundary deterministic (D-N5).
     */
    Page<KnowledgeEntry> findByLocationIdOrderByCreatedAtDescIdDesc(UUID locationId, Pageable pageable);

    Page<KnowledgeEntry> findByLocationIdAndCategoryOrderByCreatedAtDescIdDesc(
            UUID locationId, KnowledgeCategory category, Pageable pageable);

    /** The contributor's own entries — their contributions to the guide. */
    Page<KnowledgeEntry> findByAuthorIdOrderByCreatedAtDescIdDesc(UUID authorId, Pageable pageable);

    /**
     * The full-text path (the catalog precedent verbatim): the official
     * {@code websearch_to_tsquery} over the entry's coalesced
     * title+body — the same tokenizer the V156 GIN index carries — with
     * the optional category axis composed into the SAME query (the R6
     * composed-search lesson: the text query never silently drops a
     * riding filter), ranked by {@code ts_rank} with the {@code id}
     * tiebreak. The native path carries {@code is_deleted = false}
     * itself (the @SoftDelete filter only covers derived/JPQL reads).
     */
    @Query(value = """
            SELECT * FROM knowledge_entries
            WHERE is_deleted = false
              AND to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(body, ''))
                  @@ websearch_to_tsquery('simple', :query)
              AND (:category IS NULL OR category = :category)
            ORDER BY ts_rank(
                    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(body, '')),
                    websearch_to_tsquery('simple', :query)
                ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM knowledge_entries
                    WHERE is_deleted = false
                      AND to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(body, ''))
                          @@ websearch_to_tsquery('simple', :query)
                      AND (:category IS NULL OR category = :category)
                    """,
            nativeQuery = true)
    Page<KnowledgeEntry> searchFullText(@Param("query") String query,
                                        @Param("category") String category,
                                        Pageable pageable);

    /** One entry — the detail read / the author's own scoping. */
    Optional<KnowledgeEntry> findById(UUID id);
}
