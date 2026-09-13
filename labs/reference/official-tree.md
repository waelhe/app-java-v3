# Official Tree — Complete Documentation Index (the foundation)

> **The foundation of the isolated reference build.** Every family and sub-family of the official
> docs for our stack — nothing excluded. Derived exclusively from OFFICIAL documentation; no internal
> repo data, no current system. This tree is the single starting point from which the family files
> (01-core … 09-api-web) are generated.
>
> Sources verified live 2026-09-12 (HTTP 200): docs.spring.io (Boot 4.1.1 / Framework 7.0.9 /
> Security 7.1.1 / JPA 4.1.1 / Modulith 2.1.1), maven.apache.org, documentation.red-gate.com (Flyway 12),
> dev.java (Java 25), rfc-editor.org, owasp.org. Modulith 2.1.1: `application-modules.html`/`actuator.html`
> 404 → used `fundamentals.html`/`production-ready.html`; docs.oracle.com 403 → dev.java.
>
> Legend: ✅ in-stack (deep-scan) · ⭕ out-of-stack (recorded for traceability, skipped by design).
> Nothing is deleted; out-of-stack entries are kept.

---

## 1. Spring Boot 4.1.1 — full index
Source: `https://docs.spring.io/spring-boot/4.1/reference/`

| Ref | Family | Sub-families in official index | Stack | Scan |
|---|---|---|---|---|
| 1.1 | Using Spring Boot | Build Systems / Structuring Your Code / Configuration Classes / **Auto-configuration** / Spring Beans and Dependency Injection / Using the @SpringBootApplication Annotation / Running Your Application / Developer Tools / Packaging | ✅ | deep |
| 1.2 | Core Features | **SpringApplication** / Externalized Configuration / Profiles / Logging / Internationalization / Aspect-Oriented Programming / JSON / Task Execution and Scheduling / Development-time Services / Creating Your Own Auto-configuration / Kotlin Support / SSL | ✅ (Kotlin ⭕) | deep |
| 1.3 | Web | Servlet Web Applications / **Reactive Web Applications** / Graceful Shutdown / Spring Security / Spring Session / Spring for GraphQL / Spring HATEOAS | ◐ Servlet+Shutdown+Security+Session | deep on Servlet; ⭕ Reactive/GraphQL/HATEOAS |
| 1.4 | Data | SQL Databases / NoSQL Technologies (Mongo/Redis/Cassandra…) | ◐ SQL ✅ / Redis only | deep SQL; Redis only; ⭕ other NoSQL |
| 1.5 | IO | Caching / Spring Batch / gRPC / Hazelcast / Quartz / Email / Validation / REST Clients / Web Services / JTA | ◐ Caching+Validation+Quartz+Email+REST | deep Caching+Validation+Email+REST; ⭕ Batch/gRPC/Hazelcast/JTA |
| 1.6 | Messaging | JMS / AMQP / Apache Kafka / Apache Pulsar / RSocket / Spring Integration / WebSockets | ◐ Kafka (decision pending) | deep Kafka (gated); ⭕ others |
| 1.7 | Security | OAuth2 / SAML 2.0 | ◐ OAuth2 ✅ / SAML ⭕ | deep OAuth2 (cross-ref Security); ⭕ SAML |
| 1.8 | Testing | Test Modules / Test Scope Dependencies / Testing Spring Applications / Testing Spring Boot Applications / Testcontainers / Test Utilities | ✅ | deep |
| 1.9 | Packaging & Deployment | Efficient Deployments / **AOT Cache** / **AOT** / GraalVM Native / Checkpoint and Restore / Container Images / Dockerfiles / Cloud Native Buildpacks / Traditional Deployment / Docker Compose | ✅ (Docker active) | deep on AOT+Container+Docker; GraalVM/Checkpoint ⭕ |
| 1.10 | Production-ready | Enabling / **Endpoints (23)** / Monitoring HTTP / Monitoring JMX / **Observability** / Loggers / **Metrics** / **Tracing** / Auditing / Recording HTTP Exchanges / Process Monitoring / Cloud Foundry | ✅ (OTEL/Prometheus planned) | deep on Endpoints+Metrics+Tracing+Observability+Auditing; ⭕ CF |
| 1.11 | How-to Guides | Application / Properties / Embedded Web Servers / MVC / Jersey / HTTP Clients / Logging / Data Access / Database Initialization / NoSQL / Messaging / Batch / Actuator / Security / Hot Swapping / Testing / Build / AOT / GraalVM / Deploy / Docker Compose | ◐ per relevance | deep build-related; ⭕ GraalVM |
| 1.12 | Build Tool Plugins | **Maven Plugin** (full) / Gradle Plugin / AntLib / Spring Boot CLI / Supporting Other Build Systems | ◐ Maven only | deep Maven Plugin; ⭕ others |
| 1.13 | Rest APIs (Actuator endpoints) | auditevents/beans/caches/conditions/configprops/env/flyway/health/heapdump/httpexchanges/info/integrationgraph/liquibase/logfile/loggers/mappings/metrics/prometheus/quartz/sbom/scheduledtasks/sessions/shutdown/startup/threaddump | ◐ per need | deep health+metrics+conditions+prometheus |
| 1.14 | Specifications | Configuration Metadata / Providing Manual Hints / Generating Metadata / Executable Jar Format (Nested JARs/Launching/PropertiesLauncher/Restrictions) | ✅ | deep Executable Jar; ⭕ metadata hints |
| 1.15 | Appendix | Common/Deprecated Properties / Auto-configuration Classes / Test Slices / Dependency Versions | ✅ | deep Test Slices + Dependency Versions |

