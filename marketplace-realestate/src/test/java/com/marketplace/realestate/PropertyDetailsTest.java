package com.marketplace.realestate;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L31 entity floor: every consistency gate of the field set — the
 * acceptance criterion "invalid values (rooms 0, negative area, floor >
 * total, purpose outside the enum) are 400 from the factory before any
 * write".
 */
class PropertyDetailsTest {

    @Test
    void create_thenReadBack_allFieldsSurvive() {
        PropertyDetails details = PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().build());

        assertThat(details.getPurpose()).isEqualTo(PropertyPurpose.RENT);
        assertThat(details.getPropertyType()).isEqualTo(PropertyType.APARTMENT);
        assertThat(details.getAreaM2()).isEqualTo(120);
        assertThat(details.getRooms()).isEqualTo(3);
        assertThat(details.getBathrooms()).isEqualTo(2);
        assertThat(details.getFloorNumber()).isEqualTo(2);
        assertThat(details.getTotalFloors()).isEqualTo(5);
        assertThat(details.getBuildingYear()).isEqualTo(2015);
        assertThat(details.getFurnished()).isTrue();
        assertThat(details.getAmenities()).containsExactly("elevator", "parking");
        assertThat(details.getAvailableFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(details.getLatitude()).isEqualByComparingTo("33.5138");
        assertThat(details.getLongitude()).isEqualByComparingTo("36.2765");
    }

    @Test
    void apply_replacesTheWholeFieldSet_putSemantics() {
        PropertyDetails details = PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().build());

        details.apply(req().areaM2(200).rooms(4).amenities(List.of("pool")).build());

        assertThat(details.getAreaM2()).isEqualTo(200);
        assertThat(details.getRooms()).isEqualTo(4);
        assertThat(details.getAmenities()).containsExactly("pool");
    }

    @Test
    void apply_missingEnumPair_isRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().purpose(null).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("purpose");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().propertyType(null).build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void nonPositiveNumbers_areRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().areaM2(0).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("areaM2");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().areaM2(-5).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("areaM2");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().rooms(0).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("rooms");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().bathrooms(-1).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("bathrooms");
    }

    @Test
    void floorAboveTotal_isRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().floorNumber(7).totalFloors(5).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("floorNumber must not exceed totalFloors");
    }

    @Test
    void floorWithoutTotal_isAccepted_openEndedBuilding() {
        assertThatCode(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().totalFloors(null).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void negativeFloor_basement_isAccepted() {
        PropertyDetails details = PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().floorNumber(-1).totalFloors(2).build());
        assertThat(details.getFloorNumber()).isEqualTo(-1);
    }

    @Test
    void buildingYearOutsideRange_isRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().buildingYear(1700).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("buildingYear");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().buildingYear(3000).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("buildingYear");
    }

    @Test
    void coordinatesOutsideBounds_areRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().latitude(BigDecimal.valueOf(91)).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("latitude");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().longitude(BigDecimal.valueOf(-181)).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("longitude");
    }

    @Test
    void amenities_duplicatesAreRejectedExplicitly_neverSilentlyFixed() {
        // The type-gate philosophy: a duplicate is a client mistake — a 400,
        // not a silent dedup the client cannot see.
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().amenities(List.of("elevator", "parking", "elevator")).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("duplicate amenity");
    }

    @Test
    void amenities_orderIsPreserved() {
        PropertyDetails details = PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().amenities(List.of("parking", "elevator")).build());

        assertThat(details.getAmenities()).containsExactly("parking", "elevator");
    }

    @Test
    void amenities_blankAndDuplicateAndOverflow_areRejected() {
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().amenities(List.of("  ")).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(), req().amenities(List.of("a", "a")).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().amenities(java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> "amenity" + i).toList()).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at most 20");
        assertThatThrownBy(() -> PropertyDetails.create(
                UUID.randomUUID(), UUID.randomUUID(),
                req().amenities(List.of("x".repeat(41))).build()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("40");
    }

    @Test
    void documentedVocabulary_isAvailableForConsumers() {
        assertThat(PropertyDetails.DOCUMENTED_AMENITIES).isNotEmpty();
        assertThat(PropertyDetails.DOCUMENTED_AMENITIES).contains("elevator", "parking");
    }

    /** Compact mutable fixture: one valid base, per-test overrides, one build. */
    static Req req() {
        return new Req();
    }

    static final class Req {
        com.marketplace.shared.api.PropertyPurpose purpose = com.marketplace.shared.api.PropertyPurpose.RENT;
        com.marketplace.shared.api.PropertyType propertyType = com.marketplace.shared.api.PropertyType.APARTMENT;
        Integer areaM2 = 120; Integer rooms = 3; Integer bathrooms = 2;
        Integer floorNumber = 2; Integer totalFloors = 5; Integer buildingYear = 2015;
        Boolean furnished = true;
        List<String> amenities = List.of("elevator", "parking");
        LocalDate availableFrom = LocalDate.of(2026, 10, 1);
        UUID locationId = UUID.randomUUID();
        BigDecimal latitude = BigDecimal.valueOf(33.5138);
        BigDecimal longitude = BigDecimal.valueOf(36.2765);

        Req purpose(com.marketplace.shared.api.PropertyPurpose v) { this.purpose = v; return this; }
        Req propertyType(com.marketplace.shared.api.PropertyType v) { this.propertyType = v; return this; }
        Req areaM2(Integer v) { this.areaM2 = v; return this; }
        Req rooms(Integer v) { this.rooms = v; return this; }
        Req bathrooms(Integer v) { this.bathrooms = v; return this; }
        Req floorNumber(Integer v) { this.floorNumber = v; return this; }
        Req totalFloors(Integer v) { this.totalFloors = v; return this; }
        Req buildingYear(Integer v) { this.buildingYear = v; return this; }
        Req furnished(Boolean v) { this.furnished = v; return this; }
        Req amenities(List<String> v) { this.amenities = v; return this; }
        Req availableFrom(LocalDate v) { this.availableFrom = v; return this; }
        Req locationId(UUID v) { this.locationId = v; return this; }
        Req latitude(BigDecimal v) { this.latitude = v; return this; }
        Req longitude(BigDecimal v) { this.longitude = v; return this; }

        PropertyDetailsRequest build() {
            return new PropertyDetailsRequest(purpose, propertyType, areaM2, rooms, bathrooms,
                    floorNumber, totalFloors, buildingYear, furnished, amenities, availableFrom,
                    locationId, latitude, longitude);
        }
    }
}
