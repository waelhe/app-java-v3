package com.marketplace.releases;

import com.marketplace.shared.api.PlatformReleaseChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * A-18: the {@code platform_releases} store.
 *
 * <p>Lookups: the public boot path's single hot query is {@link #findLatest}
 * — the newest published row for a channel, served by the V117
 * {@code idx_platform_releases_channel_latest} index. The soft-delete
 * filter is applied by Hibernate's {@code @SoftDelete} on
 * {@link BaseEntity} exactly as every other repository here (the
 * {@code SystemSettingRepository} pattern).
 *
 * <p>{@link #findAllOrdered} exists because the console's paged listing
 * must be stable across pages ({@code Pageable.unpaged()} has no natural
 * order to fall back on) — the same reason the settings repository carries
 * its own ordered read.
 */
public interface PlatformReleaseRepository extends JpaRepository<PlatformRelease, UUID> {

    /**
     * The release identity lookup — V117's unique (channel, version) index,
     * the publish path's own conflict check.
     *
     * <p><b>The derivation must name the BUSINESS field (releaseVersion):>
     * the plain "Version" suffix derives against BaseEntity's
     * {@code @Version} optimistic-lock Long — the measured CI root of
     * 2026-10-09 (five red cycles): binding the business version STRING to
     * the integer-typed path throws Hibernate's parameter-binding
     * IllegalArgumentException, which the error.VAL-001.detail i18n key
     * masks into the generic "Validation failed" 400 (no fieldErrors —
     * the MethodArgumentNotValid path always carries them, so the body
     * itself named the IllegalArgumentException advice as the producer).
     * The identity check never ran; every valid publication 400'd.
     */
    Optional<PlatformRelease> findByChannelAndReleaseVersion(
            PlatformReleaseChannel channel, String releaseVersion);

    /**
     * The latest published release for a channel — the boot-time read. The
     * id tie-break keeps the order deterministic even if two publications
     * share a timestamp instant.
     */
    Optional<PlatformRelease> findFirstByChannelOrderByPublishedAtDescIdDesc(
            PlatformReleaseChannel channel);

    /** The console's stable paged listing, newest first. */
    Page<PlatformRelease> findAllByOrderByPublishedAtDescIdDesc(Pageable pageable);

    /** The console's channel-filtered paged listing, newest first. */
    Page<PlatformRelease> findByChannelOrderByPublishedAtDescIdDesc(
            PlatformReleaseChannel channel, Pageable pageable);
}
