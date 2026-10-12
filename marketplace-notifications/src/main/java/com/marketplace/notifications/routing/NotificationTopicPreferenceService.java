package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
import com.marketplace.notifications.NotificationPreferenceService;
import com.marketplace.notifications.NotificationTopicPreferenceView;
import com.marketplace.notifications.NotificationTopicPreferencesUpdateRequest;
import com.marketplace.notifications.NotificationTopicPreferenceUpdate;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * hierarchical TOPIC preference domain — the second resolution level of
 * the send gate, self-service exposed. The whole service mirrors the L22
 * {@code NotificationPreferenceService} shape verbatim (the sparse
 * override model, the REQUIRES_NEW upsert with the lost-race retry, the
 * Envers trail) with the topic dimension in place of the type.
 *
 * <p><b>Channels are EMAIL and WS only:</b> the in-app (DB) channel is
 * always on (the L22 standing rule — an opt-out is rejected, 400, and
 * the V198 schema CHECK makes it absolute) and PUSH has no stored
 * preference until the provider decision (D-10) lands — a channel
 * outside the pair cannot be stored, so the API never reports a state
 * the delivery path does not honor.
 */
@Service
@Transactional
public class NotificationTopicPreferenceService {

    /** The opt-outable channels a topic switch governs — the V198 CHECK's pair. */
    private static final List<NotificationChannel> GOVERNED_CHANNELS =
            List.of(NotificationChannel.EMAIL, NotificationChannel.WS);

    private final NotificationTopicPreferenceRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate upsertTransaction;

    public NotificationTopicPreferenceService(NotificationTopicPreferenceRepository repository,
                                              CurrentUserProvider currentUserProvider,
                                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.upsertTransaction = new TransactionTemplate(transactionManager);
        this.upsertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The routing engine's SECOND resolution level: the stored topic-level
     * override as an {@code Optional} — present only when an explicit row
     * exists. The engine consults this level only after the type level
     * answered empty (the documented hierarchy: type &gt; topic &gt;
     * default), so an absent topic row never overrides a present type row.
     *
     * @param userId  the recipient (users.id space)
     * @param topic   the subject family being delivered
     * @param channel the delivery channel being consulted (EMAIL/WS — the
     *                governed pair; any other channel answers empty)
     * @return the stored override, or empty for "no explicit topic-level
     *         switch — fall through to the default"
     */
    @Transactional(readOnly = true)
    public Optional<Boolean> findChannelOverride(UUID userId, NotificationTopic topic,
                                                 NotificationChannel channel) {
        if (!GOVERNED_CHANNELS.contains(channel)) {
            return Optional.empty();
        }
        return repository.findByUserIdAndTopicAndChannel(userId, topic, channel)
                .map(NotificationTopicPreference::isEnabled);
    }

    /** The caller's own effective topic matrix (topics × EMAIL/WS). */
    @Transactional(readOnly = true)
    public List<NotificationTopicPreferenceView> getMyTopicPreferences(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return effectiveMatrix(userId);
    }

    /**
     * The PUT path — the L22 upsert shape verbatim: apply every switch
     * (create the row or flip it), reject a duplicated key in one request
     * (two values for one switch is a client bug), reject any switch
     * outside the governed pair (the DB channel is always on; PUSH is
     * unborn until D-10), and retry a lost insert race once in a fresh
     * transaction (the winner's row takes the flip path — re-applying is
     * value-idempotent).
     */
    @Observed(name = "notification.preferences.topics.update")
    public List<NotificationTopicPreferenceView> updateMyTopicPreferences(
            Authentication authentication, NotificationTopicPreferencesUpdateRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        Set<TopicKey> seen = new HashSet<>();
        for (NotificationTopicPreferenceUpdate update : request.preferences()) {
            TopicKey key = new TopicKey(update.topic(), update.channel());
            if (!seen.add(key)) {
                throw new BadRequestException(
                        "Duplicate topic preference entry for topic=" + key.topic()
                                + ", channel=" + key.channel());
            }
            if (!GOVERNED_CHANNELS.contains(update.channel())) {
                throw new BadRequestException(
                        "Topic preferences govern the EMAIL and WS channels only"
                                + " (topic=" + update.topic() + ", channel=" + update.channel()
                                + ") — the in-app channel is always on and push awaits the"
                                + " provider decision (D-10)");
            }
        }
        try {
            applySwitches(userId, request);
        } catch (DataIntegrityViolationException lostInsertRace) {
            // A concurrent PUT inserted the same (userId, topic, channel)
            // key first: the unique constraint already rolled back this
            // inner transaction. Retry once in a fresh transaction — the
            // row now exists, so the same request takes the flip path.
            applySwitches(userId, request);
        }
        return effectiveMatrix(userId);
    }

    private void applySwitches(UUID userId, NotificationTopicPreferencesUpdateRequest request) {
        upsertTransaction.executeWithoutResult(status -> {
            for (NotificationTopicPreferenceUpdate update : request.preferences()) {
                repository.findByUserIdAndTopicAndChannel(userId, update.topic(), update.channel())
                        .ifPresentOrElse(
                                existing -> existing.change(update.enabled()),
                                () -> repository.save(NotificationTopicPreference.create(
                                        userId, update.topic(), update.channel(), update.enabled())));
            }
        });
    }

    /**
     * Builds the full effective matrix for one user: every topic × the
     * governed channels with the stored override or the enabled default,
     * in stable (topic, channel) order.
     */
    private List<NotificationTopicPreferenceView> effectiveMatrix(UUID userId) {
        Map<TopicKey, Boolean> stored = repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(p -> new TopicKey(p.getTopic(), p.getChannel()),
                        NotificationTopicPreference::isEnabled));
        List<NotificationTopicPreferenceView> matrix = new ArrayList<>();
        for (NotificationTopic topic : NotificationTopic.values()) {
            for (NotificationChannel channel : GOVERNED_CHANNELS) {
                matrix.add(new NotificationTopicPreferenceView(topic, channel,
                        stored.getOrDefault(new TopicKey(topic, channel), true)));
            }
        }
        return matrix;
    }

    /** The composite key of the sparse topic override lookup. */
    private record TopicKey(NotificationTopic topic, NotificationChannel channel) {
    }
}
