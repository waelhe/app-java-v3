package com.marketplace.dayf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PostCategoryLabelsTest {
    @Test
    fun mapsEveryBackendPostCategoryToArabic() {
        assertEquals("حديث الحي", PostCategoryLabels.arabic("GENERAL"))
        assertEquals("سؤال", PostCategoryLabels.arabic("QUESTION"))
        assertEquals("طلب مساعدة", PostCategoryLabels.arabic("REQUEST"))
        assertEquals("توصية", PostCategoryLabels.arabic("RECOMMENDATION"))
        assertEquals("مفقودات", PostCategoryLabels.arabic("LOST_FOUND"))
        assertEquals("إعلان", PostCategoryLabels.arabic("CLASSIFIED"))
    }

    @Test
    fun unknownCategoryIsNotShownAsRawBackendEnum() {
        assertEquals("منشور", PostCategoryLabels.arabic("FUTURE_VALUE"))
    }
}
