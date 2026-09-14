# 07 — Testing: TestContext Framework, Slices, Parallel (isolated official reference)

> **Tree refs:** 2.16 (Framework Testing incl. TestContext + MockMvc + Annotations), 1.8 (Boot Testing + Testcontainers + Test Utilities), 3.9 (Security testing cross-ref).
> **Sources:** `https://docs.spring.io/spring-framework/reference/testing.html` + `https://docs.spring.io/spring-boot/reference/testing/` @ Boot 4.1.1 — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

---

## 1. Testing Spring Applications (2.16)

- ATTEND | The Spring TestContext Framework supports unit + integration testing with `@ContextConfiguration`, `@ExtendWith(SpringExtension)`, and test-aware DI of fixtures. | [paraphrase] | testing/testcontext-framework.html
- MUST | Use `@ActiveProfiles` to select the active profiles for the test context. | [quote] | testing/annotations.html
- ATTEND | Context caching: an unchanged configuration reuses a cached `ApplicationContext` across tests — keep config stable for fast suites. | [paraphrase] | testing/testcontext-framework.html#context-caching

## 2. Spring Boot Testing (1.8)

- MUST | `@SpringBootTest` bootstraps the full context via `SpringApplication`; by default it does NOT start a server (`webEnvironment=MOCK`). | [quote] | testing/spring-boot-applications.html
- ATTEND | `webEnvironment` options: MOCK (default), RANDOM_PORT, DEFINED_PORT, NONE; with RANDOM_PORT/DEFINED_PORT the HTTP client and server run in separate threads/transactions — `@Transactional` does NOT roll back. | [paraphrase] | testing/spring-boot-applications.html
- MUST | Prefer test slices over full-context for unit-ish tests: `@WebMvcTest`, `@DataJpaTest`, `@RestClientTest`, `@JsonTest`, etc. — each loads only the relevant auto-configuration. | [quote] | testing/spring-boot-applications.html#testing.spring-boot-applications.autoconfigured-tests
- ATTEND | Slices are backed by the Test Auto-configuration Annotations (Test Slices appendix); use `@AutoConfigureMockMvc`/`@AutoConfigureTestDatabase` to tune a slice. | [paraphrase] | testing/spring-boot-applications.html
- MUST | Use `@Transactional` on slice/service tests for rollback-per-test (each test rolls back at the end). | [paraphrase] | testing/spring-boot-applications.html
- ATTEND | Use Testcontainers for integration tests needing a real store; `@ServiceConnection` lets Boot auto-config consume container details. | [quote] | testing/testcontainers.html

## 3. MockMvc (2.16)

- MUST | Use `MockMvc` for servlet-stack controller tests without a real server: `@WebMvcTest` + `@AutoConfigureMockMvc`; assert status/headers/JSON. | [paraphrase] | testing/spring-boot-applications.html#testing.spring-boot-applications.mocking-servlet-behavior
- ATTEND | For security-aware MVC tests, apply `springSecurity()` and use the Security test post-processors (see 03-security.md §8). | [paraphrase] | spring-security/reference/servlet/test/mockmvc/request-post-processors.html

## 4. Parallel & Context (2.16)

- ATTEND | Support parallel test execution; the TestContext Framework is thread-safe when configured — but beware shared mutable fixtures and profile/context coupling. | [paraphrase] | testing/testcontext-framework.html#parallel-test-execution
- ATTEND | Context failure threshold/pausing: a flaky context can pause to avoid repeated failures; configure deliberately. | [paraphrase] | testing/testcontext-framework.html

## 5. Test Annotations (2.16) — key subset

- MUST | Use `@TestPropertySource`/`@DynamicPropertySource` to set test properties (e.g. Testcontainers port); `@MockitoBean`/`@TestBean` to replace beans. | [paraphrase] | testing/annotations.html
- ATTEND | `@Sql`/`@SqlConfig` to run SQL scripts; `@Commit`/`@Rollback` to control transaction commits in tests. | [paraphrase] | testing/annotations.html

---

## Cross-cutting gap insertions

| Gap | Home | Rule added |
|---|---|---|
| Test slices (`@WebMvcTest`/`@DataJpaTest`) over `@SpringBootTest` | §2 | Use slices for unit-ish tests; full context only when the whole wiring is under test. |
| `@SpringBootTest` server/transaction caveat | §2 | RANDOM_PORT + `@Transactional` does not roll back (separate threads). |

## Verification note
- Pages verified live 2026-09-12 at Boot 4.1.1 + Framework 7.0.9. Tree coverage: 2.16 ◐ (key subset) · 1.8 ◐ (key subset) · 3.9 ◐ (cross-ref).