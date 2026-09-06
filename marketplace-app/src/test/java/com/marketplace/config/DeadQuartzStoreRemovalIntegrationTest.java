package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end guard for the dead Quartz store removal, on the <b>real Flyway
 * schema</b> (the lesson recorded three times: a test schema built by
 * {@code ddl-auto: create-drop} hides what production actually runs — V24
 * revinfo, V28 archive table, V29 dead-job rows). Boots the full context on a
 * real PostgreSQL with {@code spring.flyway.enabled=true} +
 * {@code ddl-auto=none} (the former {@code QuartzJdbcJobStoreConfigTest}
 * pattern — that test rode on the machinery this batch deletes).
 *
 * <p>Why the whole machine was removed (evidence in V31's header): since V29
 * deleted the searchIndexRefresh job — the only job ever registered — the
 * JDBC store held zero durable jobs, while every boot still paid for the
 * scheduler, its thread pool, the cluster check-ins
 * ({@code qrtz_scheduler_state}; prod sets {@code isClustered=true}) and the
 * PostgreSQL delegate machinery. All real scheduling is framework-managed
 * (@Scheduled tasks + the Modulith staleness monitor run on Spring's task
 * scheduler and never touch the Quartz store).
 *
 * <p>Guards three invariants so the dead machine cannot silently return:
 * <ol>
 *   <li><b>Schema:</b> V31 dropped all eleven {@code qrtz_*} tables (V21) —
 *       asserted against {@code information_schema} on the migrated
 *       database, not against entity mappings.</li>
 *   <li><b>Classpath:</b> {@code org.quartz.*} is gone — a re-added
 *       {@code spring-boot-starter-quartz} fails this test before it can
 *       re-create an idle scheduler against a schema that no longer has its
 *       tables.</li>
 *   <li><b>Context:</b> no Quartz {@code SchedulerFactoryBean} ("quartzScheduler")
 *       bean is registered — catches a starter returning with Quartz
 *       auto-configuration active.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DeadQuartzStoreRemovalIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established pattern (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    void v31DroppedEveryQuartzTableFromTheFlywaySchema() {
        // ESCAPE-clamped underscore: matches exactly the qrtz_ prefix (V21's
        // unquoted identifiers fold to lowercase in PostgreSQL). On the real
        // Flyway schema this proves V31 ran — a create-drop schema could
        // never show this either way, which is the point of the pattern.
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'public' AND table_name LIKE 'qrtz\\_%'",
                Integer.class);
        assertThat(remaining)
                .as("V31 drops all eleven qrtz_* tables created by V21 (dead store: zero jobs since V29)")
                .isZero();
    }

    @Test
    void quartzIsOffTheClasspath() {
        // The dependency is removed from both poms that carried it. If it
        // ever returns, this fails before an idle scheduler can boot against
        // the dropped schema.
        boolean quartzPresent;
        try {
            Class.forName("org.quartz.Scheduler", /* initialize */ false, getClass().getClassLoader());
            quartzPresent = true;
        } catch (ClassNotFoundException expected) {
            quartzPresent = false;
        }
        assertThat(quartzPresent)
                .as("spring-boot-starter-quartz must stay off the classpath (V31 dropped its store)")
                .isFalse();
    }

    @Test
    void noQuartzSchedulerBeanIsRegistered() {
        // QuartzAutoConfiguration registers SchedulerFactoryBean under the
        // fixed name "quartzScheduler". Its absence proves no Quartz
        // auto-configuration ran for this context.
        assertThat(context.containsBean("quartzScheduler"))
                .as("no Quartz SchedulerFactoryBean in the application context (dead machine removed)")
                .isFalse();
    }
}
