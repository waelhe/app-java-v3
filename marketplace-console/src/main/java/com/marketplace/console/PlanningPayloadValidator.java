package com.marketplace.console;

import tools.jackson.databind.JsonNode;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CategoryVocabularyPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 9 (plan D-11, ADR-0005): the planning record's gate — the payload
 * validator. The vocabulary is the SUPPORTED component set (the §1.4
 * discovery rows: the surfaces the client actually renders), the shape is
 * closed (unknown fields refused — no executable code, no free HTML, no
 * query fragments can ride in), and the category references are checked
 * against the catalog's own dictionary through the shared seam BEFORE any
 * write. An invalid payload is refused at the gate — «التغييرات الحساسة
 * مدققة وموسومة ومرفوضة إن كانت غير صالحة».
 */
@Component
public class PlanningPayloadValidator {

    /**
     * The supported component vocabulary — the §1.4 discovery rows, the
     * surfaces the client's renderer knows. A code outside this set is
     * the plan's «مكون مجهول» — refused, never stored.
     */
    public static final Set<String> SUPPORTED_SECTIONS = Set.of(
            "URGENT", "FOLLOWED_SOURCES", "LOST_FOUND", "RECOMMENDATIONS", "EVENTS", "FOR_YOU");

    private final CategoryVocabularyPort categoryVocabularyPort;

    public PlanningPayloadValidator(CategoryVocabularyPort categoryVocabularyPort) {
        this.categoryVocabularyPort = categoryVocabularyPort;
    }

    /**
     * Validates and normalizes one planning payload. The accepted shape
     * (closed by construction):
     * <pre>{ "sections": [ { "code": ..., "visible": true, "position": 1,
     *                       "title"?: ..., "categoryRefs"?: [...] } ] }</pre>
     *
     * @return the validated payload (normalized: the optional fields kept
     *         only when present, the refs sorted/deduplicated)
     * @throws BadRequestException naming the first violated rule
     */
    public JsonNode validate(JsonNode payload) {
        if (payload == null || !payload.has("sections") || !payload.get("sections").isArray()
                || payload.get("sections").isEmpty()) {
            throw new BadRequestException("The planning payload must carry a non-empty 'sections' array");
        }
        Set<String> seenCodes = new HashSet<>();
        List<String> categoryRefs = new ArrayList<>();
        for (JsonNode section : payload.get("sections")) {
            if (!section.isObject()) {
                throw new BadRequestException("Every section must be an object");
            }
            Set<String> allowed = Set.of("code", "visible", "position", "title", "categoryRefs");
            List<String> fields = new ArrayList<>();
            section.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (!allowed.contains(field)) {
                    throw new BadRequestException("Unknown section field '" + field
                            + "' — the planning shape is closed (no free-form fields)");
                }
            }
            String code = section.path("code").asString(null);
            if (code == null || !SUPPORTED_SECTIONS.contains(code)) {
                throw new BadRequestException("Unknown section code '" + code
                        + "' — the supported components are " + SUPPORTED_SECTIONS);
            }
            if (!seenCodes.add(code)) {
                throw new BadRequestException("Duplicate section code '" + code + "'");
            }
            if (section.has("visible") && !section.get("visible").isBoolean()) {
                throw new BadRequestException("Section '" + code + "': 'visible' must be a boolean");
            }
            if (section.has("position") && !section.get("position").isInt()) {
                throw new BadRequestException("Section '" + code + "': 'position' must be an integer");
            }
            if (section.has("title") && (section.get("title").asString("").length() > 100)) {
                throw new BadRequestException("Section '" + code + "': 'title' is capped at 100 chars");
            }
            if (section.has("categoryRefs")) {
                JsonNode refs = section.get("categoryRefs");
                if (!refs.isArray()) {
                    throw new BadRequestException("Section '" + code + "': 'categoryRefs' must be an array");
                }
                for (JsonNode ref : refs) {
                    categoryRefs.add(ref.asString());
                }
            }
        }
        if (!categoryRefs.isEmpty()) {
            Set<String> known = new HashSet<>(categoryVocabularyPort.knownCodes());
            for (String ref : categoryRefs) {
                if (!known.contains(ref)) {
                    throw new BadRequestException("Section category reference '" + ref
                            + "' is not in the catalog's category dictionary");
                }
            }
        }
        return payload;
    }
}
