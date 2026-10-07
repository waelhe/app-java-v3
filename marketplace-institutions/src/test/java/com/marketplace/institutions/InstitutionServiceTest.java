package com.marketplace.institutions;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.create;
import static org.mockito.Mockito.*;

/**
 * B-13 (compliance plan C.3): the registry engine's contracts — the geo
 * gate order (the port's 404 before the level-3 400 before any write),
 * the representative ownership, the verification machine's one-way
 * discipline, and the full register-review-publish journey as one
 * orchestration pin.
 */
@ExtendWith(MockitoExtension.class)
class InstitutionServiceTest {

    private static final UUID REPRESENTATIVE_ID = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private InstitutionRepository repository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Authentication authentication;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private InstitutionService service() {
        return new InstitutionService(repository, geoLookupPort, currentUserProvider, clock);
    }

    private void callerIs(UUID userId) {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
    }

    private void neighborhoodNode() {
        when(geoLookupPort.getLocation(NEIGHBORHOOD_ID)).thenReturn(new GeoLookupPort.GeoNode(
                NEIGHBORHOOD_ID, UUID.randomUUID(), 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of()));
    }

    private InstitutionRequest request() {
        return new InstitutionRequest("مدرسة النور الأهلية", InstitutionType.SCHOOL,
                NEIGHBORHOOD_ID, "شارع الملك فهد", "+966501234567",
                "https://alnoor-school.example", "مدرسة أهلية بالمنهج السعودي");
    }

