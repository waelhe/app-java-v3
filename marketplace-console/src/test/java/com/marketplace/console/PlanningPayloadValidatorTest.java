package com.marketplace.console;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CategoryVocabularyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 9 (ADR-0005) — the planning record's gate: the closed shape (an
 * unknown field is refused — no free-form payload can ride in), the
 * supported component vocabulary (an unknown code is refused), the
 * duplicates, the typed fields, and the category references checked
 * against the catalog's own dictionary BEFORE any write.
 */
class PlanningPayloadValidatorTest {

    private final CategoryVocabularyPort categoryVocabularyPort = mock(CategoryVocabularyPort.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private PlanningPayloadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PlanningPayloadValidator(categoryVocabularyPort);
        when(categoryVocabularyPort.knownCodes()).thenReturn(List.of("journey-appliances"));
    }

    @Test
    void theValidPayloadPassesTheGate() throws Exception {
        String payload = """
                {"sections":[
                  {"code":"URGENT","visible":true,"position":1},
                  {"code":"FOR_YOU","visible":true,"position":2,"title":"لك",
                   "categoryRefs":["journey-appliances"]}
                ]}""";

        var validated = validator.validate(mapper.readTree(payload));

        assertThat(validated).isNotNull();
    }

    @Test
    void anUnknownSectionCodeIsRefused() throws Exception {
        var payload = mapper.readTree("""
                {"sections":[{"code":"MEGA_ROW","visible":true,"position":1}]}""");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown section code");
    }

    @Test
    void anUnknownFieldIsRefusedTheShapeIsClosed() throws Exception {
        var payload = mapper.readTree("""
                {"sections":[{"code":"EVENTS","visible":true,"position":1,
                              "arbitraryHtml":"<script>"}]}""");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown section field");
    }

    @Test
    void aDuplicateSectionCodeIsRefused() throws Exception {
        var payload = mapper.readTree("""
                {"sections":[{"code":"EVENTS","visible":true,"position":1},
                             {"code":"EVENTS","visible":false,"position":2}]}""");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void anInvalidCategoryReferenceIsRefusedBeforeAnyWrite() throws Exception {
        var payload = mapper.readTree("""
                {"sections":[{"code":"RECOMMENDATIONS","visible":true,"position":1,
                              "categoryRefs":["not-a-real-category"]}]}""");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not in the catalog");
    }

    @Test
    void aTypedFieldCarryingTheWrongTypeIsRefused() throws Exception {
        var payload = mapper.readTree("""
                {"sections":[{"code":"EVENTS","visible":"yes-please","position":1}]}""");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'visible' must be a boolean");
    }

    @Test
    void anEmptyOrMissingSectionsArrayIsRefused() throws Exception {
        assertThatThrownBy(() -> validator.validate(mapper.readTree("{}")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validate(mapper.readTree("{\"sections\":[]}")))
                .isInstanceOf(BadRequestException.class);
    }
}
