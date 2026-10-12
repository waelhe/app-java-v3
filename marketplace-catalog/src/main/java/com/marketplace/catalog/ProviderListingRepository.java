package com.marketplace.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProviderListingRepository extends JpaRepository<ProviderListing, UUID>, JpaSpecificationExecutor<ProviderListing>, RevisionRepository<ProviderListing, UUID, Integer> {

    /**
     * The GraphQL demo surface's read (CatalogSpi.findAll — unpaged).
     * L37 deliberately leaves THIS surface on the derived query: it is the
     * unpaged legacy Service listing, which never carried an ordering
     * contract (boost-blind, like the sitemap — the ordered public reads
     * are the Specification-backed surfaces; see
     * ProviderListingSpecifications#boostFirst).
     */
    Page<ProviderListing> findByStatus(ListingStatus status, Pageable pageable);

    /**
     * JT-20 (#536 discovery waves D1-D4): the followed-sources rail's
     * read — the {@code ProviderListingsPort} implementation's
     * eligibility floor (ACTIVE only; soft-deleted rows are excluded
     * automatically through the entity's @SoftDelete). Derived, so the
     * storefront's own state semantics ride the repository mechanism —
     * the adapter stamps the rail's complete deterministic order through
     * the Pageable (createdAt DESC, id DESC — the D-N5 discipline), the
     * same shape {@code findByStatus} answers to today.
     */
    Page<ProviderListing> findByProviderIdInAndStatus(Collection<UUID> providerIds,
                                                      ListingStatus status,
                                                      Pageable pageable);

    /**
     * L33: the expiry job's scan — ACTIVE listings whose publication
     * window passed (strictly before: the boundary instant stays ACTIVE
     * until the next tick). Deterministic id order for stable paging.
     */
    Page<ProviderListing> findByStatusAndExpiresAtBefore(ListingStatus status,
                                                          java.time.Instant expiresAt,
                                                          Pageable pageable);

    /**
     * L32 (realestate systems plan): the ACTIVE listing id set — the
     * restriction the area-sorted search flow passes into the realestate
     * filter port. JPQL (the soft-delete filter applies automatically
     * through the entity's @SoftDelete).
     */
    @Query("select l.id from ProviderListing l where l.status = ?1")
    Set<UUID> findIdsByStatus(ListingStatus status);

    /**
     * W6 (search-unit compliance pass): the criteria-eligible ACTIVE id
     * set as an ID-ONLY JPQL projection — the official projections
     * discipline ("you're usually better off using a projection that only
     * exposes the subset", Spring Data JPA reference › Projections): the
     * former {@code findAll(spec)} composition materialized FULL entities
     * (title + description + every column) only to discard all but the id.
     * The optional predicates are the NULL-guarded blocks the native
     * criteria queries already speak; a NULL input parameter in an
     * {@code IS NULL} comparison is standard JPQL (Jakarta Persistence
     * BNF: {@code null_comparison_expression ::= {state_field_path_expression
     * | input_parameter} IS [NOT] NULL}), and every parameter also rides
     * a typed comparison so binding inference is unambiguous. Soft-deleted
     * rows are excluded automatically through the entity's @SoftDelete.
     */
    @Query("""
            select l.id from ProviderListing l
            where l.status = :status
              and (:category is null or l.category = :category)
              and (:minPrice is null or l.priceCents >= :minPrice)
              and (:maxPrice is null or l.priceCents <= :maxPrice)
              and (:guests is null or (l.maxGuests is not null and l.maxGuests >= :guests))
            """)
    Set<UUID> findIdsMatchingCriteria(@Param("status") ListingStatus status,
                                      @Param("category") String category,
                                      @Param("minPrice") Long minPrice,
                                      @Param("maxPrice") Long maxPrice,
                                      @Param("guests") Integer guests);

    /**
     * W3 (yelp-level plan §5 — G17): the rating-floor flow's eligible id
     * set — the ACTIVE listings owned by the floor-answering providers
     * (soft-deleted rows excluded through the entity's @SoftDelete). The
     * property text flow intersects this with its facet-matching id set.
     */
    @Query("select l.id from ProviderListing l where l.status = ?1 and l.providerId in ?2")
    Set<UUID> findIdsByStatusAndProviderIdIn(ListingStatus status, Collection<UUID> providerIds);

    /**
     * W3 (G19, the review round's root fix): the favorites view's read —
     * the batch INCLUDING soft-deleted listings. Hibernate's @SoftDelete
     * filter hides withdrawn rows from every generated query (findAllById
     * included), but a saved listing that its provider later withdrew
     * STAYS saved (the relation is the member's own data, b-5); the view
     * must carry its title and current truth. Native SQL is the one
     * channel that sees those rows (the export adapters' own reasoning).
     */
    @Query(value = "select * from provider_listings where id in (?1)", nativeQuery = true)
    List<ProviderListing> findAllByIdIncludingDeleted(Collection<UUID> ids);

    /**
     * W3 (yelp-level plan §5 — G18): the ranking job's KEYSET page — the
     * clean-ACTIVE set (status ACTIVE, not yet expired — the sitemap's own
     * clean-set law: never rank what is about to leave the public surface)
     * in deterministic id order, advancing by {@code id > :afterId} (the
     * saved-search scan's own fix: an offset would shift under concurrent
     * inserts and skip rows; a keyset cannot). Soft-deleted rows are
     * excluded automatically through the entity's @SoftDelete.
     */
    @Query("""
            select l from ProviderListing l
            where (:afterId is null or l.id > :afterId)
              and l.status = com.marketplace.catalog.ListingStatus.ACTIVE
              and (l.expiresAt is null or l.expiresAt > :now)
            order by l.id asc
            """)
    List<ProviderListing> findRankableAfter(UUID afterId, java.time.Instant now, Pageable pageable);

    /**
     * L39 (realestate systems plan §5 — SEO): the sitemap page — the
     * clean ACTIVE set's id + updated_at projection (exactly the two
     * facts a sitemaps.org {@code <url>} entry carries; a 50,000-URL
     * page must never load full entities). The expiry term is
     * deliberately stricter than the L33 job's window
     * ({@code findByStatusAndExpiresAtBefore}): a listing whose window
     * has already passed — still ACTIVE in status between the job's
     * half-hour ticks — is never advertised, because its page is about
     * to answer 404. Soft-deleted rows are excluded structurally by the
     * entity's {@code @SoftDelete}. Deterministic id order (the L32
     * total-order rule) comes from the service's Pageable.
     */
    @Query("""
            select new com.marketplace.catalog.SitemapEntry(l.id, l.updatedAt)
            from ProviderListing l
            where l.status = ?1 and (l.expiresAt is null or l.expiresAt > ?2)
            """)
    Page<SitemapEntry> findSitemapEntries(ListingStatus status,
                                          java.time.Instant now,
                                          Pageable pageable);

    /**
     * L39: the count-only twin of {@link #findSitemapEntries} — the
     * root request's page-count decision (urlset vs sitemap index) rides
     * this, never a row fetch (CodeRabbit round 1: a multi-page root must
     * not materialize a 50,000-row page only to discard it). Same clean
     * ACTIVE predicate, same soft-delete structural exclusion.
     */
    @Query("""
            select count(l) from ProviderListing l
            where l.status = ?1 and (l.expiresAt is null or l.expiresAt > ?2)
            """)
    long countSitemapEntries(ListingStatus status, java.time.Instant now);

    /**
     * Full-text search using PostgreSQL tsvector with GIN index.
     * Searches title and description columns.
     * Matches the GIN index defined in V9__search_index.sql (rebuilt with
     * the 'arabic' configuration by V106__search_fts_arabic_config.sql).
     *
     * <p>Uses the official {@code websearch_to_tsquery} — the PostgreSQL function
     * designed for raw user input: "simple unformatted text is a valid query"
     * and arbitrary special characters never raise a tsquery syntax error
     * (PostgreSQL 18 reference, Functions › Text Search › Parsing Queries,
     * example: {@code websearch_to_tsquery('english', '""" )( dummy \ query <->')}
     * parses to {@code 'dummi' & 'queri'}). The previous {@code to_tsquery} form
     * received mangled user input and raised SQL exceptions (HTTP 500) for any
     * input containing quotes, parentheses or a leading dash. Users also gain
     * the officially supported web-search operators: {@code "quoted phrase"},
     * {@code OR}, and {@code -exclusion}.
     *
     * <p><b>W6 (search-unit compliance pass) — the 'arabic' text search
     * configuration:</b> the tsvector/tsquery configuration moves from
     * 'simple' to the officially generated {@code arabic} configuration
     * (initdb creates it on every standard PostgreSQL install — the
     * snowball {@code arabic_stem} dictionary). Official basis: the
     * 'simple' template performs "no more than lower-casing" (docs 18
     * §12.6.2), leaving the Arabic definite article, tashkeel and affix
     * agglutination inside the lexeme — a 'شقة' query could not match
     * 'الشقة' or 'شَقَّة'; the Snowball dictionaries exist precisely
     * because "each algorithm understands how to reduce common variant
     * forms of words to a base, or stem, spelling within its language"
     * (docs 18 §12.6.6). The Arabic stemmer's rules operate on Arabic
     * script only, so Latin-script tokens pass through lowercased and
     * unchanged — mixed-language titles keep their exact 'simple'-era
     * behavior for Latin words. The GIN index (V106) carries the
     * IDENTICAL tsvector expression, as the official indexing contract
     * requires (the index must match the query's expression; GIN is "the
     * preferred text search index type" — docs 18 §12.9).</p>
     *
     * <p><b>L37 (realestate systems plan §5 — the featured boost):</b> the baked
     * ORDER BY gains the boost flag FIRST — "المعزّز أولًا داخل نفس الفرز
     * الأساسي": boosted matches outrank organic ones, relevance ranks WITHIN
     * each group (Q1 resolved by the plan's own uniform rule — the alternative,
     * a relevance-multiplying auction, is the system the plan explicitly
     * excludes). W6 (search-unit compliance pass) unifies the flag's
     * definition with the ONE boost specification every ordered surface
     * composes ({@code ProviderListingSpecifications#boostFirst}): promoted
     * window OR a live paid campaign with remaining budget (the W5 G24 law
     * «المُروَّج بلا ميزانية لا يتصدر») — the flag is the TOTAL boolean
     * {@code (promoted_until IS NOT NULL AND promoted_until > :now) OR EXISTS
     * (...)} — never NULL (a false AND anything is false, a non-match EXISTS
     * is false), so PostgreSQL's NULLS-FIRST-on-DESC trap cannot rank
     * unboosted rows first, and an expired window or exhausted budget
     * evaluates false at query time (self-correcting — no cleanup job). The
     * campaign EXISTS is answered per row by the partial index
     * {@code idx_ad_campaigns_listing_live} (V103). {@code :now} is
     * bound from the service's injected Clock (the expiry test's seam); it
     * rides the CONTENT query only — the count query never references it,
     * which Spring Data's count binding officially tolerates (LENIENT error
     * handling silently skips a named parameter absent from the count string —
     * measured in the 4.1.1 sources). The id tiebreak is appended (the L32
     * total-order rule — equal {@code ts_rank} values must not wobble across
     * pages; this closes the latent gap where the unrestricted text path
     * ordered by rank alone).</p>
     *
     * <p><b>R6 (comprehensive-review-ar fix plan §4, Wave 5 — the composed
     * text+filter search):</b> the optional catalog predicates (category /
     * price bounds / guests) join the text predicate in BOTH the content and
     * the count query — the exact optional-predicate blocks the criteria
     * surfaces have carried since I6 (a NULL parameter deactivates its
     * block; the guests block honors the undeclared-capacity contract
     * {@code max_guests IS NOT NULL AND max_guests >= :guests} — the same
     * blocks {@link #findIdsMatchingCriteria} speaks in JPQL now that the
     * native criteria twin is retired).
     * A text query no longer drops the filters that ride it — the review's
     * R6 finding; the composition happens BEFORE the count and the
     * pagination, so the page totals describe the filtered set.</p>
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('arabic', :query)
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                ts_rank(
                    to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,'')),
                    websearch_to_tsquery('arabic', :query)
                ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('arabic', :query)
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullText(@Param("query") String query,
                                         @Param("category") String category,
                                         @Param("minPrice") Long minPrice,
                                         @Param("maxPrice") Long maxPrice,
                                         @Param("guests") Integer guests,
                                         @Param("now") java.time.Instant now,
                                         Pageable pageable);

    /**
     * Typo-tolerant fallback search using the official pg_trgm extension
     * (installed by V34): {@code word_similarity} compares the query's
     * trigram set against every continuous extent of the indexed text, so
     * a one-edit typo ("gardn" → "garden") still scores high similarity.
     * Ranked by descending word similarity. Uses the GIN trigram index
     * {@code idx_listing_search_trgm} (same expression and partial
     * predicate as the FTS index — V34).
     *
     * <p>Operator {@code query <% text}: true when
     * {@code word_similarity(query, text) >= pg_trgm.word_similarity_threshold}
     * — the framework default (0.6), deliberately not overridden: threshold
     * tuning is a measurement-backed decision, not a code default.
     *
     * <p><b>R6 (Wave 5):</b> the fallback carries the SAME optional catalog
     * predicates as {@link #searchFullText} — a fallback that dropped the
     * filters would answer the typo-tolerated text match set unfiltered
     * (the count and the pages would lie about the filtered reality).
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilar(@Param("query") String query,
                                        @Param("category") String category,
                                        @Param("minPrice") Long minPrice,
                                        @Param("maxPrice") Long maxPrice,
                                        @Param("guests") Integer guests,
                                        @Param("now") java.time.Instant now,
                                        Pageable pageable);

    // W6 (search-unit compliance pass): the two NATIVE criteria queries
    // (searchByCriteria / searchByCriteriaRestricted) were RETIRED — the
    // criteria paths ride the official Specifications composition
    // (CatalogService.findBoostFirst + ProviderListingSpecifications) now,
    // the same shape every other ordered filter surface already uses.
    // Root causes measured against the official Spring Data JPA reference
    // (JPA Query Methods › Query Introspection and Rewriting) and the
    // 4.1.1 framework sources (AbstractStringBasedJpaQuery.getSortedQuery →
    // DefaultQueryEnhancer → QueryUtils.applySorting):
    //   1. a SORTED Pageable on a string-based @Query method gets its sort
    //      APPENDED to the baked ORDER BY — for these queries the regex
    //      detector misreads the parenthesized WHERE + ORDER BY as a
    //      window/subselect occurrence, so a SECOND "order by" clause is
    //      appended: invalid SQL, a syntax error, an HTTP 500 for every
    //      windowed+sorted or text+sorted request. The Specification path
    //      is sort-aware by construction (QueryUtils.toOrders over entity
    //      attributes) — the port contract's documented sort-bearing
    //      requests (PagedRequest) finally resolve.
    //   2. the native ORDER BY baked its own boost flag — the criteria
    //      surfaces now share the ONE boost specification every other
    //      surface composes (promoted window OR live campaign — the W5
    //      G24 law «المُروَّج بلا ميزانية لا يتصدر»), instead of a second,
    //      drifting definition.
    // The provider-set restriction composes as hasProviderIdIn — the
    // database's predicate AND owns the intersection of the availability
    // whitelist and the stars floor (no Java-side set algebra).

    /**
     * L27: the window-restricted full-text search — mirrors
     * {@link #searchFullText} (official {@code websearch_to_tsquery}
     * ranking, plus the pg_trgm typo-tolerance fallback), with the
     * {@code provider_id IN (:providerIds)} restriction applied to both
     * queries and their counts.
     *
     * <p>Fallback condition (PR #256 full-review round): the fallback runs
     * only when NO full-text match exists at all
     * ({@code getTotalElements() == 0}) — an out-of-range page over real
     * matches is legitimately empty ({@code isEmpty()} is true while
     * {@code getTotalElements() > 0}) and must stay an honest empty page,
     * not be replaced by the similarity result set.
     *
     * <p><b>R6 (Wave 5):</b> the optional catalog predicates join the text
     * predicate and the provider restriction in BOTH queries and counts —
     * the windowed text search no longer drops the filters that ride it.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND provider_id IN (:providerIds)
              AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('arabic', :query)
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                ts_rank(
                    to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,'')),
                    websearch_to_tsquery('arabic', :query)
                ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND provider_id IN (:providerIds)
                      AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('arabic', :query)
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullTextRestricted(@Param("query") String query,
                                                   @Param("category") String category,
                                                   @Param("minPrice") Long minPrice,
                                                   @Param("maxPrice") Long maxPrice,
                                                   @Param("guests") Integer guests,
                                                   @Param("providerIds") java.util.Collection<UUID> providerIds,
                                                   @Param("now") java.time.Instant now,
                                                   Pageable pageable);

    /**
     * Typo-tolerant fallback of the restricted FTS — same contract as
     * {@link #searchSimilar}, plus the provider whitelist.
     *
     * <p><b>R6 (Wave 5):</b> carries the SAME optional catalog predicates
     * as {@link #searchFullTextRestricted} — the fallback never drops the
     * filters.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND provider_id IN (:providerIds)
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND provider_id IN (:providerIds)
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilarRestricted(@Param("query") String query,
                                                  @Param("category") String category,
                                                  @Param("minPrice") Long minPrice,
                                                  @Param("maxPrice") Long maxPrice,
                                                  @Param("guests") Integer guests,
                                                  @Param("providerIds") java.util.Collection<UUID> providerIds,
                                                  @Param("now") java.time.Instant now,
                                                  Pageable pageable);

    // L32 (realestate systems plan §5) — listing-id-restricted variants (the
    // property-facet flow: the realestate module's matching-id set composes
    // onto the catalog query in the same restricted-branch shape). The
    // caller guarantees a NON-EMPTY collection (the empty set short-circuits
    // to an honest empty page before any query). Text queries keep their
    // relevance ranking with id as the tiebreaker.

    /**
     * <p><b>R6 (Wave 5):</b> the optional catalog predicates join the text
     * predicate and the id restriction in BOTH the content and the count
     * query — the property flow's text branch no longer drops the filters
     * that ride it, and the saved-search matcher's membership probe composes
     * the same way.</p>
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND id IN (:listingIds)
              AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('arabic', :query)
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                ts_rank(
                    to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,'')),
                    websearch_to_tsquery('arabic', :query)
                ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND id IN (:listingIds)
                      AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('arabic', :query)
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullTextRestrictedToListings(@Param("query") String query,
                                                             @Param("category") String category,
                                                             @Param("minPrice") Long minPrice,
                                                             @Param("maxPrice") Long maxPrice,
                                                             @Param("guests") Integer guests,
                                                             @Param("listingIds") Collection<UUID> listingIds,
                                                             @Param("now") java.time.Instant now,
                                                             Pageable pageable);

    /**
     * Typo-tolerant fallback of the listing-id-restricted FTS — same
     * contract as {@link #searchSimilar}, plus the listing whitelist.
     *
     * <p><b>R6 (Wave 5):</b> carries the SAME optional catalog predicates
     * as {@link #searchFullTextRestrictedToListings} — the fallback never
     * drops the filters.</p>
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND id IN (:listingIds)
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY ((promoted_until IS NOT NULL AND promoted_until > :now)
              OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                         WHERE campaign.listing_id = provider_listings.id
                           AND campaign.is_deleted = false
                           AND campaign.status = 'ACTIVE'
                           AND campaign.consumed_cents < campaign.budget_cents
                           AND (campaign.ends_at IS NULL OR campaign.ends_at > :now))) DESC,
                word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND id IN (:listingIds)
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilarRestrictedToListings(@Param("query") String query,
                                                            @Param("category") String category,
                                                            @Param("minPrice") Long minPrice,
                                                            @Param("maxPrice") Long maxPrice,
                                                            @Param("guests") Integer guests,
                                                            @Param("listingIds") Collection<UUID> listingIds,
                                                            @Param("now") java.time.Instant now,
                                                            Pageable pageable);
}