## 2. Spring Framework 7.0.9 — full index
Source: `https://docs.spring.io/spring-framework/reference/`

| Ref | Family | Sub-families in official index | Stack | Scan |
|---|---|---|---|---|
| 2.1 | Core: IoC Container | Container/Bean Overview / Dependencies (DI, depends-on, Lazy-init, Autowiring, Method Injection) / Bean Scopes / Customizing / Inheritance / Extension Points / **Annotation-based Config** (@Autowired/@Primary/@Qualifier/@Value/@PostConstruct/@PreDestroy) / Classpath Scanning / JSR-330 / Java-based Config (@Bean/@Configuration) / Environment / **Events** / BeanFactory | ✅ | deep |
| 2.2 | Core: Validation/Conversion | Validator / Data Binding / Error Codes / Type Conversion / Field Formatting / Global Date-Time / **Java Bean Validation** | ✅ | deep |
| 2.3 | Core: SpEL | Evaluation / Expressions in Bean Definitions / Language Reference | ◐ | deep used subset |
| 2.4 | Core: AOP | Concepts / Capabilities / **Proxies** / @AspectJ (full) / Schema-based / Choosing Style / **Proxying Mechanisms** / Programmatic / AspectJ | ✅ | deep |
| 2.5 | Core: AOP APIs | Pointcut/Advice/Advisor / ProxyFactoryBean / Programmatic ProxyFactory / Advised / auto-proxy / TargetSource | ⭕ | skip |
| 2.6 | Core: Resilience | Resilience / Null-safety / Data Buffers / AOT Optimizations | ◐ | deep AOT+Null-safety |
| 2.7 | Core: Appendix | XML Schemas / XML Authoring / Startup Steps | ⭕ | skip |
| 2.8 | Data Access: Transactions | Advantages / Abstraction / Synchronizing / **Declarative** (Implementation/Example/Rolling Back/Different Semantics/@Transactional/Propagation/Advising/AspectJ) / Programmatic / Choosing Declarative vs Programmatic / **Transaction-bound Events** / App-server integration / Solutions | ✅ | deep |
| 2.9 | Data Access: DAO/JDBC | Choosing / Hierarchy / JDBC Core / Connections / Batch / SimpleJdbc / Java Objects / Common Problems / Embedded DB / Initializing DataSource | ✅ | deep |
| 2.10 | Data Access: R2DBC | R2DBC | ⭕ | skip (servlet) |
| 2.11 | Data Access: ORM | ORM intro / General / **Hibernate** / **JPA** | ✅ | deep |
| 2.12 | Data Access: XML / Appendix | Object-XML | ⭕ | skip |
| 2.13 | Web Servlet: MVC | DispatcherServlet (full) / Filters / HTTP Conversion / Annotated Controllers (full) / Model / @InitBinder / **Validation** / **Exceptions** / **Controller Advice** / Functional Endpoints / URI Links / Async / Range / Data Binding / **CORS** / **API Versioning** / **Error Responses** / Web Security / HTTP Caching / View Tech / MVC Config / HTTP/2 / REST Clients / Testing | ✅ | deep Exceptions+Controller Advice+CORS+Error+API Versioning+Validation |
| 2.14 | Web Servlet: WebSockets/STOMP | WebSocket / SockJS / STOMP | ⭕ | skip unless decided |
| 2.15 | Web Reactive: WebFlux/WebClient | WebFlux / WebClient / HTTP Service / WebSockets / RSocket | ⭕ | skip (servlet) |
| 2.16 | Testing | Introduction / Unit / Integration / JDBC Testing / **TestContext Framework** (Key Abstractions/Bootstrapping/Listeners/Context Management/DI Fixtures/Bean Overriding/Tx/SQL/Parallel/Support/AOT) / WebTestClient / RestTestClient / **MockMvc** (full) / MockMvc vs E2E / **Annotations** (full) | ✅ | deep |
| 2.17 | Integration | REST Clients / JMS / JMX / Email / **Task Execution and Scheduling** / **Cache Abstraction** / Observability / JVM AOT Cache / JVM Checkpoint | ◐ | deep Task+Cache+Observability+AOT; ⭕ JMS/JMX |
| 2.18 | Languages | Kotlin / Groovy / Dynamic | ⭕ | skip (Java) |
| 2.19 | Appendix/Wiki | Java API / Kotlin API / Wiki (What’s New/Upgrade/Supported) | ◐ | deep Upgrade+Supported |