    @Test
    void registerIsBornUnverifiedWithTheCallerAsRepresentative() {
        callerIs(REPRESENTATIVE_ID);
        neighborhoodNode();
        when(repository.save(any(Institution.class))).thenAnswer(inv -> inv.getArgument(0));

        Institution institution = service().register(request(), authentication);

        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.UNVERIFIED);
        assertThat(institution.getRepresentativeId()).isEqualTo(REPRESENTATIVE_ID);
        assertThat(institution.getName()).isEqualTo("مدرسة النور الأهلية");
        assertThat(institution.getRegisteredAt()).isEqualTo(now);
    }

    @Test
    void registerOfANonNeighborhoodNodeIs400BeforeAnyWrite() {
        callerIs(REPRESENTATIVE_ID);
        UUID cityId = create(UUID.class);
        when(geoLookupPort.getLocation(cityId)).thenReturn(new GeoLookupPort.GeoNode(
                cityId, null, 2, "الرياض", "Riyadh", "riyadh", List.of()));

        assertThatThrownBy(() -> service().register(
                new InstitutionRequest("n", InstitutionType.CLINIC, cityId, null, null, null, null),
                authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verifyNoInteractions(repository);
    }

    @Test
    void registerOfAnUnknownNodeIsThePortsOwn404() {
        callerIs(REPRESENTATIVE_ID);
        UUID unknown = create(UUID.class);
        when(geoLookupPort.getLocation(unknown))
                .thenThrow(new ResourceNotFoundException("Location not found: " + unknown));

        assertThatThrownBy(() -> service().register(
                new InstitutionRequest("n", InstitutionType.MOSQUE, unknown, null, null, null, null),
                authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Location not found");
        verifyNoInteractions(repository);
    }

    @Test
    void requestVerificationMovesOnlyTheOwnersUnverifiedEntry() {
        callerIs(REPRESENTATIVE_ID);
        Institution institution = Institution.register("n", InstitutionType.CHARITY,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        service().requestVerification(institution.getId(), authentication);

        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.PENDING);
    }

    @Test
    void requestVerificationOfAForeignEntryAnswers404() {
        callerIs(REPRESENTATIVE_ID);
        UUID id = create(UUID.class);
        when(repository.findById(id)).thenReturn(Optional.of(Institution.register("n",
                InstitutionType.NGO, UUID.randomUUID(), NEIGHBORHOOD_ID, null, null, null, null, clock)));

        assertThatThrownBy(() -> service().requestVerification(id, authentication))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Institution not found");
    }

    @Test
    void requestVerificationOfARejectedEntryCannotSelfReverse() {
        callerIs(REPRESENTATIVE_ID);
        Institution institution = Institution.register("n", InstitutionType.COMPANY,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        // Move to PENDING then REJECT through the machine itself.
        institution.requestVerification();
        institution.rejectVerification();

        service().requestVerification(institution.getId(), authentication);

        // Still REJECTED — the recovery lever is the administrator's APPROVE alone.
        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.REJECTED);
    }

    @Test
    void reviewApprovesAPendingEntryToVerified() {
        Institution institution = Institution.register("n", InstitutionType.UNIVERSITY,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        institution.requestVerification();
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        service().review(institution.getId(), true);

        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
    }

    @Test
    void reviewRejectsAPendingEntry() {
        Institution institution = Institution.register("n", InstitutionType.GOVERNMENT,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        institution.requestVerification();
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        service().review(institution.getId(), false);

        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.REJECTED);
    }

    @Test
    void reviewReAdmitsARejectedEntry() {
        Institution institution = Institution.register("n", InstitutionType.CLINIC,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        institution.requestVerification();
        institution.rejectVerification();
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        service().review(institution.getId(), true);

        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);
    }

    @Test
    void reviewOfAnIllegalSourceAnswers409WithTheMachinesOwnWords() {
        Institution institution = Institution.register("n", InstitutionType.SCHOOL,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);
        // Still UNVERIFIED — neither the approve nor the reject lever accepts it.
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));

        assertThatThrownBy(() -> service().review(institution.getId(), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only PENDING");
    }

    @Test
    void resolveChainWalksTheAdministrativeAncestryToTheRoot() {
        UUID countryId = UUID.randomUUID();
        UUID regionId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        GeoLookupPort.GeoNode neighborhood = new GeoLookupPort.GeoNode(
                NEIGHBORHOOD_ID, cityId, 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of());
        GeoLookupPort.GeoNode city = new GeoLookupPort.GeoNode(
                cityId, regionId, 2, "الرياض", "Riyadh", "riyadh", List.of());
        GeoLookupPort.GeoNode region = new GeoLookupPort.GeoNode(
                regionId, countryId, 1, "منطقة الرياض", "Riyadh Region", "riyadh-region", List.of());
        GeoLookupPort.GeoNode country = new GeoLookupPort.GeoNode(
                countryId, null, 0, "السعودية", "Saudi Arabia", "saudi-arabia", List.of());
        when(geoLookupPort.getLocation(NEIGHBORHOOD_ID)).thenReturn(neighborhood);
        when(geoLookupPort.getLocation(cityId)).thenReturn(city);
        when(geoLookupPort.getLocation(regionId)).thenReturn(region);
        when(geoLookupPort.getLocation(countryId)).thenReturn(country);

        List<GeoLookupPort.GeoNode> chain = service().resolveChain(NEIGHBORHOOD_ID);

        assertThat(chain).extracting(GeoLookupPort.GeoNode::level)
                .containsExactly(3, 2, 1, 0);
    }

    /**
     * The module's DoD as a journey (the plan's §1.2 rule): register →
     * review → publish — the representative registers (born UNVERIFIED,
     * visible on the honest registry), asks for the review (PENDING),
     * the administrator approves (VERIFIED — the trust mark), and the
     * public detail read assembles the schema.org JSON-LD block over
     * the resolved administrative chain.
     */
    @Test
    void theFullJourneyRegisterReviewPublish() {
        InstitutionService service = service();
        callerIs(REPRESENTATIVE_ID);
        neighborhoodNode();
        UUID cityId = UUID.randomUUID();
        when(geoLookupPort.getLocation(cityId)).thenReturn(new GeoLookupPort.GeoNode(
                cityId, null, 2, "الرياض", "Riyadh", "riyadh", List.of()));
        when(geoLookupPort.getLocation(NEIGHBORHOOD_ID)).thenReturn(new GeoLookupPort.GeoNode(
                NEIGHBORHOOD_ID, cityId, 3, "حي القضية", "Al-Qudayya", "al-qudayya", List.of()));
        when(repository.save(any(Institution.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. The representative registers — born UNVERIFIED.
        Institution institution = service.register(request(), authentication);
        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.UNVERIFIED);

        // 2. The board returns it (the honest registry — every state).
        when(repository.searchBoard(null, null, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(institution), PageRequest.of(0, 20), 1));
        assertThat(service.searchBoard(null, null, PageRequest.of(0, 20))).hasSize(1);

        // 3. The representative asks for the review.
        when(repository.findById(institution.getId())).thenReturn(Optional.of(institution));
        service.requestVerification(institution.getId(), authentication);
        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.PENDING);

        // 4. The administrator approves — the trust mark.
        service.review(institution.getId(), true);
        assertThat(institution.getVerificationState()).isEqualTo(InstitutionVerificationState.VERIFIED);

        // 5. The public detail read assembles the JSON-LD over the resolved chain.
        List<GeoLookupPort.GeoNode> chain = service.resolveChain(institution.getLocationId());
        InstitutionJsonLd jsonLd = InstitutionJsonLd.of(institution, chain);
        assertThat(jsonLd.type()).isEqualTo("Organization");
        assertThat(jsonLd.name()).isEqualTo("مدرسة النور الأهلية");
        assertThat(jsonLd.url()).isEqualTo("https://alnoor-school.example");
        assertThat(jsonLd.telephone()).isEqualTo("+966501234567");
        assertThat(jsonLd.address().addressLocality()).isEqualTo("الرياض");
        assertThat(jsonLd.address().streetAddress()).isEqualTo("شارع الملك فهد");
    }

    /** The absence-honesty rule: every optional JSON-LD field omitted when absent. */
    @Test
    void theJsonLdBlockOmitsEveryAbsentField() {
        Institution bare = Institution.register("جمعية البر", InstitutionType.CHARITY,
                REPRESENTATIVE_ID, NEIGHBORHOOD_ID, null, null, null, null, clock);

        InstitutionJsonLd jsonLd = InstitutionJsonLd.of(bare, List.of());

        assertThat(jsonLd.description()).isNull();
        assertThat(jsonLd.url()).isNull();
        assertThat(jsonLd.telephone()).isNull();
        assertThat(jsonLd.address()).isNotNull();
        assertThat(jsonLd.address().streetAddress()).isNull();
        assertThat(jsonLd.address().addressLocality()).isNull();
        assertThat(jsonLd.address().addressCountry()).isNull();
    }
}
