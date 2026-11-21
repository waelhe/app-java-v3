package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import com.marketplace.catalog.ProductTypeAttributeWriteService;
import com.marketplace.catalog.ProductTypeRepository;
import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 2026-11-06 #808 round (Major · Data Integrity & Integration): the
 * product-type assignment write family's serialization seam — the same
 * deterministic holder pattern {@code RowLockSerializationIntegrationTest}
 * established (while a first transaction owns the ProductType row's write
 * lock, a concurrent assignment write CANNOT complete — the lock IS the
 * serialization; no probabilistic thread race).
 *
 * <p><b>The finding being closed:</b> {@code assignAttributes} took the
 * locked read ({@code findByIdForUpdate}) but its three sibling paths
 * ({@code updateAssignments}, {@code unassignAttributes},
 * {@code reorderAttributes}) rode a plain {@code findById} — two of them
 * derive positions from a prior read ({@code nextPosition}, the reorder's
 * parked range), so a path racing {@code assignAttributes} on the SAME
 * type derived the SAME position and the loser hit the live unique index
 * {@code (product_type_id, scope, position)} as an unclassified
 * constraint throw. All four paths now serialize on the ProductType row:
 * this test proves the reorder BLOCKS while a concurrent assignment
 * transaction holds the row, then completes over the COMMITTED truth —
 * the holder's fresh assignment is part of the reordered bucket (never a
 * stale-count duplicate position, never a constraint throw).</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductTypeAssignmentSerializationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches RowLockSerializationIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txm;

    @Autowired
    private ProductTypeRepository productTypes;

    @Autowired
    private ProductTypeAttributeWriteService writeService;

    /**
     * The deterministic serialization proof: while a holder transaction —
     * the assignAttributes flow's own locked read mid-flight — owns the
     * ProductType row's write lock (and commits a NEW assignment inside
     * that transaction), a concurrent reorderAttributes CANNOT complete;
     * once the holder commits, the reorder runs over the committed truth:
     * the fresh assignment is part of the reordered bucket and the whole
     * operation answers the classified SUCCESS (never the unique-index
     * constraint throw the unlocked read raced into).
     */
    @Test
    void theReorderSerializesOnTheProductTypeRowAndReadsTheCommittedAssignment() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID typeId = UUID.randomUUID();
        UUID defA = UUID.randomUUID();
        UUID defB = UUID.randomUUID();
        UUID defC = UUID.randomUUID();
        seedTypeAndDefinitions(typeId, defA, defB, defC, suffix);
        seedAssignment(typeId, defA, "VARIANT", 1);
        seedAssignment(typeId, defB, "VARIANT", 2);

        // The holder — assignAttributes' own locked read, paused between
        // the lock acquisition and its commit (the exact window the
        // unlocked reorder used to race through), committing the NEW
        // assignment inside the transaction.
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                new TransactionTemplate(txm).execute(status -> {
                    assertThat(productTypes.findByIdForUpdate(typeId)).isPresent();
                    lockHeld.countDown();
                    awaitQuietly(release);
                    // The concurrent assign's own write — a fresh VARIANT
                    // assignment at the scope's next position.
                    jdbc.update("""
                            INSERT INTO product_type_attribute_assignments
                                    (id, product_type_id, attribute_definition_id, scope, is_required, position)
                            VALUES (?, ?, ?, 'VARIANT', FALSE, 3)
                            """, UUID.randomUUID(), typeId, defC);
                    return null;
                });
            } catch (Throwable t) {
                holderFailure.set(t);
            }
        });
        holder.start();
        assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("the holder transaction owns the product type row's write lock")
                .isTrue();

        // The concurrent reorder — the service call itself (the ADMIN the
        // write surface requires), on its own thread.
        CountDownLatch reorderStarted = new CountDownLatch(1);
        CountDownLatch reorderDone = new CountDownLatch(1);
        AtomicReference<ProductTypeMutationResult> outcome =
                new AtomicReference<>();
        AtomicReference<Throwable> reorderFailure = new AtomicReference<>();
        Thread reordering = new Thread(() -> {
            reorderStarted.countDown();
            runAsAdmin(() -> {
                try {
                    outcome.set(writeService.reorderAttributes(typeId, "VARIANT",
                            List.of(new AttributeReorderMove(defB, -1))));
                } catch (Throwable t) {
                    reorderFailure.set(t);
                } finally {
                    reorderDone.countDown();
                }
            });
        });
        reordering.start();
        assertThat(reorderStarted.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(reorderDone.await(500, TimeUnit.MILLISECONDS))
                .as("while the product type row's write lock is held, the reorder cannot complete")
                .isFalse();

        // Release the holder; the reorder acquires the lock and runs over
        // the COMMITTED truth — the fresh assignment included.
        release.countDown();
        holder.join(10_000);
        assertThat(holderFailure.get()).as("the holder transaction commits cleanly").isNull();
        assertThat(reorderDone.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(reorderFailure.get()).as("the serialized reorder never hits the unique index").isNull();
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().success())
                .as("the reorder answers the classified SUCCESS over the committed truth")
                .isTrue();

        // The committed truth was read: the bucket holds ALL THREE
        // assignments, reordered as [B, A, C] (B shifted up one) with the
        // dense final positions 1..3.
        Integer aPosition = jdbc.queryForObject(
                "SELECT position FROM product_type_attribute_assignments "
                        + "WHERE product_type_id = ? AND attribute_definition_id = ?",
                Integer.class, typeId, defA);
        Integer bPosition = jdbc.queryForObject(
                "SELECT position FROM product_type_attribute_assignments "
                        + "WHERE product_type_id = ? AND attribute_definition_id = ?",
                Integer.class, typeId, defB);
        Integer cPosition = jdbc.queryForObject(
                "SELECT position FROM product_type_attribute_assignments "
                        + "WHERE product_type_id = ? AND attribute_definition_id = ?",
                Integer.class, typeId, defC);
        assertThat(bPosition).isEqualTo(1);
        assertThat(aPosition).isEqualTo(2);
        assertThat(cPosition).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void seedTypeAndDefinitions(UUID typeId, UUID defA, UUID defB, UUID defC, String suffix) {
        jdbc.update("INSERT INTO product_types (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)",
                typeId, "serialization-type-" + suffix, "Serialization type", "نوع التسلسل");
        jdbc.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'TEXT')",
                defA, "serialization-a-" + suffix, "Attribute A", "الخاصية أ");
        jdbc.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'TEXT')",
                defB, "serialization-b-" + suffix, "Attribute B", "الخاصية ب");
        jdbc.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'TEXT')",
                defC, "serialization-c-" + suffix, "Attribute C", "الخاصية ج");
    }

    private void seedAssignment(UUID typeId, UUID definitionId, String scope, int position) {
        jdbc.update("""
                INSERT INTO product_type_attribute_assignments
                        (id, product_type_id, attribute_definition_id, scope, is_required, position)
                VALUES (?, ?, ?, ?, FALSE, ?)
                """, UUID.randomUUID(), typeId, definitionId, scope, position);
    }

    /** The ADMIN authority the write surface's {@code @PreAuthorize} requires. */
    private static void runAsAdmin(Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext admin = SecurityContextHolder.createEmptyContext();
        List<? extends GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        admin.setAuthentication(new UsernamePasswordAuthenticationToken(
                "serialization-test-operator", null, authorities));
        SecurityContextHolder.setContext(admin);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