## 3. Spring Security 7.1.1 + Spring Authorization Server — full index
Source: `https://docs.spring.io/spring-security/reference/`

| Ref | Family | Sub-families in official index | Stack | Scan |
|---|---|---|---|---|
| 3.1 | Servlet: Getting Started / Architecture | Getting Started / Architecture (filter chain) | ✅ | deep |
| 3.2 | Authentication | Architecture / Username-Password (Form/Basic/Digest) / Password Storage (In Memory/JDBC/UserDetails/PasswordEncoder/DaoAuthenticationProvider/LDAP) / MFA / Persistence / Passkeys / OTP / Session Management / Remember Me / Anonymous / Pre-Auth / JAAS / CAS / X509 / Run-As / Logout / Auth Events / Kerberos | ◐ | deep Password Storage+Session+Auth Events+Logout; ⭕ MFA/Passkeys/JAAS/CAS/X509/Kerberos/OTP |
| 3.3 | Authorization | Architecture / **Authorize HTTP Requests** / **Method Security** / Domain Object ACLs / Authorization Events | ✅ | deep |
| 3.4 | OAuth2 | Log In / Client (Grants/Client Auth/Authorized Clients) / **Resource Server (JWT/Opaque/Multitenancy/Bearer/DPoP/Protected Metadata)** / **Authorization Server (Getting Started/Config/Core/Endpoints)** | ✅ | deep JWT+Resource Server+SAS |
| 3.5 | SAML2 | Log In / Logout / Metadata / Migrating | ⭕ | skip |
| 3.6 | Protection Against Exploits | **CSRF** / **Security HTTP Response Headers** / HTTP / **HttpFirewall** | ✅ | deep |
| 3.7 | Integrations | Concurrency / Localization / Servlet APIs / Spring Data / MVC / WebSocket / **CORS** / Taglib / **Observability** | ◐ | deep CORS+Data+MVC+Observability+Concurrency |
| 3.8 | Configuration | Java / Kotlin / Namespace | ◐ | deep Java Config |
| 3.9 | Testing | **Method Security / MockMvc Support / Setup / RequestPostProcessors (Mock Users/CSRF/Form/Basic/OAuth2/Logout) / RequestBuilders / ResultMatchers / ResultHandlers** | ✅ | deep |
| 3.10 | Appendix | Database Schemas / XML Namespace / Proxy / FAQ | ◐ | deep DB schemas |
| 3.11 | Reactive | WebFlux Security / GraalVM | ⭕ | skip |

