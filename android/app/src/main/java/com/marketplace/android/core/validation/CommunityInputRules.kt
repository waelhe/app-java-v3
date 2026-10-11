package com.marketplace.android.core.validation

object CommunityInputRules {
    val postCategories = setOf(
        "GENERAL",
        "CLASSIFIED",
        "LOST_FOUND",
        "RECOMMENDATION",
        "QUESTION",
        "REQUEST"
    )

    fun registrationError(displayName: String, email: String, password: String): String? {
        val name = displayName.trim()
        val normalizedEmail = email.trim()
        return when {
            name.isEmpty() -> "أدخل الاسم الظاهر."
            name.length > 100 -> "يجب ألا يتجاوز الاسم 100 حرف."
            normalizedEmail.isEmpty() || normalizedEmail.length > 50 ||
                !EMAIL_PATTERN.matches(normalizedEmail) -> "أدخل بريدًا إلكترونيًا صالحًا لا يتجاوز 50 حرفًا."
            password.length !in 8..72 -> "يجب أن تتراوح كلمة المرور بين 8 و72 حرفًا."
            else -> null
        }
    }

    fun postError(locationId: String?, category: String, title: String, body: String): String? {
        val normalizedTitle = title.trim()
        val normalizedBody = body.trim()
        return when {
            locationId.isNullOrBlank() -> "اختر حيّك أولًا قبل النشر."
            category !in postCategories -> "اختر نوع مشاركة صالحًا."
            normalizedTitle.isEmpty() -> "أدخل عنوانًا للمشاركة."
            normalizedTitle.length > 200 -> "يجب ألا يتجاوز العنوان 200 حرف."
            normalizedBody.isEmpty() -> "اكتب تفاصيل المشاركة."
            normalizedBody.length > 2000 -> "يجب ألا يتجاوز نص المشاركة 2000 حرف."
            else -> null
        }
    }

    fun commentError(body: String): String? = when {
        body.trim().isEmpty() -> "اكتب تعليقًا قبل الإرسال."
        body.trim().length > 2000 -> "يجب ألا يتجاوز التعليق 2000 حرف."
        else -> null
    }

    fun searchError(query: String): String? =
        if (query.trim().isEmpty()) "اكتب كلمة أو عبارة للبحث." else null

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}
