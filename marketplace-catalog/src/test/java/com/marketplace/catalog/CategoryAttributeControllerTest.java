package com.marketplace.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2xW3 merge round (2026-10-03): the registry controller's own
 * module-local unit guards — the {@code CatalogControllerTest} house
 * pattern (plain Mockito, no Spring context; the web wiring stays with
 * the app-side WebMvc slice). The waves' own branches closed their
 * service-level gates; the merged tree rides both waves' uncovered
 * controllers against the module's BUNDLE budget, so the composition
 * contracts pin HERE:
 * <ul>
 *   <li>the public read maps the registry's own rows through the
 *       response record (the same fields the registry declares — no
 *       projection drift);</li>
 *   <li>register passes the identity pair through verbatim and answers
 *       the composed row;</li>
 *   <li>update passes the replaceable fields (never the identity pair —
 *       the V70 immutability law lives one layer below);</li>
 *   <li>remove delegates and answers 204.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CategoryAttributeControllerTest {

    @Mock
    private CategoryAttributeService categoryAttributeService;

    @InjectMocks
    private CategoryAttributeController controller;

    private final UUID categoryId = UUID.randomUUID();
    private final UUID attributeId = UUID.randomUUID();

    private CategoryAttribute row(int position) {
        return CategoryAttribute.create(categoryId, "wifi", "Wi-Fi", "واي فاي",
                CategoryAttributeType.BOOLEAN, position);
    }

    @BeforeEach
    void setUp() {
        // no shared state — every test declares its own stubbing
    }

    @Test
    void byCategory_mapsTheRegistryRowsThroughTheResponseRecord() {
        CategoryAttribute first = row(1);
        CategoryAttribute second = row(2);
        when(categoryAttributeService.byCategoryId(categoryId))
                .thenReturn(List.of(first, second));

        var response = controller.byCategory(categoryId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        var body = response.getBody();
        // first row: the registry's own fields, verbatim — no projection drift
        assertThat(body.getFirst().id()).isEqualTo(first.getId());
        assertThat(body.getFirst().categoryId()).isEqualTo(categoryId);
        assertThat(body.getFirst().code()).isEqualTo("wifi");
        assertThat(body.getFirst().labelEn()).isEqualTo("Wi-Fi");
        assertThat(body.getFirst().labelAr()).isEqualTo("واي فاي");
        assertThat(body.getFirst().valueType()).isEqualTo(CategoryAttributeType.BOOLEAN);
        assertThat(body.getFirst().position()).isEqualTo(1);
        // position order rides the service's own ordering, mapped as-is
        assertThat(body.get(1).position()).isEqualTo(2);
    }

    @Test
    void register_passesTheIdentityPairVerbatimAndAnswersTheComposedRow() {
        CategoryAttribute saved = row(3);
        when(categoryAttributeService.register(categoryId, "parking", "Parking",
                "مواقف", CategoryAttributeType.BOOLEAN)).thenReturn(saved);

        var response = controller.register(categoryId,
                new CategoryAttributeService.RegistrationRequest(
                        "parking", "Parking", "مواقف", CategoryAttributeType.BOOLEAN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().code()).isEqualTo("wifi");
        assertThat(response.getBody().id()).isEqualTo(saved.getId());
        verify(categoryAttributeService).register(categoryId, "parking", "Parking",
                "مواقف", CategoryAttributeType.BOOLEAN);
    }

    @Test
    void update_passesTheReplaceableFields_neverTheIdentityPair() {
        CategoryAttribute amended = row(1);
        when(categoryAttributeService.update(attributeId, "Wi-Fi (fast)", "واي فاي سريع",
                CategoryAttributeType.TEXT, 4)).thenReturn(amended);

        var response = controller.update(attributeId,
                new CategoryAttributeService.AmendmentRequest(
                        "Wi-Fi (fast)", "واي فاي سريع", CategoryAttributeType.TEXT, 4));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(amended.getId());
        // the identity pair never crosses this surface — the V70 law
        verify(categoryAttributeService).update(attributeId, "Wi-Fi (fast)", "واي فاي سريع",
                CategoryAttributeType.TEXT, 4);
    }

    @Test
    void remove_delegatesAndAnswersNoContent() {
        var response = controller.remove(attributeId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(categoryAttributeService).remove(attributeId);
    }
}
