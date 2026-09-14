# 04 — Spring Data JPA + JDBC + Transactions (isolated official reference)

> **Tree refs:** 4.1 (JPA core), 4.2 (query methods), 4.3 (transactionality/locking/auditing/events/AOT), 4.5 (Envers) + Framework 2.8 (Transactions), 2.9 (JDBC), 2.11 (ORM).
> **Sources:** `https://docs.spring.io/spring-data/jpa/reference/` (4.1.1) + `https://docs.spring.io/spring-framework/reference/data-access/` (7.0.9) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. `[quote]`=verbatim · `[paraphrase]`=faithful restatement.
> **Isolation:** no internal repo data, no current system. **Encoding:** UTF-8.

---

## 1. JPA Core (4.1)

- MUST | The `Repository` interface is the central abstraction, typed to domain class + ID; domain objects are treated as DDD aggregates with identifiers. | [quote] | repositories/core-concepts.html
- MUST | Annotate any shared base repository interface with `@NoRepositoryBean`, or runtime instantiation fails. | [paraphrase] | repositories/definition.html
- AVOID | Mixing `@Entity` and `@Document` on domain types — Spring Data can't tell repositories apart → undefined behavior. | [paraphrase] | repositories/definition.html
- MUST | Create `LocalContainerEntityManagerFactoryBean`, not `EntityManagerFactory` directly (exception translation + factory creation). | [quote] | repositories/create-instances.html
- ATTEND | Spring Data JPA requires a `PlatformTransactionManager` bean named `transactionManager` unless an explicit `transaction-manager-ref` is set. | [paraphrase] | repositories/create-instances.html
- ATTEND | `save(...)` calls `persist(...)` for new entities and `merge(...)` for existing; new-vs-not detection defaults to Version-Property + Id-Property inspection. | [quote] | jpa/entity-persistence.html
- ATTEND | A PRIMITIVE `@Version` property cannot be used for new-vs-not detection — JPA treats 0 as the first version; use `Persistable` for manually-assigned ids. | [quote] | jpa/entity-persistence.html

## 2. Query Methods & Projections (4.2)

- ATTEND | Lookup strategies: `CREATE`, `USE_DECLARED_QUERY`, `CREATE_IF_NOT_FOUND` (default); `@Query` overrides `@NamedQuery`. | [paraphrase] | repositories/query-methods-details.html · jpa/query-methods.html
- MUST | Use non-null `Sort`/`Pageable`/`Limit` — pass `Sort.unsorted()`/`Pageable.unpaged()`/`Limit.unlimited()` instead of null. | [paraphrase] | repositories/query-methods-details.html
- MUST | Close `Stream<T>` query results (try-with-resources). | [paraphrase] | repositories/query-methods-details.html
- ATTEND | Derived query predicates (`StartingWith`, etc.) sanitize/escape wildcards to match as literals. | [quote] | jpa/query-methods.html
- ATTEND | With `@Modifying`, `clearAutomatically=true` is needed ONLY when the persistence context must be cleared afterward — decide per query, it is not mandatory everywhere. | [paraphrase] | jpa/query-methods.html
- ATTEND | Bulk (derived-delete) issues a single JPQL query and skips lifecycle callbacks, but loads all results into memory first. | [paraphrase] | jpa/query-methods.html
- MUST | **Closed interface projections** (getters matching entity properties only) are optimized by Spring Data to select just those columns. | [quote] | repositories/projections.html
- ATTEND | **Class/DTO projections** (incl. Java Records) are a separate mechanism — constructor-binding DTOs provide value semantics but are NOT the same "closed/optimized" interface projections. | [paraphrase] | repositories/projections.html
- MUST | For DTO via JPQL, define a constructor expression (`SELECT new com.x.NamesOnly(...)`) and provide an all-args constructor. | [paraphrase] | repositories/projections.html
- ATTEND | QBE ignores null fields by default, but supports NO nested/grouped constraints, NO collections/maps, NO regex (with JPA). | [quote] | repositories/query-by-example.html

## 3. Transactionality (4.3 + 2.8)

