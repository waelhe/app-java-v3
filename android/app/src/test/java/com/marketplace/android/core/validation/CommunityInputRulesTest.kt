package com.marketplace.android.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityInputRulesTest {
    @Test
    fun registrationAcceptsTheBackendLimits() {
        assertNull(CommunityInputRules.registrationError("Wael", "wael@example.com", "12345678"))
        assertNull(CommunityInputRules.registrationError("و".repeat(100), "a@b.co", "p".repeat(72)))
    }

    @Test
    fun registrationRejectsInvalidEmailAndPasswordBoundaries() {
        assertEquals(
            "أدخل بريدًا إلكترونيًا صالحًا لا يتجاوز 50 حرفًا.",
            CommunityInputRules.registrationError("Wael", "not-an-email", "12345678")
        )
        assertEquals(
            "يجب أن تتراوح كلمة المرور بين 8 و72 حرفًا.",
            CommunityInputRules.registrationError("Wael", "wael@example.com", "1234567")
        )
        assertEquals(
            "يجب أن تتراوح كلمة المرور بين 8 و72 حرفًا.",
            CommunityInputRules.registrationError("Wael", "wael@example.com", "p".repeat(73))
        )
    }

    @Test
    fun postValidationMatchesTheBackendCategoryAndTextBounds() {
        assertNull(CommunityInputRules.postError("area-id", "QUESTION", "سؤال", "أين المكتبة؟"))
        assertEquals(
            "اختر نوع مشاركة صالحًا.",
            CommunityInputRules.postError("area-id", "INVENTED", "عنوان", "نص")
        )
        assertEquals(
            "يجب ألا يتجاوز العنوان 200 حرف.",
            CommunityInputRules.postError("area-id", "GENERAL", "ع".repeat(201), "نص")
        )
        assertEquals(
            "يجب ألا يتجاوز نص المشاركة 2000 حرف.",
            CommunityInputRules.postError("area-id", "GENERAL", "عنوان", "ن".repeat(2001))
        )
    }

    @Test
    fun commentMustBePresentAndStayWithinTheServerBound() {
        assertEquals("اكتب تعليقًا قبل الإرسال.", CommunityInputRules.commentError("   "))
        assertEquals("يجب ألا يتجاوز التعليق 2000 حرف.", CommunityInputRules.commentError("ت".repeat(2001)))
        assertNull(CommunityInputRules.commentError("رد مفيد"))
    }
}
