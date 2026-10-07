package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
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
import java.util.UUID;

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
    private GeographicFeatureSettingRepository geographicRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private ConsoleService service(ConsoleProperties properties) {
        return new ConsoleService(flagRepository, configRepository, geographicRepository,
                geoLookupPort, properties, meterRegistryProvider);
    }

    /** A real-shaped 4-level chain: neighborhood → city → governorate → country. */
    private static final UUID COUNTRY = UUID.randomUUID();
    private static final UUID GOVERNORATE = UUID.randomUUID();
    private static final UUID CITY = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD = UUID.randomUUID();

    private void givenTheFourLevelChain() {
        when(geoLookupPort.getLocation(NEIGHBORHOOD)).thenReturn(
                new GeoLookupPort.GeoNode(NEIGHBORHOOD, CITY, 3, "حي قدسية", "Qudsayya", "qudsayya"));
        when(geoLookupPort.getLocation(CITY)).thenReturn(
                new GeoLookupPort.GeoNode(CITY, GOVERNORATE, 2, "دمشق", "Damascus", "damascus"));
        when(geoLookupPort.getLocation(GOVERNORATE)).thenReturn(
                new GeoLookupPort.GeoNode(GOVERNORATE, COUNTRY, 1, "دمشق - ريف", "Rif Dimashq", "rif-dimashq"));
        when(geoLookupPort.getLocation(COUNTRY)).thenReturn(
                new GeoLookupPort.GeoNode(COUNTRY, null, 0, "سوريا", "Syria", "syria"));
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

    // -------------------------------------------------------------------------
    // B-18 (compliance plan C.10 — إعدادات ميزات وارثة جغرافيًا)
    // -------------------------------------------------------------------------

    /**
     * B-18's own gate (C.10: «اختبار الوراثة — الحي يغلب المدينة يغلب
     * البلد»): the most-specific scope wins. The full ladder over the
     * real-shaped 4-level chain — rows at the country and the city, a
     * missing row at the governorate — the CITY's value answers at the
     * neighborhood; then the neighborhood's OWN row takes over; and at
     * the city itself the city's row is the nearest.
     */
    @Test
    void theInheritanceTest_NeighborhoodBeatsCityBeatsCountry() {
        givenTheFourLevelChain();
        String key = "community.polls.enabled";
        // The live rows: the country says OFF, the city says ON — the
        // governorate carries no row (the honest skip in the walk).
        when(geographicRepository.findByKeyAndLocationIdIn(eq(key), anyList())).thenReturn(List.of(
                GeographicFeatureSetting.register(key, COUNTRY, false),
                GeographicFeatureSetting.register(key, CITY, true)));

        ConsoleService console = service(failClosed());

        // At the neighborhood: the chain is [neighborhood, city,
        // governorate, country] — the CITY's row is the nearest live one.
        assertThat(console.isEnabled(key, NEIGHBORHOOD)).as("the city beats the country").isTrue();
        // At the city itself: the city's row is the location's own scope.
        assertThat(console.isEnabled(key, CITY)).as("the city's own scope").isTrue();
        // At the country: only the country's row exists — OFF.
        assertThat(console.isEnabled(key, COUNTRY)).as("the country's own scope").isFalse();

        // The neighborhood's OWN row now exists — it beats the city's.
        when(geographicRepository.findByKeyAndLocationIdIn(eq(key), anyList())).thenReturn(List.of(
                GeographicFeatureSetting.register(key, COUNTRY, false),
                GeographicFeatureSetting.register(key, CITY, true),
                GeographicFeatureSetting.register(key, NEIGHBORHOOD, false)));
        assertThat(console.isEnabled(key, NEIGHBORHOOD)).as("the neighborhood beats them all").isFalse();
    }

    /**
     * B-18 (C.10): the composition ladder's lower rungs — a chain with NO
     * geographic row anywhere falls to the GLOBAL flag (its own row, then
     * the fail-closed default), and a geographic row BEATS the global row.
     */
    @Test
    void aChainWithNoRowFallsToTheGlobalFlagThenTheDefault() {
        givenTheFourLevelChain();
        String key = "community.market.enabled";
        when(geographicRepository.findByKeyAndLocationIdIn(eq(key), anyList())).thenReturn(List.of());
        when(flagRepository.findByKey(key)).thenReturn(Optional.empty());

        // No geographic row, no global row — the fail-closed default (OFF).
        assertThat(service(failClosed()).isEnabled(key, NEIGHBORHOOD)).isFalse();

        // The global flag lands — the chain inherits it.
        when(flagRepository.findByKey(key)).thenReturn(Optional.of(FeatureFlag.register(key, null, true)));
        assertThat(service(failClosed()).isEnabled(key, NEIGHBORHOOD)).isTrue();

        // A geographic row beats the global row.
        when(geographicRepository.findByKeyAndLocationIdIn(eq(key), anyList())).thenReturn(List.of(
                GeographicFeatureSetting.register(key, COUNTRY, false)));
        assertThat(service(failClosed()).isEnabled(key, NEIGHBORHOOD))
                .as("the geographic row beats the global flag").isFalse();
    }

    /**
     * B-18 (C.10): the unknown location is the port's own honest 404 —
     * never a silently-accepted value.
     */
    @Test
    void anUnknownLocationAnswersThePortsOwn404() {
        when(geoLookupPort.getLocation(UUID.fromString("00000000-0000-0000-0000-0000000000ff")))
                .thenThrow(new ResourceNotFoundException("GeoLocation", null));

        assertThatThrownBy(() -> service(failClosed())
                .isEnabled("some.key", UUID.fromString("00000000-0000-0000-0000-0000000000ff")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * B-18 (C.10 — «بوابة كتابة إدارية بعقد التحقق 400/403 نمط C.7»):
     * the registration's gate order — the location resolves through the
     * port FIRST (its 404 BEFORE any write — the L31 discipline), then
     * the duplicate live pair answers 409 (the partial unique's polite
     * face).
     */
    @Test
    void geographicRegistrationGatesTheLocationFirstThenTheDuplicate409() {
        when(geoLookupPort.getLocation(NEIGHBORHOOD)).thenThrow(new ResourceNotFoundException("GeoLocation", null));
        assertThatThrownBy(() -> service(failClosed())
                .registerGeographicSetting("some.key", NEIGHBORHOOD, true))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(geographicRepository, never()).save(any());
        verify(geographicRepository, never()).findByKeyAndLocationId(any(), any());

        // The doReturn family — Mockito's own documented remedy when
        // OVERRIDING a stub that throws: the when(...) form would invoke
        // the throwing stub during its own setup. The registration reads
        // getLocation(locationId) exactly once (no parent walk), so this
        // ONE stub is the whole gate.
        doReturn(new GeoLookupPort.GeoNode(NEIGHBORHOOD, CITY, 3, "حي قدسية", "Qudsayya", "qudsayya"))
                .when(geoLookupPort).getLocation(NEIGHBORHOOD);
        when(geographicRepository.findByKeyAndLocationId("some.key", NEIGHBORHOOD))
                .thenReturn(Optional.of(GeographicFeatureSetting.register("some.key", NEIGHBORHOOD, false)));
        assertThatThrownBy(() -> service(failClosed())
                .registerGeographicSetting("some.key", NEIGHBORHOOD, true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");
        verify(geographicRepository, never()).save(any());
    }

    /**
     * B-18 (C.10): the registration's happy path — the location gate
     * passed, no duplicate, the row saved in the operator's chosen state.
     */
    @Test
    void geographicRegistrationSavesTheRowAfterTheGates() {
        // The registration reads getLocation(locationId) exactly once —
        // one stub, not the chain helper (strict stubbing's own honesty:
        // the parent walk belongs to the RESOLUTION read alone).
        when(geoLookupPort.getLocation(CITY)).thenReturn(
                new GeoLookupPort.GeoNode(CITY, GOVERNORATE, 2, "دمشق", "Damascus", "damascus"));
        when(geographicRepository.findByKeyAndLocationId("some.key", CITY)).thenReturn(Optional.empty());
        when(geographicRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GeographicFeatureSetting saved = service(failClosed())
                .registerGeographicSetting("some.key", CITY, true);

        assertThat(saved.getKey()).isEqualTo("some.key");
        assertThat(saved.getLocationId()).isEqualTo(CITY);
        assertThat(saved.isEnabled()).isTrue();
        verify(geoLookupPort).getLocation(CITY);
    }

    /**
     * B-18 (C.10): the flip — an unknown (key, location) pair answers
     * 404; the known pair carries the change on the row.
     */
    @Test
    void theGeographicFlipAnswers404ForTheUnknownPairAndCarriesTheChange() {
        when(geographicRepository.findByKeyAndLocationId("some.key", CITY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(failClosed()).setGeographicSetting("some.key", CITY, true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Geographic feature setting not found");

        GeographicFeatureSetting setting = GeographicFeatureSetting.register("some.key", CITY, false);
        when(geographicRepository.findByKeyAndLocationId("some.key", CITY)).thenReturn(Optional.of(setting));

        GeographicFeatureSetting flipped = service(failClosed()).setGeographicSetting("some.key", CITY, true);

        assertThat(flipped.isEnabled()).isTrue();
    }

    /**
     * B-18 (C.10): the board — the operator's inventory in the stable
     * (key, location) order the repository's own derived ordering carries.
     */
    @Test
    void theGeographicBoardCarriesTheInventory() {
        when(geographicRepository.findAllByOrderByKeyAscLocationIdAsc()).thenReturn(List.of(
                GeographicFeatureSetting.register("a.key", COUNTRY, true),
                GeographicFeatureSetting.register("a.key", CITY, false)));

        List<GeographicFeatureSetting> board = service(failClosed()).geographicSettings();

        assertThat(board).hasSize(2);
        assertThat(board.get(0).getKey()).isEqualTo("a.key");
        assertThat(board.get(0).getLocationId()).isEqualTo(COUNTRY);
    }
}
