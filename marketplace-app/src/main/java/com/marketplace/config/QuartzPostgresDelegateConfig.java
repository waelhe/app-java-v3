package com.marketplace.config;

import java.util.Properties;

import org.springframework.boot.quartz.autoconfigure.JobStoreType;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Configures the PostgreSQL driver delegate for Quartz's JDBC job store.
 *
 * <p>Root cause this closes (b5d9f7f5 Railway deploy, first time the JDBC store
 * ever ran): without a delegate the default {@code StdJDBCDelegate} reads the
 * {@code JOB_DATA} column via {@code ResultSet.getBlob} — pgjdbc interprets a
 * BYTEA column as a large-object OID and fetches it as a long, so the very first
 * job registration crash-loops the boot with
 * {@code "Couldn't store trigger 'DEFAULT.searchIndexRefreshTrigger' …:
 * Couldn't retrieve job: Bad value for type long : \xaced…"}. The official
 * quartz {@code tables_postgres.sql} (V21's source) documents the requirement:
 * {@code org.quartz.jobStore.driverDelegateClass =
 * org.quartz.impl.jdbcjobstore.PostgreSQLDelegate}, whose blob reads use
 * {@code getBytes}.
 *
 * <p>Why a conditional customizer instead of a
 * {@code spring.quartz.properties.org.quartz.jobStore.driverDelegateClass} entry:
 * the property would also reach {@code RAMJobStore}, which has no such setter —
 * the shared test-profile context (job-store-type: memory) fails to boot with
 * "JobStore class 'org.quartz.simpl.RAMJobStore' props could not be configured"
 * (observed in CI run 33807053454). {@code SchedulerFactoryBeanCustomizer} runs
 * after Boot applied the mapped {@code spring.quartz.properties}
 * (QuartzAutoConfiguration#quartzScheduler), so this bean re-sets the merged
 * properties with the delegate appended — but only when the JDBC store is
 * active.
 */
@Configuration
public class QuartzPostgresDelegateConfig {

    static final String DRIVER_DELEGATE_CLASS_PROPERTY = "org.quartz.jobStore.driverDelegateClass";
    static final String POSTGRESQL_DELEGATE = "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate";

    @Bean
    SchedulerFactoryBeanCustomizer postgresQuartzDriverDelegateCustomizer(QuartzProperties quartzProperties) {
        return factory -> applyDelegate(quartzProperties, factory);
    }

    private static void applyDelegate(QuartzProperties quartzProperties, SchedulerFactoryBean factory) {
        if (quartzProperties.getJobStoreType() != JobStoreType.JDBC) {
            // RAMJobStore (test profile) has no driver-delegate concept.
            return;
        }
        Properties merged = new Properties();
        merged.putAll(quartzProperties.getProperties());
        merged.setProperty(DRIVER_DELEGATE_CLASS_PROPERTY, POSTGRESQL_DELEGATE);
        factory.setQuartzProperties(merged);
    }
}
