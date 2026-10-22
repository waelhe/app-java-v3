package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.parser.PartTree;

import static org.assertj.core.api.Assertions.assertThatCode;

/** Agent 1: every declared derived query must bind to a real page property. */
class CmsPageRepositoryQueryTest {

    @Test
    void everyDerivedQueryNamesARealEntityProperty() {
        for (var method : CmsPageRepository.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isDefault()
                    || !method.getName().contains("By")) {
                continue;
            }
            assertThatCode(() -> new PartTree(method.getName(), CmsPage.class))
                    .as("derived query %s", method.getName())
                    .doesNotThrowAnyException();
        }
    }
}
