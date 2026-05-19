# Dependency Compatibility Matrix

Last verified: 2026-05-19 (UTC)

## Core Platform Matrix

| Component | Version | Source of truth | Enforcement in repo |
|---|---:|---|---|
| Java | 21 | Spring Boot system requirements | Root `pom.xml` via `requireJavaVersion [21,)` |
| Maven | 3.9.15 (recommended), 3.9+ (minimum) | Maven releases history + project enforcer rule | Root `pom.xml` via `requireMavenVersion [3.9,)` |
| Spring Boot | 4.0.6 | `spring-boot-starter-parent` in root `pom.xml` | Parent POM inheritance |
| Spring Modulith BOM | 2.0.6 | Explicit BOM import in root `pom.xml` | `dependencyManagement` import |
| Resilience4j BOM | 2.4.0 | Explicit BOM import in root `pom.xml` | `dependencyManagement` import |
| ArchUnit | 1.4.2 | Explicit test dependency version | `dependencyManagement` |
| JaCoCo Maven Plugin | 0.8.14 | Explicit plugin version property | Root `pom.xml` build plugin |
| springdoc-openapi | 3.0.3 | Explicit dependency version property | `dependencyManagement` |
| MapStruct | 1.6.3 | Explicit dependency version property | `dependencyManagement` |

## Version Governance Rules

1. Keep Spring-managed dependencies unpinned in child modules unless there is a documented exception.
2. Any explicit override must include one of: CVE fix, compatibility fix, or upstream bug workaround.
3. Re-check this matrix monthly and after any major framework upgrade.
4. Run reactor validation before PR merge.

## Verification Commands

```bash
mvn -q -DskipTests validate
mvn -q -DskipTests verify
```

## Official References

- Spring Boot Documentation: https://docs.spring.io/spring-boot/documentation.html
- Spring Boot System Requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Maven Releases History: https://maven.apache.org/docs/history.html
- Maven Multi-Module Guide: https://maven.apache.org/guides/mini/guide-multiple-modules.html