- ATTEND | Repository CRUD inherits `@Transactional` from `SimpleJpaRepository`: read ops → `readOnly=true`; others → plain `@Transactional`. | [quote] | jpa/transactions.html
- MUST | Define transaction boundaries at the service/facade layer, not per CRUD call — an outer transaction overrides repository-level settings. | [paraphrase] | jpa/transactions.html
- MUST | Use `readOnly=true` for queries — with Hibernate it sets flush mode to `MANUAL`, skipping dirty checks (a real improvement on large object trees). | [quote] | jpa/transactions.html
- ATTEND | `readOnly` is a hint, NOT a check — a manipulating query is not blocked by it. | [paraphrase] | jpa/transactions.html
- MUST | Annotate concrete class methods with `@Transactional`, not interface methods — interface annotations are silently ignored in AspectJ (rollback may silently fail). | [quote] | spring-framework/reference/data-access/transaction/declarative/annotations.html
- ATTEND | `@Transactional` works through the AOP proxy — self-invocation does NOT start a transaction; use external calls or AspectJ mode. | [quote] | spring-framework/reference/data-access/transaction/declarative/annotations.html
- ATTEND | Default `@Transactional` settings: propagation REQUIRED, isolation DEFAULT, read-write, no timeout; rollback on RuntimeException/Error only. | [quote] | spring-framework/reference/data-access/transaction/declarative/annotations.html
- MUST | Use `rollbackFor` for checked exceptions; default does NOT roll back on checked exceptions. | [paraphrase] | spring-framework/reference/data-access/transaction/declarative/rolling-back.html
- AVOID | The pattern `"Exception"` in rollback rules — matches nearly everything and hides other rules. | [quote] | spring-framework/reference/data-access/transaction/declarative/rolling-back.html
- ATTEND | `isolation`/`timeout`/`readOnly` apply only to REQUIRED or REQUIRES_NEW propagation. | [quote] | spring-framework/reference/data-access/transaction/declarative/annotations.html
- ATTEND | REQUIRED: participating tx joins the outer scope, silently ignoring local isolation/timeout/readOnly; inner rollback-only → outer gets `UnexpectedRollbackException`. | [paraphrase] | spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
- AVOID | `REQUIRES_NEW` without a connection pool sized > concurrent threads + 1 — pool exhaustion + deadlock risk. | [quote] | spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
- ATTEND | NESTED uses savepoints in one physical tx — works only with JDBC resource transactions. | [paraphrase] | spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
- ATTEND | `@EnableTransactionManagement` finds `@Transactional` beans only in the SAME ApplicationContext — don't put it in a WebApplicationContext expecting it to cover services in another context. | [quote] | spring-framework/reference/data-access/transaction/declarative/annotations.html
- ATTEND | Prefer declarative transactions when there are many transactional ops; programmatic (`TransactionTemplate`) only for a small number (and to set the tx name). | [paraphrase] | spring-framework/reference/data-access/transaction/tx-decl-vs-prog.html

## 4. Locking (4.3)

- MUST | Specify lock mode on query methods via `@Lock` (e.g. `LockModeType.READ`, `PESSIMISTIC_WRITE`); CRUD methods can be redeclared with `@Lock`. | [quote] | jpa/locking.html
- ATTEND | Optimistic locking via `@Version` in the entity is the JPA mechanism; `@Version` also drives new-vs-not detection (see §1). Use `@Lock` for pessimistic/read locking. | [paraphrase] | jpa/locking.html · jpa/entity-persistence.html
- ATTEND | On optimistic-lock conflict the JPA provider throws an `OptimisticLockException`/`ObjectOptimisticLockingFailureException` — plan a retry/conflict response. | [paraphrase] | spring-framework/reference/data-access/orm.html

## 5. Auditing & Events (4.3)

- MUST | Enable auditing with `@EnableJpaAuditing` + an `AuditorAware<T>` bean + `AuditingEntityListener` on entities + `spring-aspects.jar` on the classpath. | [paraphrase] | auditing.html
- MUST | Use `@CreatedBy`/`@LastModifiedBy`/`@CreatedDate`/`@LastModifiedDate`; time types: JDK8 date-time, `long`/`Long`, legacy `Date`/`Calendar`. | [quote] | auditing.html
- MUST | Publish aggregate-root events via `@DomainEvents` + `@AfterDomainEventPublication`; fired on `save`/`saveAll`/`delete`/`deleteAll`/`deleteInBatch`. | [quote] | repositories/core-domain-events.html
- AVOID | Relying on `deleteById(...)` to publish aggregate-root events — it is notably absent (delete-by-query path). | [paraphrase] | repositories/core-domain-events.html

## 6. Transaction-bound Events (2.11)

