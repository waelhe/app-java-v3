package com.marketplace.geo;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L30 entity gates: the hierarchy cannot be broken through the factory —
 * level skips, parentless children, parented roots, blank names and
 * malformed slugs are all rejected at construction (the acceptance
 * criterion "a hierarchical level skip is impossible").
 */
class GeoLocationTest {

    private static GeoLocation root() {
        return GeoLocation.createRoot("سوريا", "Syria", "syria");
    }

    @Test
    void createRoot_hasCountryLevelAndNoParent() {
        GeoLocation root = root();
        assertThat(root.getLevel()).isZero();
        assertThat(root.getParentId()).isNull();
        assertThat(root.getNameAr()).isEqualTo("سوريا");
        assertThat(root.getSlug()).isEqualTo("syria");
    }

    @Test
    void createChild_derivesLevelFromParent() {
        GeoLocation syria = root();
        GeoLocation governorate = GeoLocation.createChild(syria, "ريف دمشق", "Rif Dimashq", "rif-dimashq");
        assertThat(governorate.getLevel()).isEqualTo(1);
        assertThat(governorate.getParentId()).isEqualTo(syria.getId());

        GeoLocation city = GeoLocation.createChild(governorate, "قدسيا", "Qudsayya", "qudsayya");
        assertThat(city.getLevel()).isEqualTo(2);
    }

    @Test
    void createChild_underNeighborhood_isRejected_deepestLevel() {
        GeoLocation country = root();
        GeoLocation governorate = GeoLocation.createChild(country, "ريف دمشق", null, "rif-dimashq");
        GeoLocation city = GeoLocation.createChild(governorate, "قدسيا", null, "qudsayya");
        GeoLocation neighborhood = GeoLocation.createChild(city, "البلد", null, "qudsayya-old-town");

        assertThatThrownBy(() -> GeoLocation.createChild(neighborhood, "مستحيل", null, "impossible"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("deepest level");
    }

    @Test
    void createChild_withoutParent_isRejected() {
        assertThatThrownBy(() -> GeoLocation.createChild(null, "يتيم", null, "orphan"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("requires a parent");
    }

    @Test
    void createChild_rejectsMalformedSlug() {
        assertThatThrownBy(() -> GeoLocation.createChild(root(), "حي", null, "Qudsayya"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("slug");
        assertThatThrownBy(() -> GeoLocation.createChild(root(), "حي", null, "ق"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("slug");
        assertThatThrownBy(() -> GeoLocation.createChild(root(), "حي", null, "x"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("slug");
    }

    @Test
    void createChild_rejectsBlankName() {
        assertThatThrownBy(() -> GeoLocation.createChild(root(), "   ", null, "valid-slug"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("nameAr");
    }

    @Test
    void update_renamesAndReSlugs_butNeverMoves() {
        GeoLocation city = GeoLocation.createChild(root(), "قدسيا", null, "qudsayya");
        UUID beforeId = city.getId();
        UUID beforeParent = city.getParentId();
        int beforeLevel = city.getLevel();

        city.update("مدينة قدسيا", "Qudsayya City", "qudsayya-city");

        assertThat(city.getId()).isEqualTo(beforeId);
        assertThat(city.getParentId()).isEqualTo(beforeParent);
        assertThat(city.getLevel()).isEqualTo(beforeLevel);
        assertThat(city.getNameAr()).isEqualTo("مدينة قدسيا");
        assertThat(city.getNameEn()).isEqualTo("Qudsayya City");
        assertThat(city.getSlug()).isEqualTo("qudsayya-city");
    }

    @Test
    void update_blankEnglishName_normalizesToNull() {
        GeoLocation city = GeoLocation.createChild(root(), "قدسيا", "  ", "qudsayya");
        assertThat(city.getNameEn()).isNull();
    }

    @Test
    void geoLevel_roundTripsStoredLevel() {
        for (GeoLevel level : GeoLevel.values()) {
            assertThat(GeoLevel.of(level.level())).isEqualTo(level);
        }
        assertThatThrownBy(() -> GeoLevel.of(9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreGeneratedDistinct() {
        assertThat(root().getId()).isNotEqualTo(root().getId());
        assertThat(root().getId()).isInstanceOf(UUID.class);
    }
}
