package com.marketplace.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.Externalized;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-14 / D.3 (compliance plan wave D — «استحداث الأحداث خارجياً عند الحاجة»,
 * gate «IT وسيط»): the official Modulith event externalization path, proven
 * end-to-end against a REAL Apache Kafka broker. The three official steps
 * (events.html "Externalizing Events") are all live in this class: the
 * broker-specific artifact is on the runtime classpath
 * (spring-modulith-events-kafka, runtime scope), the probe event type carries
 * Modulith's own {@link Externalized @Externalized} with the explicit routing
 * target, and the transport is Boot-wired through {@code @ServiceConnection}
 * (the ApacheKafkaContainerConnectionDetailsFactory — the official
 * spring-boot-kafka/testcontainers bridge).
 *
 * <p><b>The journey under proof:</b> a probe event published INSIDE a
 * transaction (the canonical publication shape — the externalization support
 * is a transactional event listener) is routed to the broker after commit,
 * and the message physically arrives on the routing-target topic — read back
 * through a plain {@code KafkaConsumer} with byte-level deserialization so
 * the assertion holds regardless of the producer serializer's details (the
 * payload must merely carry the event's own serialized form, which contains
 * the probe's UUID). The registry's guard (events.html: "the Event
 * Publication Registry guards the externalization against failures …
 * publications can be resubmitted through the APIs provided") is asserted
 * from the two tables the A-13 gates already own: the probe's publication
 * completes into {@code event_publication_archive} with a completion date
 * and leaves the live {@code event_publication} table.
 *
 * <p><b>Why this shape answers the item's own condition</b> («عند الحاجة»):
 * the capability is INSTITUTED and measured — the artifact, the selection
 * mechanism, the registry guard, and the delivery path all proven on this
 * platform's real stack. Production activation remains the owner's
 * configuration decision (annotate the externally-consumed event types +
 * provision the broker via {@code spring.kafka.*}) — zero production event
 * types are annotated today, so nothing routes in any deployed profile
 * until that need materializes. The dormant posture is not an assumption:
 * the selection gate is the {@code @Externalized} annotation itself (the
 * official default selector), and this IT is the standing proof that the
 * machinery behind it works when the annotation IS present.
 */
@SpringBootTest(properties = {
        // The A-13 gate's schema posture: the REAL Flyway schema (including
        // the V28 archive table the completion mode writes into) rather than
        // the test profile's create-drop shape.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventExternalizationKafkaIntegrationTest {

    /** The routing target: the Kafka topic the probe declares explicitly. */
    private static final String TOPIC = "marketplace.externalization.it";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension.
    // KAFKA-18281 (Apache Kafka 3.9.0's own KRaft regression: the controller
    // listener bound to 0.0.0.0 crashes the broker at launch — exit code 1,
    // the measured "Exception in thread \"main\"" in every run): the
    // Testcontainers maintainer's documented override for exactly this
    // failure (testcontainers-java #9506 — eddumelendez's recipe: define
    // KAFKA_LISTENERS with the endpoints sans the 0.0.0.0 binding). The
    // env override stays on the container's own documented variable, no
    // bespoke command — the same official-docs line as every container here.
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The externalized probe — the official selection mechanism's own key:
     * Modulith's {@code @Externalized} on an event type inside the
     * auto-configuration package, with the explicit routing target as the
     * annotation's value (events.html: "Specify the broker-specific routing
     * target in the annotation's value").
     */
    @Externalized(TOPIC)
    record ExternalizedProbeEvent(UUID id) {
    }

    @AfterEach
    void cleanProbeRows() {
        jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE '%ExternalizedProbeEvent%'");
        jdbc.update("DELETE FROM event_publication_archive WHERE serialized_event LIKE '%ExternalizedProbeEvent%'");
    }

    @Test
    void anExternalizedEventReachesTheBrokerAndCompletesItsPublication() {
        UUID probeId = UUID.randomUUID();

        // The canonical publication shape: inside a transaction, so the
        // externalization's transactional listener and the registry's
        // bookkeeping ride the exact production path.
        TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);
        inTransaction.executeWithoutResult(status -> events.publishEvent(new ExternalizedProbeEvent(probeId)));

        // The broker round-trip: a plain KafkaConsumer reads the message back
        // from the routing-target topic — the physical delivery proof.
        String received = pollTopicFor(TOPIC, probeId.toString());
        assertThat(received)
                .as("the externalized event physically arrived on its routing-target topic")
                .contains(probeId.toString());

        // The registry guard: the probe's publication completed (archived
        // with a completion date) and left the live table — the same two
        // tables the A-13 restart-republish gate owns, so the externalization
        // integrates with the platform's existing publication lifecycle
        // instead of forming a second, unguarded one.
        poll("the externalized publication completed into the archive",
                () -> archivedFor(probeId) >= 1);
        poll("the completed publication left the live registry table",
                () -> liveFor(probeId) == 0);
    }

    // ---------- helpers (the EventPublicationRestartRepublishIntegrationTest patterns) ----------

    private long archivedFor(UUID eventId) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication_archive"
                        + " where serialized_event like ? and completion_date is not null",
                Long.class, "%" + eventId + "%");
        return count == null ? 0 : count;
    }

    private long liveFor(UUID eventId) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication where serialized_event like ?",
                Long.class, "%" + eventId + "%");
        return count == null ? 0 : count;
    }

    /**
     * Polls the topic (up to 30s, 200ms interval — the house pattern; no
     * Awaitility in this reactor) for a record whose bytes contain the probe
     * marker, returning the matched payload's UTF-8 text.
     */
    private String pollTopicFor(String topic, String marker) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "externalization-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                props, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    String payload = new String(record.value(), StandardCharsets.UTF_8);
                    if (payload.contains(marker)) {
                        return payload;
                    }
                }
            }
        }
        return "";
    }

    /**
     * Polls (up to 30s, 200ms interval — the house pattern) until the
     * condition holds, failing with a diagnostic message otherwise. The
     * externalization delivery is async (the AFTER_COMMIT transactional
     * listener), so state transitions must be polled.
     */
    private void poll(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
}