- MUST | Bind a listener to the transaction commit phase with `@TransactionalEventListener` (default phase AFTER_COMMIT). | [quote] | spring-framework/reference/data-access/transaction/event.html
- ATTEND | Valid phases: BEFORE_COMMIT, AFTER_COMMIT (default), AFTER_ROLLBACK, AFTER_COMPLETION. | [quote] | spring-framework/reference/data-access/transaction/event.html
- ATTEND | With no running transaction the listener is NOT invoked — override with `fallbackExecution=true` if needed. | [paraphrase] | spring-framework/reference/data-access/transaction/event.html

## 7. JDBC (2.9)

- MUST | Use `JdbcTemplate` as the central JDBC class — handles resource creation/release and translates SQLExceptions to the `org.springframework.dao` hierarchy. | [quote] | spring-framework/reference/data-access/jdbc/core.html
- MUST | Configure the `DataSource` as a Spring bean; `JdbcTemplate` participates in ongoing transactions via `DataSourceUtils`. | [paraphrase] | spring-framework/reference/data-access/jdbc/connections.html
- AVOID | `DriverManagerDataSource`/`SimpleDriverDataSource` in production — no pooling, testing only; prefer HikariCP. | [quote] | spring-framework/reference/data-access/jdbc/connections.html
- ATTEND | Use `NamedParameterJdbcTemplate`/`JdbcClient` for named parameters; every SQL logs at DEBUG. | [paraphrase] | spring-framework/reference/data-access/jdbc/core.html

## 8. ORM / Hibernate / JPA (2.11)

- MUST | Rely on exception translation via `@Repository` + a `PersistenceExceptionTranslationPostProcessor` — no business-service coupling to the data-access strategy. | [quote] | spring-framework/reference/data-access/orm/general.html
- ATTEND | `LocalContainerEntityManagerFactoryBean` gives full control; `LocalEntityManagerFactoryBean` only for simple stand-alone/test environments. | [paraphrase] | spring-framework/reference/data-access/orm/jpa.html
- ATTEND | Lazy-loading and open-in-view are managed by Boot (default `spring.jpa.open-in-view=true` for web) — see `08-ops.md`/Boot reference for the production recommendation. | [paraphrase] | boot-data (cross-ref)

## 9. Envers (4.5)

- MUST | Make a repository a `RevisionRepository<Entity, Id, RevisionNumber>`; the entity must be `@Audited`. | [quote] | envers/configuration.html
- MUST | Enable with `@EnableEnversRepositories`; dependency `spring-data-envers` (brings `hibernate-envers`); revision-number param is `Integer` or `Long`. | [paraphrase] | envers/configuration.html
- ATTEND | Query revisions with `findRevisions(id)` → `Revisions<RevisionNumber, Entity>` where `RevisionNumber` is the type declared on the `RevisionRepository` (not a hardcoded `Long`). | [quote] | envers/usage.html

## 10. AOT (4.3)

- MUST | Annotate domain types with `@Entity`/`@Table` so they register with `ManagedTypes` for runtime hints — classpath scanning is not possible in native images. | [quote] | jpa/aot.html
- AVOID | Using AOT repository classes directly — they are an internal optimization and may change. | [paraphrase] | jpa/aot.html
- ATTEND | JPA AOT requires Hibernate; `QueryRewriter` must be a no-args class (beans not yet supported); `ScrollPosition`/keyset not yet supported. | [paraphrase] | jpa/aot.html

---

## Cross-cutting gap insertions

| Gap | Home | Rule added |
|---|---|---|
| `readOnly=true` for queries (Hibernate dirty-checking skip) | §3 | readOnly=true → flush MANUAL → skip dirty checks; a real optimization on large object trees. |
| `@Version` optimistic locking in BaseEntity | §4 | `@Version` = optimistic locking + new-vs-not detection; primitive version can't detect new. |
| `@Transactional` on Service, not Controller | §3 | Transaction boundaries at the service/facade; works through the proxy; same-context only. |
| Money via BigDecimal (precision) | → 09-api-web.md (10.5) | (owned by money/NIST family) |

## Verification note
- All pages fetched live 2026-09-12 at Spring Data JPA 4.1.1 + Framework 7.0.9; URLs exact.
- Tree coverage: 4.1 ✅ · 4.2 ✅ · 4.3 ✅ · 4.5 ✅ · 2.8 ✅ · 2.9 ✅ · 2.11 ◐ (ORM subset). 4.4 ◐ FAQ/glossary on need.