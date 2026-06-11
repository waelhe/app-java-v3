# Project-Specific Rules — Backend Java (V3)

Follows the global AGENTS.md at `~/.config/opencode/AGENTS.md`.
This file adds project-specific conventions.

## Additional Rules

- **CI**: Run `mvn clean verify -pl <module>` before pushing
- **Testing**: All integration tests MUST have `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` or `@Container`
- **Flyway**: Any schema change = new V{number} migration file. Never modify existing migrations
- **Envers**: All domain entities MUST have `@Audited`
- **Protocols enforced**: Planning → Execution → Surgical Editing (see global AGENTS.md)