## 4. Spring Data JPA 4.1.1 — full index
Source: `https://docs.spring.io/spring-data/jpa/reference/`

| Ref | Family | Sub-families in official index | Stack | Scan |
|---|---|---|---|---|
| 4.1 | Core | Getting Started / Core concepts / Repository Interfaces / Configuration / Persisting Entities | ✅ | deep |
| 4.2 | Query Methods | Defining / JPA Query / Value Expressions / **Projections** / Stored Procedures / **Specifications** / Query by Example / Vector Search | ✅ | deep Query+Projections+Specifications; ⭕ Vector |
| 4.3 | Advanced | **Transactionality** / **Locking** / **Auditing** / Merging / CDI / Custom Repos / **Publishing Events from Aggregate Roots** / Null Handling / Extensions / Keywords / Return Types / **AOT** | ✅ | deep Transactionality+Locking+Auditing+Events+AOT |
| 4.4 | Reference | FAQ / Glossary | ◐ | on need |
| 4.5 | **Envers** | Introduction / Configuration / Usage | ✅ | deep (audit) |
| 4.6 | Javadoc / Wiki | Javadoc / Wiki (Upgrade/Supported) | ◐ | deep Upgrade |

## 5. Spring Modulith 2.1.1 — full index
Source: `https://docs.spring.io/spring-modulith/reference/`

| Ref | Family | Sub-families in official index | Stack | Scan |
|---|---|---|---|---|
| 5.1 | Fundamentals | Modular structure / module detection / dependencies | ✅ | deep |
| 5.2 | Verifying Module Structure | spring-modulith-test verification / CI | ✅ | deep |
| 5.3 | **Application Events** | @ApplicationModuleListener / transactional semantics / event publication registry / retry | ✅ | deep |
| 5.4 | Integration Testing Modules | module integration tests | ✅ | deep |
| 5.5 | **Moments** | time-based events API | ◐ | deep if scheduling |
| 5.6 | Documenting Modules | module docs generation | ◐ | on need |
| 5.7 | Runtime Support | runtime / event delivery / completion modes | ✅ | deep |
| 5.8 | Production-ready | /actuator/applicationmodules | ✅ | deep |
| 5.9 | Appendix | artifact list | ◐ | on need |

## 6. Maven 3.9.x — full index
Source: `https://maven.apache.org/guides/` + `https://maven.apache.org/pom.html`

| Ref | Family | Sub-families | Stack | Scan |
|---|---|---|---|---|
| 6.1 | Lifecycle | default/clean/site / phases / call only final phase | ✅ | deep |
| 6.2 | Dependency Management | BOM / dependencyManagement / never pin BOM versions / exceptions | ✅ | deep |
| 6.3 | Reactor | -pl/-am / ordering / dependency resolution | ✅ | deep |
| 6.4 | Reproducible Builds | fixed plugin versions | ✅ | deep |
| 6.5 | Plugin Management | duplicate declarations | ✅ | deep |
| 6.6 | POM Reference | structure | ◐ | on need |

