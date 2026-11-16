# Testing Conventions

> **Canonical reference:** For the full testing standards (naming, structure, patterns,
> mocking libraries, coverage thresholds), see
> [CODING_STANDARDS.md §13 — Testing](../CODING_STANDARDS.md#13-testing).
>
> This document provides the quick-reference summary. The full rules live in CODING_STANDARDS.md
> to keep a single source of truth.

## Test naming

- **Unit tests** must use the `*Test` suffix.
  - Executed by `maven-surefire-plugin` during the `test` phase.
- **Integration tests** must use one of these suffixes:
  - `*IT`
  - `*IntegrationTest`
  - Executed by `maven-failsafe-plugin` during the `integration-test` and `verify` phases.

## Test method naming convention

Pattern: `methodName_condition_expected` or `methodName_whenCondition_expected`

Examples:
- `create_setsStatusToPending`
- `cancel_whenNotOwner_throwsAccessDenied`
- `createBooking_withPastDate_throwsBadRequest`

## CI execution

CI runs `mvn verify` (via Maven Wrapper) so both unit and integration tests are executed
in the standard Maven lifecycle.

## Coverage threshold

- **JaCoCo minimum:** 70% (`jacoco.coverage.threshold=0.70` in `pom.xml`)
- Enforced on all domain modules via `jacoco:check` goal in `verify` phase
- Critical modules (identity, payments, booking) may enforce higher local thresholds

## Test libraries (canonical)

| Library | Purpose |
|---------|---------|
| JUnit 5 | Test framework |
| Mockito | Mocking dependencies |
| AssertJ | Fluent assertions (`assertThat(...)`) |
| Instancio | Test data generation (`Instancio.create(Type.class)`) |
| Testcontainers | Integration tests with real DB/Redis (Docker required) |
| Spring Modulith Test | `@ApplicationModuleTest` for module-scoped context |

## Test structure pattern

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceTest {

    private final XxxRepository repository = mock(XxxRepository.class);

    private XxxService service;

    @BeforeEach
    void setUp() {
        service = new XxxService(repository);
    }

    @Test
    void methodName_condition_expected() {
        // given
        UUID id = Instancio.create(UUID.class);
        // when
        Xxx result = service.getById(id);
        // then
        assertThat(result).isNotNull();
    }
}
```

## Forbidden patterns

- ❌ `ReflectionTestUtils.setField` for non-final fields (use constructor injection)
- ❌ `setAccessible(true)` on private methods (make method package-private instead)
- ❌ `@SuppressWarnings("unchecked")` to silence warnings (fix the root cause)
- ❌ `catch (Exception)` in `@ApplicationModuleListener` (defeats retry — see CODING_STANDARDS §4)

## References

- [Maven Surefire Plugin](https://maven.apache.org/surefire/)
- [Maven Failsafe Plugin](https://maven.apache.org/failsafe/)
- [JaCoCo Maven Plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
- [JUnit 5](https://junit.org/junit5/)
- [Mockito](https://site.mockito.org/)
- [AssertJ](https://assertj.github.io/doc/)
- [Instancio](https://www.instancio.org/)
- [Testcontainers](https://www.testcontainers.org/)
- [Spring Modulith — Testing](https://docs.spring.io/spring-modulith/reference/testing.html)
- [CODING_STANDARDS.md §13 — Testing](../CODING_STANDARDS.md#13-testing)
