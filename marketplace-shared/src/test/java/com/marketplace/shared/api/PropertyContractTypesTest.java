package com.marketplace.shared.api;

import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L31: the shared-api realestate contract types — the enums' full value
 * sets (valueOf round-trips, exactly the plan's enumerated sets) and the
 * {@code PropertyView} record's equality contract. These types live in
 * shared because the cross-module criteria (L32) and the catalog embed
 * consume them; the module that owns the data (realestate) tests the
 * behavior, this file pins the contract surface.
 */
class PropertyContractTypesTest {

    @Test
    void propertyPurpose_coversExactlyThePlanSet() {
        assertThat(java.util.Arrays.toString(PropertyPurpose.values()))
                .isEqualTo("[RENT, SALE]");
        assertThat(PropertyPurpose.valueOf("RENT")).isEqualTo(PropertyPurpose.RENT);
        assertThat(PropertyPurpose.valueOf("SALE")).isEqualTo(PropertyPurpose.SALE);
    }

    @Test
    void propertyType_coversExactlyThePlanSet() {
        assertThat(java.util.Arrays.toString(PropertyType.values()))
                .isEqualTo("[APARTMENT, VILLA, LAND, SHOP, OFFICE, GARAGE]");
        for (PropertyType type : PropertyType.values()) {
            assertThat(PropertyType.valueOf(type.name())).isEqualTo(type);
        }
    }

    @Test
    void propertyView_equalityAndAccessors() {
        UUID listingId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        PropertyView view = new PropertyView(listingId, PropertyPurpose.RENT,
                PropertyType.APARTMENT, 120, 3, 2, 1, 5, 2015, false,
                List.of("elevator", "parking"), null, locationId,
                BigDecimal.valueOf(33.5), BigDecimal.valueOf(36.2));

        PropertyView same = new PropertyView(listingId, PropertyPurpose.RENT,
                PropertyType.APARTMENT, 120, 3, 2, 1, 5, 2015, false,
                List.of("elevator", "parking"), null, locationId,
                BigDecimal.valueOf(33.5), BigDecimal.valueOf(36.2));
        PropertyView different = new PropertyView(listingId, PropertyPurpose.SALE,
                PropertyType.APARTMENT, 120, 3, 2, 1, 5, 2015, false,
                List.of("elevator", "parking"), null, locationId,
                BigDecimal.valueOf(33.5), BigDecimal.valueOf(36.2));

        assertThat(view).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(view).isNotEqualTo(different);
        assertThat(view.listingId()).isEqualTo(listingId);
        assertThat(view.purpose()).isEqualTo(PropertyPurpose.RENT);
        assertThat(view.propertyType()).isEqualTo(PropertyType.APARTMENT);
        assertThat(view.areaM2()).isEqualTo(120);
        assertThat(view.rooms()).isEqualTo(3);
        assertThat(view.bathrooms()).isEqualTo(2);
        assertThat(view.floorNumber()).isEqualTo(1);
        assertThat(view.totalFloors()).isEqualTo(5);
        assertThat(view.buildingYear()).isEqualTo(2015);
        assertThat(view.furnished()).isFalse();
        assertThat(view.amenities()).containsExactly("elevator", "parking");
        assertThat(view.locationId()).isEqualTo(locationId);
        assertThat(view.latitude()).isEqualByComparingTo("33.5");
        assertThat(view.longitude()).isEqualByComparingTo("36.2");
        assertThat(view.toString()).contains("APARTMENT");
    }
}
