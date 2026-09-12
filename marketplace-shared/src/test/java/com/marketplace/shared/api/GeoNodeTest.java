package com.marketplace.shared.api;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L30: the cross-module geo view record — both construction forms and the
 * nesting contract (children default to the empty list in the flat form,
 * so consumers never dereference null).
 */
class GeoNodeTest {

    @Test
    void flatForm_carriesNoChildren() {
        UUID id = UUID.randomUUID();
        GeoNode node = new GeoNode(id, null, 0, "سوريا", "Syria", "syria");

        assertThat(node.id()).isEqualTo(id);
        assertThat(node.parentId()).isNull();
        assertThat(node.level()).isZero();
        assertThat(node.nameAr()).isEqualTo("سوريا");
        assertThat(node.nameEn()).isEqualTo("Syria");
        assertThat(node.slug()).isEqualTo("syria");
        assertThat(node.children()).isEmpty();
    }

    @Test
    void nestedForm_carriesChildren() {
        UUID countryId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        GeoNode city = new GeoNode(cityId, countryId, 2, "قدسيا", "Qudsayya", "qudsayya");
        GeoNode country = new GeoNode(countryId, null, 0, "سوريا", "Syria", "syria",
                List.of(city));

        assertThat(country.children()).containsExactly(city);
        assertThat(country.children().get(0).id()).isEqualTo(cityId);
        assertThat(country.children().get(0).parentId()).isEqualTo(countryId);
    }
}
