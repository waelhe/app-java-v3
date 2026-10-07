package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * B-15 (compliance plan C.5): the console engine's contracts — the
 * request-time reads with the policy default, the registration/flip
 * surfaces, the metrics summary over a REAL registry (the honest read
 * of what EXISTS), and the change history over the rows' own auditing
 * fields.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleServiceTest {

    @Mock
    private FeatureFlagRepository flagRepository;

    @Mock
    private RemoteConfigValueRepository configRepository;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private ConsoleService service(ConsoleProperties properties) {
        return new ConsoleService(flagRepository, configRepository, properties, meterRegistryProvider);
    }

    private ConsoleProperties failClosed() {
        return new ConsoleProperties(new ConsoleProperties.Flags(false));
    }

    private ConsoleProperties failOpen() {
        return new ConsoleProperties(new ConsoleProperties.Flags(true));
    }

    @Test
    void isEnabledAnswersTheRowsStateForARegisteredKey() {
        FeatureFlag flag = FeatureFlag.register("community.polls.enabled", "وصف", true);
        when(flagRepository.findByKey("community.polls.enabled")).thenReturn(Optional.of(flag));

        assertThat(service(failClosed()).isEnabled("community.polls.enabled")).isTrue();
    }

    @Test
    void anUnregisteredKeyFailsClosedByThePolicyDefault() {
        when(flagRepository.findByKey("typoed.key")).thenReturn(Optional.empty());

        assertThat(service(failClosed()).isEnabled("typoed.key")).isFalse();
    }

    @Test
    void theMigrationWindowCanOpenThePolicy() {
        when(flagRepository.findByKey("not.yet.registered")).thenReturn(Optional.empty());

        assertThat(service(failOpen()).isEnabled("not.yet.registered")).isTrue();
    }

    @Test
    void configValueAnswersTheRowsValueWithTheCallersFallback() {
        RemoteConfigValue config = RemoteConfigValue.register("community.posts.max-length", "2000", "وصف");
        when(configRepository.findByKey("community.posts.max-length")).thenReturn(Optional.of(config));
        when(configRepository.findByKey("unknown.key")).thenReturn(Optional.empty());

        ConsoleService service = service(failClosed());
        assertThat(service.configValue("community.posts.max-length", "1000")).isEqualTo("2000");
        assertThat(service.configValue("unknown.key", "1000")).isEqualTo("1000");
    }

    @Test
    void flagRegistrationOfADuplicateLiveKeyAnswers409() {
        when(flagRepository.findByKey("dup.key")).thenReturn(Optional.of(
                FeatureFlag.register("dup.key", null, false)));

        assertThatThrownBy(() -> service(failClosed()).registerFlag("dup.key", "d", true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void theFlipOfAnUnknownKeyAnswers404() {
        when(flagRepository.findByKey("unknown.key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(failClosed()).setFlag("unknown.key", true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Feature flag not found");
    }

    @Test
    void theFlipCarriesTheChangeOnTheRow() {
        FeatureFlag flag = FeatureFlag.register("some.flag", null, false);
        when(flagRepository.findByKey("some.flag")).thenReturn(Optional.of(flag));

        FeatureFlag flipped = service(failClosed()).setFlag("some.flag", true);

        assertThat(flipped.isEnabled()).isTrue();
    }

    @Test
    void theConfigRevisionOfAnUnknownKeyAnswers404() {
        when(configRepository.findByKey("unknown.key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(failClosed()).reviseConfig("unknown.key", "v", "d"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Remote config not found");
    }

    /**
     * The metrics read (the «قراءة المقاييس» gate): a REAL
     * SimpleMeterRegistry with a REAL observation registered — the read
     * summarizes exactly what EXISTS (the observed command's meter, its
     * name/type/measurements), and degrades to an honest empty list
     * when no registry is bound.
     */
    @Test
    void metricsSummarizeWhatExistsInTheRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("job.create").increment(3.0);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(registry);

        List<ConsoleService.MeterSummary> metrics = service(failClosed()).metrics();

        assertThat(metrics).isNotEmpty();
        assertThat(metrics.stream().map(ConsoleService.MeterSummary::name))
                .contains("job.create");
        ConsoleService.MeterSummary jobCreate = metrics.stream()
                .filter(m -> m.name().equals("job.create")).findFirst().orElseThrow();
        assertThat(jobCreate.type()).isEqualTo("COUNTER");
        assertThat(jobCreate.measurements())
                .anySatisfy(m -> assertThat(m.statistic()).isEqualTo("COUNT"));
    }

    @Test
    void metricsDegradeToAnHonestEmptyListWithoutARegistry() {
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

        assertThat(service(failClosed()).metrics()).isEmpty();
    }

    /**
     * The change-history read (the «قراءة التدقيق» gate): the rows' own
     * Data JPA auditing fields — the flags and the config entries
     * interleaved, newest first.
     */
    @Test
    void changeHistoryCarriesTheAuditingFields() {
        FeatureFlag flag = FeatureFlag.register("flag.key", null, true);
        RemoteConfigValue config = RemoteConfigValue.register("config.key", "v", null);
        when(flagRepository.findAll()).thenReturn(List.of(flag));
        when(configRepository.findAll()).thenReturn(List.of(config));

        List<ConsoleService.ChangeHistoryEntry> history = service(failClosed()).changeHistory();

        assertThat(history).hasSize(2);
        assertThat(history.stream().map(ConsoleService.ChangeHistoryEntry::kind))
                .containsExactlyInAnyOrder("FEATURE_FLAG", "REMOTE_CONFIG");
        assertThat(history.stream().map(ConsoleService.ChangeHistoryEntry::key))
                .containsExactlyInAnyOrder("flag.key", "config.key");
    }
}
