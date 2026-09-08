package com.marketplace.notifications;

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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification preference
 * domain — the send gate every delivery consults and the self-service
 * matrix the endpoints expose. The whole feature lives inside this module
 * (the roadmap's integration point: "internal preference read").
 *
 * <p><b>The sparse-override model:</b> only explicit overrides are stored.
 * {@link #isChannelEnabled} returns {@code true} for a user×type×channel
 * with no row — the default IS the pre-L22 behavior, so the system's
 * default state is byte-for-byte the old one (acceptance criterion 2).
 *
 * <p><b>Channel semantics (the L22 scope):</b> the in-app (DB) channel is
 * always on — the roadmap: "inside the app always" — and an explicit DB
 * opt-out is <em>rejected</em> (a 400, never a silently-ignored stored
 * value): the API must not report a state the delivery path does not
 * honor. EMAIL is gated before every send; WS is sent by default and
 * honors an explicit opt-out. A DB opt-in ({@code enabled = true}) is
 * accepted — it merely affirms the default.
 *
 * <p><b>Upsert atomicity (CodeRabbit round 1):</b> the class-level
 * {@code @Transactional} does not serialize concurrent PUTs — two requests
 * for the same {@code (userId, type, channel)} can both read "no row" and
 * both insert; the unique constraint then aborts one VALID request. The
 * write therefore runs inside a {@link TransactionTemplate} with
 * {@code REQUIRES_NEW}: a lost race surfaces as
 * {@link DataIntegrityViolationException} from that inner, already-rolled
 * back transaction, and the whole request is retried once in a fresh
 * transaction — where the winner's row now exists, so the same request
 * takes the flip path (re-applying the batch is value-idempotent: a
 * no-change flip writes nothing). Every state change — insert or flip —
 * still goes through the ORM, so Envers keeps auditing both.
 */
@Service
@Transactional
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate upsertTransaction;

    public NotificationPreferenceService(NotificationPreferenceRepository repository,
                                         CurrentUserProvider currentUserProvider,
                                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.upsertTransaction = new TransactionTemplate(transactionManager);
        this.upsertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The L22 send gate: is this channel enabled for this user and
     * notification type? Absence of an override row means enabled (the
     * default) — so with no preferences written, every send proceeds
     * exactly as it did pre-L22.
     *
     * @param userId   the recipient (users.id space)
     * @param type     the notification type being delivered
     * @param channel  the delivery channel being consulted
     * @return false only when an explicit override disables the channel
     */
    @Transactional(readOnly = true)
    public boolean isChannelEnabled(UUID userId, NotificationType type, NotificationChannel channel) {
        return repository.findByUserIdAndTypeAndChannel(userId, type, channel)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }

    /**
     * The effective matrix the GET endpoint returns: every notification
     * type × every channel, each with its stored override or the enabled
     * default — six rows today (2 types × 3 channels). The in-app (DB)
     * channel always reports enabled: an opt-out is rejected at the write
     * path, so no stored row can say otherwise.
     *
     * @param authentication the caller (self-scoped read)
     * @return the full effective matrix in stable (type, channel) order
     */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceView> getMyPreferences(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return effectiveMatrix(userId);
    }

    /**
     * The PUT path: applies every switch in the request (upsert — create
     * the override row if absent, flip the existing one otherwise) and
     * returns the resulting effective matrix. Two request-level contracts
     * are enforced before any write: a request naming the same
     * type×channel twice is rejected (two values for one switch is a
     * client bug — silent last-write-wins would hide it), and an in-app
     * (DB) opt-out is rejected (the delivery path always creates the
     * in-app notification — the API never reports a state it does not
     * honor). The DB rule is also enforced at the binding layer
     * ({@code NotificationPreferenceUpdate}'s {@code @AssertTrue}) — this
     * service-level check is the defense-in-depth copy for non-HTTP
     * callers, the house pattern of guarding the service itself.
     *
     * @param authentication the caller (self-scoped write)
     * @param request        the switches to apply
     * @return the full effective matrix after the application
     */
    @Observed(name = "notification.preferences.update")
    public List<NotificationPreferenceView> updateMyPreferences(Authentication authentication,
                                                                NotificationPreferencesUpdateRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        Set<PreferenceKey> seen = new HashSet<>();
        for (NotificationPreferenceUpdate update : request.preferences()) {
            PreferenceKey key = new PreferenceKey(update.type(), update.channel());
            if (!seen.add(key)) {
                throw new BadRequestException(
                        "Duplicate preference entry for type=" + key.type() + ", channel=" + key.channel());
            }
            if (update.channel() == NotificationChannel.DB && !update.enabled()) {
                throw new BadRequestException(
                        "The in-app (DB) channel is always on — an opt-out is not supported"
                                + " (type=" + update.type() + ")");
            }
        }
        try {
            applySwitches(userId, request);
        } catch (DataIntegrityViolationException lostInsertRace) {
            // A concurrent PUT inserted the same (userId, type, channel)
            // key first: the unique constraint already rolled back this
            // inner transaction. Retry once in a fresh transaction — the
            // row now exists, so the same request takes the flip path.
            applySwitches(userId, request);
        }
        return effectiveMatrix(userId);
    }

    /**
     * Applies the request's switches inside the dedicated
     * {@code REQUIRES_NEW} transaction (see the class javadoc).
     */
    private void applySwitches(UUID userId, NotificationPreferencesUpdateRequest request) {
        upsertTransaction.executeWithoutResult(status -> {
            for (NotificationPreferenceUpdate update : request.preferences()) {
                repository.findByUserIdAndTypeAndChannel(userId, update.type(), update.channel())
                        .ifPresentOrElse(
                                existing -> existing.change(update.enabled()),
                                () -> repository.save(NotificationPreference.create(
                                        userId, update.type(), update.channel(), update.enabled())));
            }
        });
    }

    /**
     * Builds the full effective matrix for one user: every notification
     * type × every channel with the stored override or the enabled
     * default, in stable (type, channel) order — six rows today.
     */
    private List<NotificationPreferenceView> effectiveMatrix(UUID userId) {
        Map<PreferenceKey, Boolean> stored = repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(p -> new PreferenceKey(p.getType(), p.getChannel()),
                        NotificationPreference::isEnabled));
        List<NotificationPreferenceView> matrix = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                matrix.add(new NotificationPreferenceView(type, channel,
                        stored.getOrDefault(new PreferenceKey(type, channel), true)));
            }
        }
        return matrix;
    }

    /** The composite key of the sparse override lookup. */
    private record PreferenceKey(NotificationType type, NotificationChannel channel) {
    }
}
