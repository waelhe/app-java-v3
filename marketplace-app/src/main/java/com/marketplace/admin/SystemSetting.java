package com.marketplace.admin;

import com.marketplace.shared.api.SystemSettingTypeException;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.envers.Audited;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * W0 (yelp-level plan §4.6 + §5): one platform setting — a key, a JSON value, a
 * description, and the accountability columns {@link BaseEntity} carries
 * (who/when on both ends, an optimistic-lock {@code version}, soft delete).
 *
 * <p><b>Value as JSON (the V48 amenities / V54 saved-search precedent):</b> the
 * column is {@code jsonb} and is mapped through Hibernate's official JSON
 * mapping — {@code @JdbcTypeCode(SqlTypes.JSON)} on the attribute holding the
 * JSON <em>text</em> ({@code SavedSearch.criteria} is the same mapping with a
 * record as the Java side). The DB keeps the value in its native JSON type, so a
 * setting's shape is data and not a migration: {@code "OPEN"} is a JSON string,
 * {@code 25} a number, {@code true} a boolean, a policy an object. The V71 CHECK
 * constraint and {@link #create} keep the stored text valid JSON on every path.
 *
 * <p><b>Immutability of the key:</b> {@code updatable = false} — V71 declares the
 * unique index over the key <em>without</em> a soft-delete predicate (the V70
 * {@code uq_categories_code} reasoning: an identity is not released by deletion,
 * so a re-created row can never sit next to an old one carrying the same
 * identity). A rename is therefore delete-and-create, never an update.
 *
 * <p><b>Audited:</b> {@code system_settings_aud} (V71 mirror, V24 column
 * discipline) answers «من غيّر النمط، ومتى، وإلى ماذا» for a switch that changes
 * the shape of trust on the platform.
 *
 * <p>{@code parsed} is a read-side memo, not state: the JSON text is the single
 * source of truth (the {@code transient} keyword keeps it out of both JPA and the
 * Redis JDK-serialized value).
 */
@Entity
@Table(name = "system_settings")
@Audited
public class SystemSetting extends BaseEntity {

    /**
     * Dedicated, stateless mapper for the read-side view of the JSON text. The
     * write-side encode is Hibernate's own — this mapper is never asked to encode
     * an entity attribute, so the two cannot disagree about shape.
     */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The database twin: V71 {@code chk_system_settings_key} — same shape, char for char. */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)*");

    @Id
    private UUID id;

    @Column(name = "setting_key", nullable = false, updatable = false, length = 100)
    private String settingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false, columnDefinition = "jsonb")
    private String rawJson;

    @Column(name = "description", length = 500)
    private String description;

    private transient JsonNode parsed;

    protected SystemSetting() {
        // JPA
    }

    /**
     * The factory gate. The key must match the shape the database enforces — so a
     * malformed key fails here, on the request thread, with a readable message
     * instead of as a constraint violation from inside the flush — and the value
     * must be real JSON: {@code null} is not a setting value, it is an absent one.
     * That includes the JSON literal {@code null}: Jackson delivers it as a
     * {@code NullNode}, not as Java {@code null} (CodeRabbit W1 r1 — the official
     * {@link JsonNode#isNull()} predicate is the discriminator), because a stored
     * {@code 'null'::jsonb} is not a SQL NULL and every later typed read would
     * fail loud as a 500 instead of a readable 400 here.
     */
    static SystemSetting create(UUID id, String settingKey, JsonNode value, String description) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (settingKey == null || settingKey.length() > 100 || !KEY_PATTERN.matcher(settingKey).matches()) {
            throw new IllegalArgumentException(
                    "setting key must be a lowercase dotted identifier (e.g. reviews.mode), got: " + settingKey);
        }
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("a system setting value cannot be null: " + settingKey);
        }
        SystemSetting setting = new SystemSetting();
        setting.id = id;
        setting.settingKey = settingKey;
        setting.rawJson = JSON.writeValueAsString(value);
        setting.parsed = value;
        setting.description = description;
        return setting;
    }

    /**
     * Replaces the value. Returns {@code true} when it actually changed — the
     * writer uses that to decide whether to evict caches and publish a change
     * event, so a no-op {@code PATCH} neither clears a warm cache nor fills the
     * audit trail with a revision that changed nothing. The JSON literal
     * {@code null} is rejected for the same reason the factory rejects it (the
     * {@code NullNode} case, see {@link #create}).
     */
    boolean replaceValue(JsonNode next) {
        if (next == null || next.isNull()) {
            throw new IllegalArgumentException("a system setting value cannot be null: " + settingKey);
        }
        String encoded = JSON.writeValueAsString(next);
        if (encoded.equals(rawJson)) {
            return false;
        }
        this.rawJson = encoded;
        this.parsed = next;
        return true;
    }

    /** Updates the human-facing description. Returns {@code true} when it changed. */
    boolean replaceDescription(String description) {
        if (this.description == null ? description == null : this.description.equals(description)) {
            return false;
        }
        this.description = description;
        return true;
    }

    /** The value in its native JSON type — the shape the database holds. */
    public JsonNode value() {
        JsonNode current = parsed;
        if (current == null) {
            current = JSON.readTree(rawJson);
            parsed = current;
        }
        return current;
    }

    public String getSettingKey() {
        return settingKey;
    }

    /** The stored JSON text — what the admin surface echoes back verbatim. */
    public String getRawJson() {
        return rawJson;
    }

    public String getDescription() {
        return description;
    }

    // -- Typed read-side accessors: the port's contract, executed on the row --

    String textValue() {
        JsonNode node = value();
        if (!node.isString()) {
            throw typeMismatch("a JSON string");
        }
        return node.asString();
    }

    int intValue() {
        JsonNode node = value();
        if (!node.isIntegralNumber()) {
            throw typeMismatch("a JSON integer");
        }
        return node.asInt();
    }

    boolean booleanValue() {
        JsonNode node = value();
        if (!node.isBoolean()) {
            throw typeMismatch("a JSON boolean");
        }
        return node.asBoolean();
    }

    /**
     * Structured values (objects, arrays, enums): the value's JSON shape as a
     * Java type, decoded by the Jackson the platform already uses at every other
     * JSON boundary.
     */
    <T> T as(Class<T> type) {
        if (String.class.equals(type)) {
            return type.cast(textValue());
        }
        if (Integer.class.equals(type)) {
            return type.cast(intValue());
        }
        if (Boolean.class.equals(type)) {
            return type.cast(booleanValue());
        }
        return JSON.treeToValue(value(), type);
    }

    private SystemSettingTypeException typeMismatch(String expected) {
        return new SystemSettingTypeException(
                "System setting '" + settingKey + "' must hold " + expected + " but holds: " + rawJson);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
