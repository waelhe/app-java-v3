package com.marketplace.console;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-15 (compliance plan C.5 — «Remote Config بخصائص Boot»): one remote
 * configuration value — the OPERATIONAL half of the design. The static
 * half lives in {@link ConsoleProperties} (bound once at boot, Boot's
 * external-config reference); these rows are the values the operator
 * changes FROM THE CONSOLE (the identity statement's own «التغيير من
 * اللوحة لا من الكود»), read at request time — the C.10 measured limit
 * embodied: Boot's configuration governs the static at boot
 * exclusively, the operational is DATA.
 *
 * <p>One LIVE row per key (the V157 partial unique — the V70 shape),
 * the value a free VARCHAR (the consumer parses its own type — the
 * console carries the string, the policy lives with the reader), the
 * BaseEntity auditing fields the change history.</p>
 */
@Entity
@Table(name = "remote_config_values")
@Audited
public class RemoteConfigValue extends BaseEntity {

    @Id
    private UUID id;

    /** The config's key — the services' lookup name (e.g. {@code community.posts.max-length}). */
    @Column(name = "key_name", nullable = false, length = 200)
    private String key;

    /** The config's value — the raw string; the consumer parses its own type. */
    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    /** What the value calibrates — the human-facing description (the operator's own documentation). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    protected RemoteConfigValue() {
    }

    private RemoteConfigValue(UUID id, String key, String value, String description) {
        this.id = id;
        this.key = key;
        this.value = value;
        this.description = description;
    }

    /** The registration factory. */
    public static RemoteConfigValue register(String key, String value, String description) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A remote config's key cannot be blank");
        }
        return new RemoteConfigValue(UUID.randomUUID(), key, value, description);
    }

    /** The operator's value revision (the auditing fields record who and when). */
    public void revise(String value, String description) {
        this.value = value;
        this.description = description;
    }

    @Override
    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getDescription() { return description; }
}
