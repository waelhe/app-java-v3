package com.marketplace.releases;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.PlatformReleaseChannel;
import com.marketplace.shared.api.PlatformReleasePublishedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * A-18 (compliance plan C.12 — «نظام تحديثات المنصة»): the release
 * service — the console's publication path and the public boot read.
 *
 * <p><b>Three guard layers on the write path</b> (the house rule the
 * {@code SystemSettingsService} javadoc pins): the URL space
 * ({@code /api/v1/admin/**} → {@code hasRole('ADMIN')} in
 * {@code SecurityConfig}), the class-level {@code @PreAuthorize} on
 * {@code PlatformReleaseAdminController}, and THIS service's own
 * {@code @PreAuthorize("hasRole('ADMIN')")} — a caller reaching the bean
 * off the HTTP path still meets the role check (the A-07 lesson:
 * «unannotated methods are not secured»). The service is the only place
 * allowed to touch the store.
 *
 * <p><b>The publication is event-full by contract:</b>
 * {@link PlatformReleasePublishedEvent} is published INSIDE the publish
 * transaction (the {@code SystemSettingChangedEvent} pattern) — the Modulith
 * fan-out the plan's gate names («الفان-آوت حدث Modulith ← notifications»).
 * The notifications consumer is Track B's registered late arrival
 * («الواصل المتأخر»); until it lands, the publication registry guards the
 * event exactly as it guards every house event (the A-13 machinery), so the
 * consumer's contract is standing before the consumer exists.
 *
 * <p><b>Identity is (channel, version)</b> — V117's unique index. A
 * re-publish of the same pair answers the house 409 (the V70 identity
 * stance: an identity is never recycled), and there is deliberately NO
 * update or delete path: a release is a historical fact; superseding is the
 * next publish.
 */
@Service
public class PlatformReleaseService {

    private static final Logger log = LoggerFactory.getLogger(PlatformReleaseService.class);

    private final PlatformReleaseRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PlatformReleaseService(PlatformReleaseRepository repository,
            ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The console's publication command — one transaction: the row lands,
     * the fan-out event rides the registry with it, the view answers.
     *
     * @throws ConflictException (409) when (channel, version) already
     *         exists — the identity is never recycled.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformReleaseView publish(PlatformReleaseChannel channel, String version,
            String changelog, String minVersion, boolean mandatory, int graceHours,
            String actor) {
        if (repository.findByChannelAndReleaseVersion(channel, version).isPresent()) {
            throw new ConflictException(
                    "A release already exists for channel " + channel + " at version " + version
                            + " — release identities are never recycled");
        }

        PlatformRelease release = PlatformRelease.publish(UUID.randomUUID(), channel, version,
                changelog, minVersion, mandatory, graceHours, clock.instant());
        PlatformRelease saved = repository.save(release);

        // Inside the transaction, on the SystemSettingChangedEvent pattern:
        // the decision is published here; the registry guards the delivery.
        events.publishEvent(new PlatformReleasePublishedEvent(saved.getId(), saved.getChannel(),
                saved.getReleaseVersion(), saved.getMinVersion(), saved.isMandatory(),
                saved.getGraceHours(), saved.getPublishedAt()));

        log.info("Platform release published: channel={}, version={}, mandatory={}, actor={}",
                channel, version, mandatory, actor);
        return PlatformReleaseView.from(saved);
    }

    /**
     * The console's listing — the publication history, newest first,
     * optionally filtered by channel.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<PlatformReleaseView> list(PlatformReleaseChannel channel, Pageable pageable) {
        Page<PlatformRelease> page = channel == null
                ? repository.findAllByOrderByPublishedAtDescIdDesc(pageable)
                : repository.findByChannelOrderByPublishedAtDescIdDesc(channel, pageable);
        return page.map(PlatformReleaseView::from);
    }

    /**
     * The public boot read («المسار العام للإصدار عند إقلاع العميل» — the
     * §7/2 moment): the latest published release for a channel.
     *
     * @throws ResourceNotFoundException (404) when nothing is published for
     *         the channel yet — the honest "no release published" answer,
     *         never a fabricated empty contract.
     */
    @Transactional(readOnly = true)
    public PlatformReleaseView latest(PlatformReleaseChannel channel) {
        return repository.findFirstByChannelOrderByPublishedAtDescIdDesc(channel)
                .map(PlatformReleaseView::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No release has been published for channel " + channel));
    }

    /**
     * The release facts both surfaces answer — the boot contract's own
     * shape: channel · version · changelog · the minimum-version floor ·
     * the mandatory flag · the grace window · the publication instant.
     */
    public record PlatformReleaseView(
            UUID id,
            PlatformReleaseChannel channel,
            String version,
            String changelog,
            String minVersion,
            boolean mandatory,
            int graceHours,
            java.time.Instant publishedAt) {

        static PlatformReleaseView from(PlatformRelease release) {
            return new PlatformReleaseView(release.getId(), release.getChannel(),
                    release.getReleaseVersion(), release.getChangelog(), release.getMinVersion(),
                    release.isMandatory(), release.getGraceHours(), release.getPublishedAt());
        }
    }
}
