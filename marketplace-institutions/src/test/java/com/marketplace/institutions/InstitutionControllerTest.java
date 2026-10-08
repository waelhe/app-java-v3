package com.marketplace.institutions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * B-13 (compliance plan C.3): the REST surface's statuses — the direct
 * controller-invocation house pattern: 201 on the registration, 200 on
 * the reads and the verification moves, the paged envelopes riding
 * {@code PagedResponse}, the JSON-LD block riding the detail alone.
 */
@ExtendWith(MockitoExtension.class)
class InstitutionControllerTest {

    @Mock
    private InstitutionService service;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private InstitutionController controller;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), java.time.ZoneOffset.UTC);

    private Institution institution() {
        return Institution.register("مدرسة النور الأهلية", InstitutionType.SCHOOL,
                UUID.randomUUID(), UUID.randomUUID(), "شارع الملك فهد",
                "+966501234567", "https://alnoor-school.example", "مدرسة أهلية", clock);
    }

    @Test
    void registerAnswers201() {
        var request = new InstitutionRequest("مدرسة النور", InstitutionType.SCHOOL,
                UUID.randomUUID(), null, null, null, null);
        when(service.register(request, authentication)).thenReturn(institution());

        ResponseEntity<InstitutionResponse> result = controller.register(request, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().name()).isEqualTo("مدرسة النور الأهلية");
        assertThat(result.getBody().verificationState()).isEqualTo("UNVERIFIED");
    }

    @Test
    void boardAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.searchBoard(InstitutionType.SCHOOL, null, pageable))
                .thenReturn(new PageImpl<>(List.of(institution()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<InstitutionResponse>> result =
                controller.board(InstitutionType.SCHOOL, null, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        // The list rows carry NO JSON-LD (the structured-data surface rides the detail alone).
        assertThat(result.getBody().content().get(0).jsonLd()).isNull();
    }

    @Test
    void detailAnswers200WithTheJsonLdBlock() {
        Institution institution = institution();
        when(service.getInstitution(institution.getId())).thenReturn(institution);
        when(service.resolveChain(institution.getLocationId())).thenReturn(List.of(
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), null, 2, "الرياض", "Riyadh", "riyadh", List.of())));

        ResponseEntity<InstitutionResponse> result = controller.detail(institution.getId());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().jsonLd()).isNotNull();
        assertThat(result.getBody().jsonLd().type()).isEqualTo("Organization");
        assertThat(result.getBody().jsonLd().address().addressLocality()).isEqualTo("الرياض");
    }

    @Test
    void myInstitutionsAnswers200WithThePagedEnvelope() {
        var pageable = PageRequest.of(0, 20);
        when(service.myInstitutions(authentication, pageable))
                .thenReturn(new PageImpl<>(List.of(institution()), pageable, 1));

        ResponseEntity<com.marketplace.shared.api.PagedResponse<InstitutionResponse>> result =
                controller.myInstitutions(pageable, authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).hasSize(1);
    }

    @Test
    void requestVerificationAnswers200() {
        Institution institution = institution();
        when(service.requestVerification(institution.getId(), authentication)).thenReturn(institution);

        ResponseEntity<InstitutionResponse> result =
                controller.requestVerification(institution.getId(), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
