package com.marketplace.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android activity on an emulator and pins both authentication
 * entry points. Server-side ceremonies remain covered separately by backend tests;
 * this test does not fake a successful passkey assertion.
 */
@RunWith(AndroidJUnit4::class)
class AuthEntryPointE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun unauthenticatedScreenOffersPasskeyAndPkceFallback() {
        composeRule.onNodeWithText("المتابعة بمفتاح المرور").assertIsDisplayed()
        composeRule.onNodeWithText("تسجيل الدخول الآمن").assertIsDisplayed()
    }
}
