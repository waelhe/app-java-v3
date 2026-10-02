package com.marketplace.provider;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 (yelp-level plan §5 — the business page): the write surface's unit
 * guards — the ownership law (the module's own AccessDenied shape), the
 * PUT-replacement week contract (upsert by day, withdraw the undeclared),
 * the duplicate-weekday rejection, the max+1 position allocation (the W1
 * lesson), the swap-form reorder, the duplicate-area loud failure, and
 * the not-found shapes for foreign rows.
 */
class ProviderBusinessPageServiceTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private ProviderService providerService;
    private CurrentUserProvider currentUserProvider;
    private BusinessHourRepository businessHourRepository;
    private OfferedServiceRepository offeredServiceRepository;
    private ServiceAreaRepository serviceAreaRepository;
    private ProviderBusinessPageService service;
    private final Authentication owner = new TestingAuthenticationToken(
            USER_ID.toString(), "n/a", "ROLE_PROVIDER");

    private ProviderProfile profile;

    @BeforeEach
    void setUp() {
        providerService = mock(ProviderService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        businessHourRepository = mock(BusinessHourRepository.class);
        offeredServiceRepository = mock(OfferedServiceRepository.class);
        serviceAreaRepository = mock(ServiceAreaRepository.class);
        service = new ProviderBusinessPageService(providerService, currentUserProvider,
                businessHourRepository, offeredServiceRepository, serviceAreaRepository);

        profile = ProviderProfile.create("اسم", null, USER_ID);
        // The fixture's id must BE the path parameter's id (the production
        // code resolves the profile then uses ITS OWN id for the repository
        // lookups — the factory generates a random one; the stamp mirrors
        // what a persisted row carries).
        try {
            java.lang.reflect.Field idField = ProviderProfile.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(profile, PROVIDER_ID);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("fixture stamp failed", impossible);
        }
        when(providerService.getById(PROVIDER_ID)).thenReturn(profile);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
    }

    // -- the ownership law ------------------------------------------------------

    @Test
    void nonOwner_isDeniedBeforeAnyWrite() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());

        // The denial guards the WRITES (the reads are the public page's own
        // composition — by design, the ProviderPublicPageService pattern).
        assertThatThrownBy(() -> service.replaceHours(PROVIDER_ID, List.of(), owner))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You do not own this provider");
        verify(businessHourRepository, never()).delete(any(BusinessHour.class));
    }

    @Test
    void admin_bypassesTheOwnershipCheck() {
        when(currentUserProvider.isAdmin(any())).thenReturn(true);
        when(businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(PROVIDER_ID))
                .thenReturn(List.of());

        // The admin path writes through the same surface without owning it.
        assertThat(service.replaceHours(PROVIDER_ID, List.of(), owner)).isEmpty();
    }

    @Test
    void profileWithoutLinkedUser_cannotBeOwnedByAnyone() {
        profile = ProviderProfile.create("اسم", null, null);
        when(providerService.getById(PROVIDER_ID)).thenReturn(profile);

        assertThatThrownBy(() -> service.replaceHours(PROVIDER_ID, List.of(), owner))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -- the declared week (G11) -------------------------------------------------

    @Test
    void replaceHours_upsertsTheDeclaredDay_andWithdrawsTheRest() {
        BusinessHour existing = BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("17:00"));
        when(businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(PROVIDER_ID))
                .thenReturn(List.of(existing));
        when(businessHourRepository.findByProviderIdAndDayOfWeek(PROVIDER_ID,
                DayOfWeek.FRIDAY.getValue())).thenReturn(Optional.empty());

        service.replaceHours(PROVIDER_ID, List.of(new ProviderBusinessPageService.HoursEntry(
                DayOfWeek.FRIDAY, LocalTime.parse("10:00"), LocalTime.parse("16:00"))), owner);

        // Monday is withdrawn (soft delete), Friday inserted.
        verify(businessHourRepository).delete(existing);
        verify(businessHourRepository).save(any(BusinessHour.class));
    }

    @Test
    void replaceHours_movesTheExistingDay() {
        BusinessHour monday = BusinessHour.create(PROVIDER_ID, DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("17:00"));
        when(businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(PROVIDER_ID))
                .thenReturn(List.of(monday));
        when(businessHourRepository.findByProviderIdAndDayOfWeek(PROVIDER_ID,
                DayOfWeek.MONDAY.getValue())).thenReturn(Optional.of(monday));

        List<BusinessHour> week = service.replaceHours(PROVIDER_ID, List.of(
                new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.MONDAY, LocalTime.parse("10:00"), LocalTime.parse("14:00"))),
                owner);

        assertThat(monday.getOpensAt()).isEqualTo(LocalTime.parse("10:00"));
        assertThat(week).containsExactly(monday);
    }

    @Test
    void replaceHours_duplicateWeekday_isRejectedLoudly() {
        assertThatThrownBy(() -> service.replaceHours(PROVIDER_ID, List.of(
                new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("17:00")),
                new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.MONDAY, LocalTime.parse("10:00"), LocalTime.parse("14:00"))),
                owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate weekday");
    }

    @Test
    void replaceHours_moreThanSevenEntries_isRejected() {
        // Eight entries over the seven-day week — the size gate fires
        // before the per-day duplicate scan, so repeated days are fine.
        List<ProviderBusinessPageService.HoursEntry> eight = java.util.stream.IntStream
                .rangeClosed(1, 8).mapToObj(d -> new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("17:00")))
                .toList();
        assertThatThrownBy(() -> service.replaceHours(PROVIDER_ID, eight, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 7");
    }

    // -- the declared menu (G12) ---------------------------------------------------

    @Test
    void addService_allocatesMaxPlusOne() {
        when(offeredServiceRepository.findMaxPositionByProviderId(PROVIDER_ID))
                .thenReturn(Optional.of(4));
        when(offeredServiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OfferedService added = service.addService(PROVIDER_ID,
                new ProviderBusinessPageService.ServiceEntry(
                        "تنظيف عميق", "شامل", 120, 15_000L, "SAR"), owner);

        assertThat(added.getPosition()).isEqualTo(5);
        assertThat(added.getTitle()).isEqualTo("تنظيف عميق");
    }

    @Test
    void addService_firstRowStartsAtZero() {
        when(offeredServiceRepository.findMaxPositionByProviderId(PROVIDER_ID))
                .thenReturn(Optional.empty());
        when(offeredServiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.addService(PROVIDER_ID,
                        new ProviderBusinessPageService.ServiceEntry("t", null, null, null, null), owner)
                .getPosition()).isZero();
    }

    @Test
    void addService_nullEntry_isRejected() {
        assertThatThrownBy(() -> service.addService(PROVIDER_ID, null, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateService_replacesTheDisplayFields() {
        OfferedService existing = OfferedService.create(PROVIDER_ID, "قديم", null, null, null, null, 2);
        when(offeredServiceRepository.findById(existing.getId()))
                .thenReturn(Optional.of(existing));
        when(offeredServiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OfferedService amended = service.updateService(PROVIDER_ID, existing.getId(),
                new ProviderBusinessPageService.ServiceEntry("جديد", "وصف", 60, 5_000L, "SAR"), owner);

        assertThat(amended.getTitle()).isEqualTo("جديد");
        assertThat(amended.getPosition()).isEqualTo(2); // untouched by the update
    }

    @Test
    void updateService_foreignRow_answers404() {
        UUID foreign = UUID.randomUUID();
        when(offeredServiceRepository.findById(foreign)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateService(PROVIDER_ID, foreign,
                new ProviderBusinessPageService.ServiceEntry("t", null, null, null, null), owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void moveService_swapsWithTheOccupant() {
        OfferedService mover = OfferedService.create(PROVIDER_ID, "a", null, null, null, null, 0);
        OfferedService occupant = OfferedService.create(PROVIDER_ID, "b", null, null, null, null, 3);
        when(offeredServiceRepository.findById(mover.getId())).thenReturn(Optional.of(mover));
        when(offeredServiceRepository.findByProviderIdAndPosition(PROVIDER_ID, 3))
                .thenReturn(Optional.of(occupant));
        when(offeredServiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.moveService(PROVIDER_ID, mover.getId(), 3, owner);

        assertThat(mover.getPosition()).isEqualTo(3);
        assertThat(occupant.getPosition()).isZero(); // the swap form
    }

    @Test
    void moveService_toItsOwnPosition_isANoOp() {
        OfferedService mover = OfferedService.create(PROVIDER_ID, "a", null, null, null, null, 2);
        when(offeredServiceRepository.findById(mover.getId())).thenReturn(Optional.of(mover));

        service.moveService(PROVIDER_ID, mover.getId(), 2, owner);

        verify(offeredServiceRepository, never()).save(any());
    }

    @Test
    void removeService_softDeletesThroughTheRepository() {
        OfferedService existing = OfferedService.create(PROVIDER_ID, "a", null, null, null, null, 0);
        when(offeredServiceRepository.findById(existing.getId()))
                .thenReturn(Optional.of(existing));

        service.removeService(PROVIDER_ID, existing.getId(), owner);

        verify(offeredServiceRepository).delete(existing);
    }

    // -- the declared areas (G13) ----------------------------------------------------

    @Test
    void addArea_savesTheDeclaration() {
        UUID locationId = UUID.randomUUID();
        when(serviceAreaRepository.existsByProviderIdAndLocationId(PROVIDER_ID, locationId))
                .thenReturn(false);
        when(serviceAreaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ServiceArea declared = service.addArea(PROVIDER_ID, locationId, owner);

        assertThat(declared.getLocationId()).isEqualTo(locationId);
    }

    @Test
    void addArea_duplicate_failsLoudly() {
        UUID locationId = UUID.randomUUID();
        when(serviceAreaRepository.existsByProviderIdAndLocationId(PROVIDER_ID, locationId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addArea(PROVIDER_ID, locationId, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(serviceAreaRepository, never()).save(any());
    }

    @Test
    void removeArea_foreignRow_answers404() {
        UUID areaId = UUID.randomUUID();
        when(serviceAreaRepository.findById(areaId)).thenReturn(Optional.of(
                ServiceArea.create(UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> service.removeArea(PROVIDER_ID, areaId, owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -- the reads --------------------------------------------------------------------

    @Test
    void reads_serveTheThreeBlocks() {
        when(businessHourRepository.findByProviderIdOrderByDayOfWeekAsc(PROVIDER_ID))
                .thenReturn(List.of());
        when(offeredServiceRepository.findByProviderIdOrderByPositionAsc(PROVIDER_ID))
                .thenReturn(List.of());
        when(serviceAreaRepository.findByProviderIdOrderByIdAsc(PROVIDER_ID))
                .thenReturn(List.of());

        assertThat(service.getHours(PROVIDER_ID)).isEmpty();
        assertThat(service.getServices(PROVIDER_ID)).isEmpty();
        assertThat(service.getAreas(PROVIDER_ID)).isEmpty();
    }
}
