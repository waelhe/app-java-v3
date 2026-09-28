package com.marketplace.admin;

import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingsPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W0 (yelp-level plan §5 — acceptance): the control layer against the
 * <em>migrated</em> schema.
 *
 * <p><b>Why Flyway is on and Hibernate's DDL is off here</b> (the
 * {@code SavedSearchIntegrationTest} property pair): the W0 row is not «an
 * entity works» — it is «the V71 table, its CHECK constraint, its unique index,
 * its seed row and its Envers mirror meet the entity exactly». With
 * {@code ddl-auto=none} the schema is V71's own, so the seed row and
 * {@code system_settings_aud} are the real artefacts and nothing is generated.
 *
 * <p><b>The decisive assertion</b> is the stored JSON text: the column must hold
 * a JSON <em>string</em> ({@code "VERIFIED_ONLY"}), not a double-encoded one
 * ({@code "\"VERIFIED_ONLY\""}). That is the one failure mode a JSON-mapped
 * attribute can hide — the Java round-trip looks right while the DB shape (and
 * with it every SQL reader) is wrong — so it is asserted at the SQL level.
 *
 * <p>Coverage: (1) the V71 seed answers through the read port; (2) a cached
 * miss is healed by an admin PATCH — no redeploy, which also proves the
 * AFTER_COMMIT eviction (a stale cached {@code empty} would answer instead);
 * (3) the switch is in the Envers trail; (4) a non-admin writer is refused;
 * (5) an unknown key is 404; (6) the surface lists the seeded key.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SystemSettingsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SystemSettingsPort settings;

    @Autowired
    private SystemSettingRepository repository;

    @Autowired
    private RevisionService revisions;

    @Test
    void theV71SeedIsAReadableJsonStringAndTheGateReadsIt() {
        assertThat(jdbc.queryForObject(
                "select setting_value::text from system_settings where setting_key = ?",
                String.class, SystemSettingKeys.REVIEWS_MODE))
                .as("the stored value must be a JSON string, not a double-encoded one")
                .isEqualTo("\"VERIFIED_ONLY\"");

        assertThat(settings.getString(SystemSettingKeys.REVIEWS_MODE)).contains("VERIFIED_ONLY");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminPatchSwitchesTheValueAndHealsTheCachedMiss() throws Exception {
        String key = "reviews.mode.probe" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into system_settings (id, setting_key, setting_value, description) "
                + "values (?::uuid, ?, ?::jsonb, ?)", id, key, "\"VERIFIED_ONLY\"", "integration probe");

        // The read port caches the row; the switch below must reach it anyway.
        assertThat(settings.getString(key)).contains("VERIFIED_ONLY");

        mockMvc.perform(patch("/api/v1/admin/settings/{key}", key)
                        .contentType("application/json")
                        .content("""
                                {"value": "OPEN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("OPEN"));

        assertThat(settings.getString(key))
                .as("the AFTER_COMMIT eviction must expose the new value to cached readers")
                .contains("OPEN");
        assertThat(jdbc.queryForObject(
                "select setting_value::text from system_settings where setting_key = ?", String.class, key))
                .isEqualTo("\"OPEN\"");

        List<RevisionService.RevisionEntry> trail = revisions.getRevisions("SystemSetting", UUID.fromString(id));
        assertThat(trail).as("an administrative switch must be audited").isNotEmpty();

        jdbc.update("delete from system_settings_aud where id = ?::uuid", id);
        jdbc.update("delete from system_settings where id = ?::uuid", id);
    }

    @Test
    @WithMockUser(roles = "USER")
    void aNonAdminWriterIsRefused() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/settings/{key}", SystemSettingKeys.REVIEWS_MODE)
                        .contentType("application/json")
                        .content("""
                                {"value": "OPEN"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anUnknownKeyIs404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings/{key}", "reviews.mode.absent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theSurfaceListsTheSeededKey() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.key == 'reviews.mode')].value").exists());
    }
}
