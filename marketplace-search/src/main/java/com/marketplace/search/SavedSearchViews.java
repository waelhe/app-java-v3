package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import tools.jackson.databind.ObjectMapper;

/**
 * L35: the list view's criteria re-serialization — the stored record
 * through the app's mapper (the same JSON the export port hands the
 * subject). Package-private on purpose: the controller owns the API
 * shapes, this is its serialization helper.
 */
final class SavedSearchViews {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SavedSearchViews() {
    }

    static tools.jackson.databind.JsonNode criteriaNode(SavedSearch saved) {
        SearchCriteria criteria = saved.getCriteria();
        if (criteria == null) {
            return MAPPER.nullNode();
        }
        return MAPPER.valueToTree(criteria);
    }
}
