package com.marketplace.android.core.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.marketplace.android.BuildConfig
import com.marketplace.android.MainActivity
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses the backend's registered native public OAuth client. AppAuth handles
 * browser tabs, authorization-code exchange and PKCE; the app never carries
 * a confidential-client secret.
 */
class OidcAuthManager(
    private val activity: Activity,
    private val onAccessToken: (String, Long) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val processingResponse = AtomicBoolean(false)

    fun beginSignIn() {
        onMessage("جارٍ تجهيز تسجيل الدخول الآمن…")
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.BACKEND_BASE_URL)) { config, error ->
            activity.runOnUiThread {
                if (error != null || config == null) {
                    onMessage("تعذر الوصول إلى خدمة تسجيل الدخول. تحقّق من عنوان الخادم.")
                    return@runOnUiThread
                }

                val verifier = CodeVerifierUtil.generateRandomCodeVerifier()
                val challenge = CodeVerifierUtil.deriveCodeVerifierChallenge(verifier)
                val request = AuthorizationRequest.Builder(
                    config,
                    BuildConfig.OAUTH_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    Uri.parse(BuildConfig.OAUTH_REDIRECT_URI)
                )
                    .setScope("openid profile")
                    .setCodeVerifier(verifier, challenge, CodeVerifierUtil.CHALLENGE_METHOD_S256)
                    .build()

                val callbackIntent = Intent(activity, MainActivity::class.java).apply {
                    action = ACTION_AUTH_RESPONSE
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val callback = PendingIntent.getActivity(
                    activity,
                    AUTH_REQUEST_CODE,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                AuthorizationService(activity).performAuthorizationRequest(request, callback)
            }
        }
    }

    fun handleAuthorizationIntent(intent: Intent?) {
        if (intent == null || intent.action != ACTION_AUTH_RESPONSE) return
        if (!processingResponse.compareAndSet(false, true)) return

        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        if (exception != null || response == null) {
            processingResponse.set(false)
            onMessage("لم يكتمل تسجيل الدخول. أعد المحاولة.")
            intent.action = null
            return
        }

        onMessage("تم التحقق من الحساب؛ جارٍ إكمال الجلسة…")
        val service = AuthorizationService(activity)
        service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            activity.runOnUiThread {
                try {
                    val token = tokenResponse?.accessToken
                    if (tokenError != null || token.isNullOrBlank()) {
                        onMessage("تعذر إكمال الجلسة. قد يحتاج عنوان إعادة التوجيه إلى تسجيله على الخادم.")
                    } else {
                        val expiry = tokenResponse.accessTokenExpirationTime
                            ?: (System.currentTimeMillis() + DEFAULT_TOKEN_TTL_MILLIS)
                        onAccessToken(token, expiry)
                        onMessage("أهلًا بك. تم تسجيل الدخول.")
                    }
                } finally {
                    service.dispose()
                    processingResponse.set(false)
                    intent.action = null
                }
            }
        }
    }

    companion object {
        private const val AUTH_REQUEST_CODE = 7421
        private const val ACTION_AUTH_RESPONSE = "com.marketplace.android.AUTH_RESPONSE"
        private const val DEFAULT_TOKEN_TTL_MILLIS = 15 * 60 * 1000L
    }
}
