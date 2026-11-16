package com.marketplace.releases;

import com.marketplace.shared.api.PlatformReleaseChannel;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A-18 (compliance plan C.12): one platform release — the row the manager
 * publishes from the console and the boot-time client reads off the public
 * path. The facts are the plan's own column list: channel · version ·
 * changelog · the minimum-version floor · the mandatory flag · the grace
 * window («نافذة التوفير») · the publication instant.
 *
 * <p><b>Immutable by design:</b> a release is a historical fact. The only
 * write path is {@link #publish} (a fresh row); there is no update and no
 * delete surface — superseding a release is publishing the next one, which
 * is exactly why the V117 unique identity carries no soft-delete predicate
 * (the V70 stance: identity values are never recycled).
 *
 * <p><b>Shape guards (the entity's own defense-in-depth):</b> the V117 CHECK
 * twins live here as Java patterns — the semver shape for both version
 * fields. The boundary (bean validation + the controllers' parse
 * convention) rejects invalid shapes with the house 400 first; these guards
 * keep a non-HTTP SPI caller from landing a malformed row the DB would then
 * refuse at flush time.
 *
 * <p><b>Audited:</b> {@code platform_releases_aud} (the V117 mirror) answers
 * «من نشر ماذا ومتى» for a surface that shapes how the whole client base
 * boots.
 */
@Entity
@Table(name = "platform_releases")
@Audited
public class PlatformRelease extends BaseEntity {

    /** The database twin: V117 {@code chk_platform_releases_version_shape}. */
    static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 10)
    private PlatformReleaseChannel channel;

    // Named releaseVersion on BOTH sides: the Java side because the BaseEntity
    // optimistic-lock field owns the plain `version` name; the COLUMN because
    // PostgreSQL rejects a duplicated column name in the table (the CI-measured
    // V117 failure — the local gates never ran Flyway, docker-gated). The WIRE
    // field stays "version" (the view record's own shape).
    @Column(name = "release_version", nullable = false, updatable = false, length = 32)
    private String releaseVersion;

    @Column(name = "changelog", nullable = false, updatable = false, columnDefinition = "text")
    private String changelog;

    @Column(name = "min_version", nullable = false, updatable = false, length = 32)
    private String minVersion;

    @Column(name = "mandatory", nullable = false, updatable = false)
    private boolean mandatory;

    @Column(name = "grace_hours", nullable = false, updatable = false)
    private int graceHours;

    /**
     * The publication instant — a DOMAIN fact (when the release went out),
     * deliberately distinct from the audit {@code createdAt} (when the row
     * was written): the console's contract owns it, the latest-read orders
     * by it.
     */
    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    protected PlatformRelease() {
        // JPA
    }

    /**
     * The single write path — the publish factory. Every column is
     * updatable = false: the row is born complete or it is not born.
     */
    public static PlatformRelease publish(UUID id, PlatformReleaseChannel channel,
            String releaseVersion, String changelog, String minVersion, boolean mandatory,
            int graceHours, Instant publishedAt) {
        if (!SEMVER.matcher(releaseVersion).matches()) {
            throw new IllegalArgumentException(
                    "version must be a semantic version (major.minor.patch): " + releaseVersion);
        }
        if (!SEMVER.matcher(minVersion).matches()) {
            throw new IllegalArgumentException(
                    "minVersion must be a semantic version (major.minor.patch): " + minVersion);
        }
        if (graceHours < 0) {
            throw new IllegalArgumentException("graceHours must not be negative: " + graceHours);
        }
        PlatformRelease release = new PlatformRelease();
        release.id = id;
        release.channel = channel;
        release.releaseVersion = releaseVersion;
        release.changelog = changelog;
        release.minVersion = minVersion;
        release.mandatory = mandatory;
        release.graceHours = graceHours;
        release.publishedAt = publishedAt;
        return release;
    }

    public UUID getId() {
        return id;
    }

    public PlatformReleaseChannel getChannel() {
        return channel;
    }

    public String getReleaseVersion() {
        return releaseVersion;
    }

    public String getChangelog() {
        return changelog;
    }

    public String getMinVersion() {
        return minVersion;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public int getGraceHours() {
        return graceHours;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
