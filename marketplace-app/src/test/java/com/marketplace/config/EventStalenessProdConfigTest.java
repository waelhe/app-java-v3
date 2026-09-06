package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the event-publication lifecycle layer: the staleness
 * monitoring declared in {@code application-prod.yml} must use the property
 * names Spring Modulith 2.1.1 actually binds.
 *
 * <p>Root cause this guards: the production profile declared a staleness
 * monitor under the keys {@code publication-threshold} /
 * {@code processing-threshold} (commit {@code cb2d8dd}) — names that do not
 * exist in Modulith 2.1.1. Verified against the shipped
 * {@code spring-configuration-metadata.json} of
 * {@code spring-modulith-events-core-2.1.1.jar}, whose complete
 * {@code spring.modulith.events.staleness.*} surface is exactly:
 * {@code published}, {@code processing}, {@code resubmitted} (plus
 * {@code check-interval} and the deprecated aliases {@code resubmission},
 * {@code check-intervall}). Unknown keys bind to nothing, so
 * {@code StalenessProperties} stayed all-zero, {@code monitorStaleness()}
 * returned false, and the Staleness Monitor never registered its task — the
 * declared "6h / 1h" monitoring silently did not exist, and stuck
 * publications stayed zombie until a manual restart. Spring does not reject
 * unknown properties under this prefix, so only a pinned test can keep the
 * keys honest.
 *
 * <p>Runtime proof that the corrected names activate the monitor lives in
 * {@code EventPublicationResubmissionIntegrationTest}: with these durations
 * non-zero the boot log carries the official registration line
 * "Checking for stale event publications every …". Unit level (no Spring
 * context), mirroring {@code ForwardHeadersProdConfigTest}: the yml files are
 * pinned here.
 */
class EventStalenessProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void prodProfileBindsStalenessWithOfficialPropertyNames() throws Exception {
        // Official names (spring-configuration-metadata.json,
        // spring-modulith-events-core 2.1.1): published / processing / resubmitted.
        assertThat(property("application-prod.yml", "spring.modulith.events.staleness.published"))
                .isEqualTo("6h");
        assertThat(property("application-prod.yml", "spring.modulith.events.staleness.processing"))
                .isEqualTo("1h");
        // resubmitted closes the lifecycle loop: a resubmitted publication that
        // makes no progress returns to FAILED after the same window as processing.
        assertThat(property("application-prod.yml", "spring.modulith.events.staleness.resubmitted"))
                .isEqualTo("1h");
    }

    @Test
    void prodProfileCarriesNoDeadStalenessKeys() throws Exception {
        // The pre-fix keys bound to nothing in Modulith 2.1.1. They must never
        // come back: their presence would be a silent no-op — config that lies.
        assertThat(property("application-prod.yml", "spring.modulith.events.staleness.publication-threshold"))
                .as("publication-threshold is not a Modulith 2.1.1 property")
                .isNull();
        assertThat(property("application-prod.yml", "spring.modulith.events.staleness.processing-threshold"))
                .as("processing-threshold is not a Modulith 2.1.1 property")
                .isNull();
    }

    @Test
    void prodProfileKeepsBootTimeRecoveryEnabled() throws Exception {
        // Real Modulith 2.1.1 property (metadata list above): on restart,
        // outstanding (not-yet-completed) publications are resubmitted.
        // This is the boot-time half of event recovery; the runtime half is
        // EventPublicationResubmission.
        assertThat(property("application-prod.yml", "spring.modulith.events.republish-outstanding-events-on-restart"))
                .isEqualTo("true");
    }

    private String property(String yml, String key) throws java.io.IOException {
        List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
        assertThat(sources).as("%s must load", yml).isNotEmpty();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
