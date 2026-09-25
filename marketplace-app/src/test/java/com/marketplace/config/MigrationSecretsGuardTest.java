package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration-secrets gate (N1): no credential may ever ship inside a Flyway
 * migration again. The pre-fix shape — a fixed bcrypt hash burned into
 * {@code R__seed_oauth2_client.sql}, shipped in the repository and the jar
 * for every environment — is exactly the class of regression this test
 * blocks: any password-hash literal (a {@code DelegatingPasswordEncoder}
 * id-prefix form like {@code {bcrypt}$2a$…} or a bare bcrypt salt block
 * {@code $2a$10$…}) inside {@code db/migration} fails the build with the
 * offending file.
 *
 * <p>The patterns are deliberately narrow so that legitimate schema DDL
 * (columns NAMED password/secret, insert statements with placeholders) never
 * trip: only hash-shaped literals count — a credential VALUE, never a column
 * definition.
 *
 * <p>The official channel for every credential is the environment (12-factor
 * §III — config lives in the environment, not the codebase), converged into
 * the database at boot through the framework's own APIs
 * ({@code OAuth2ClientSecretInitializer} for the client,
 * {@code AdminUserInitializer} for the break-glass admin).
 */
class MigrationSecretsGuardTest {

    /** DelegatingPasswordEncoder id-prefix forms: {bcrypt}, {sha256}, {argon2}, … */
    private static final Pattern ENCODER_ID_PREFIX_PATTERN =
            Pattern.compile("\\{(bcrypt|sha256|argon2|argon2id|pbkdf2|scrypt|noop|md4|md5|sha)\\}");

    /** Bare bcrypt salt block even without the {bcrypt} prefix: $2a$10$… / $2y$… / $2b$… */
    private static final Pattern BARE_BCRYPT_PATTERN =
            Pattern.compile("\\$2[aby]\\$\\d{2}\\$");

    @Test
    void noCredentialEverShipsInsideAMigration() throws IOException {
        Path migrationDir = Path.of("src", "main", "resources", "db", "migration");
        assertThat(Files.isDirectory(migrationDir))
                .as("db/migration must exist on the classpath root of marketplace-app")
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(migrationDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new IllegalStateException("Cannot read migration " + p, e);
                        }
                        Matcher encoderId = ENCODER_ID_PREFIX_PATTERN.matcher(content);
                        if (encoderId.find()) {
                            offenders.add(p.getFileName() + ": password-hash encoding "
                                    + encoderId.group() + " — env-only channel (AdminUserInitializer)");
                        }
                        Matcher bareBcrypt = BARE_BCRYPT_PATTERN.matcher(content);
                        if (bareBcrypt.find()) {
                            offenders.add(p.getFileName() + ": bare bcrypt salt block "
                                    + bareBcrypt.group() + " — env-only channel (AdminUserInitializer)");
                        }
                    });
        }
        assertThat(offenders)
                .as("migrations must stay credential-free — the admin/client bootstrap is env -> DB"
                        + " convergence at boot, never a hand-written secret in SQL (N1)")
                .isEmpty();
    }
}
