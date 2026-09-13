# 00 — System Overview (isolated system map)

> **Stack + shape + invariants.** Every fact verified by reading; citations are `file:line`.

## 1. Stack

- MUST | Stack: Java 25 + Spring Boot 4.1.1 + Spring Framework 7.0.9 + Security 7.1.1 + Modulith 2.1.1 + ArchUnit 1.5.0. | [code] | pom.xml:10,44,46,87
- MUST | Data: PostgreSQL (PostGIS) + Flyway + JPA/Hibernate + Envers + Redis (session/cache). | [code] | application.yml:20,47,76,126
- MUST | Auth: Spring Authorization Server (own) + OAuth2 resource server (JWT) + confidential + public clients. | [code] | SecurityConfig.java:107,127

## 2. Shape — 19 Maven modules

- MUST | 19 modules (`pom.xml:21-41`): marketplace-shared, marketplace-platform-infra, 16 domain modules (identity, catalog, booking, payments, pricing, reviews, messaging, search, provider, availability, notifications, ledger, disputes, media, geo, realestate), marketplace-app (composition root). | [code] | pom.xml:21-41
- MUST | Every domain module depends on marketplace-shared + marketplace-platform-infra; exactly ONE domain→domain Maven edge exists (realestate → catalog). | [code] | marketplace-realestate/pom.xml:30 · marketplace-app/pom.xml:19-96
- MUST | Composition root: `com.marketplace.MarketplaceApplication` — `@SpringBootApplication` + `@Modulithic` (default detection), no custom scan/excludes. | [code] | MarketplaceApplication.java:9-12

## 3. Key invariants (the system contract)

- MUST | Relationships are scalar UUID FK columns — zero JPA associations (`@ManyToOne/@OneToMany/@JoinColumn` absent in main code). | [code] | (grep-verified)
- MUST | All 25 `@Entity` extend `BaseEntity` + are `@Audited` (Envers `_aud` mirrors). | [code] | BaseEntity.java:38-41 · V24__envers_audit_tables.sql
- MUST | `@Transactional` lives on service classes (class-level) with `readOnly=true` on reads; REQUIRES_NEW only for bounded isolated writes; NOT_SUPPORTED for GDPR purges. | [code] | (services, see 03-data.md)
- MUST | Cross-module only via shared-api ports (injected) and application events (`@ApplicationModuleListener`, AFTER_COMMIT/async); adapters are internal. | [code] | (ports table, see 01-modules.md)
- MUST | Flyway V1..V50 with a V35 numbering gap (no file, unexplained); `validate-on-migrate: true`, `out-of-order: false`. | [code] | db/migration/ · application.yml:50-51
- MUST | Secrets via env only; prod fail-fast on missing (DB, Redis host, issuer, CORS, keystore, clients, OTLP). | [code] | application-prod.yml · SecurityConfig.java:425-447
- MUST | Verify with `./mvnw verify` (CI) or `mvn clean verify -pl <module> -am` (local); JaCoCo 70% bundle gate; Modulith verify + ArchUnit in surefire. | [code] | ci.yml:101 · pom.xml:386-395 · ModulithVerificationTest.java:19

## 4. Verification note
- Facts read live 2026-09-13 on branch `governance/d012-scope`. Details in 01–06.