## 7. Flyway 12.x — full index
Source: `https://documentation.red-gate.com/flyway/`

| Ref | Family | Sub-families | Stack | Scan |
|---|---|---|---|---|
| 7.1 | Migration Naming | V{n}__/R{n}__ / order | ✅ | deep |
| 7.2 | Migration Execution | lifecycle / validate / checksums | ✅ | deep |
| 7.3 | Immutability | never edit applied / forward-only | ✅ | deep |
| 7.4 | Migration Types | versioned/repeatable | ✅ | deep |
| 7.5 | Configuration & Integration | Boot auto-config / callbacks | ✅ | deep |

## 8. Java 25
Source: `https://dev.java/` (docs.oracle.com 403 → dev.java)

| Ref | Family | Sub-families | Stack | Scan |
|---|---|---|---|---|
| 8.1 | Language | records / sealed / pattern matching / main | ✅ | deep |
| 8.2 | Platform | LTS / modules / toolchain | ✅ | deep version fit |

## 9. RFCs (web/API contracts)
Source: `https://www.rfc-editor.org/rfc/`

| Ref | Family | Sub-families | Stack | Scan |
|---|---|---|---|---|
| 9.1 | RFC 9457 | Problem Details | ✅ | deep |
| 9.2 | RFC 7519 | JWT | ✅ | deep |
| 9.3 | RFC 9470 | Token Revocation | ◐ | deep where SAS |
| 9.4 | OAuth2 | grants / client credentials | ✅ | deep |

## 10. OWASP Top 10 + cheat sheets + NIST
Source: `https://owasp.org/` + cheat sheets + NIST

| Ref | Family | Sub-families | Stack | Scan |
|---|---|---|---|---|
| 10.1 | A01 | Broken Access Control / IDOR | ✅ | deep |
| 10.2 | A03 | Injection / boundary validation | ✅ | deep |
| 10.3 | A07 | Auth failures / session | ✅ | deep |
| 10.4 | Cheat sheets | Logging / CORS / Secrets / JWT / Validation | ✅ | deep |
| 10.5 | NIST | passwords / crypto / money precision | ✅ | deep money |

---

## Cross-cutting lessons (standards-miner targets)

Linear chapter-scans miss standards; each confirmed gap has a known home in the tree:

| Missed principle | Home (tree ref) | Status |
|---|---|---|
| `@Transactional` on Service, not Controller | 2.8 + 1.2 | to add |
| No `catch(Exception)` in event listeners (EPR retry) | 5.3 | to add |
| Optimistic locking via `@Version` in BaseEntity | 4.3 | to add |
| Event naming past-tense (`BookingCreated`) | 5.8 + DDD | to add |
| `@Lazy` self-injection | 2.1 | to add (refine) |
| readOnly=true detailed (Hibernate dirty-checking) | 4.3 + 2.8 | to add |
| Convention over configuration | 1.1 | present |
| Customize via properties before replacing auto-config | 1.1 | to refine |
| Test slices (@WebMvcTest/@DataJpaTest) over full context | 1.8 + 2.16 | to add |
| RFC 9457 (7807 replaced) | 9.1 | present |
| Defense in depth (method+URL+object) | 3.3 | present |
| Graceful shutdown + timeout | 1.3 | present (default in 4.1) |
| Money via BigDecimal (precision) | 10.5 + 2.8 | to add |
| Flyway immutability | 7.3 | present |
| Cross-boundary dependency avoidance | 5.1/5.2 | present (refine) |

---

## Verification notes

- All top-level indexes verified live 2026-09-12 (HTTP 200) from sources above.
- Modulith renames: `application-modules.html`/`actuator.html` 404 → `fundamentals.html`/`production-ready.html`.
- docs.oracle.com 403 → dev.java.
- This tree is the exhaustive inventory; nothing excluded — out-of-stack recorded with ⭕ for traceability.