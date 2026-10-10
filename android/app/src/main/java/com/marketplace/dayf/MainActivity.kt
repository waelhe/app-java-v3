package com.marketplace.dayf

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.marketplace.dayf.data.PlatformApi
import com.marketplace.dayf.data.PlatformRepository
import com.marketplace.dayf.data.SecureSessionStore
import com.marketplace.dayf.ui.DayfApp
import com.marketplace.dayf.ui.DayfTheme
import com.marketplace.dayf.ui.PlatformViewModel
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

class MainActivity : ComponentActivity() {
    private lateinit var sessionStore: SecureSessionStore
    private lateinit var authorizationService: AuthorizationService
    private lateinit var viewModel: PlatformViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        sessionStore = SecureSessionStore(applicationContext)
        authorizationService = AuthorizationService(this)
        val api = PlatformApi(BuildConfig.API_BASE_URL) { sessionStore.accessToken() }
        val repository = PlatformRepository(api)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlatformViewModel(repository, sessionStore) as T
                }
            }
        )[PlatformViewModel::class.java]

        setContent {
            DayfTheme {
                DayfApp(
                    viewModel = viewModel,
                    onSignIn = ::beginAuthorization,
                    onSignOut = {
                        // Verify OIDC end-session metadata before claiming server-wide logout.
                        sessionStore.clear()
                        viewModel.refreshJourney()
                    }
                )
            }
        }

        viewModel.refreshJourney()
        handleAuthorizationResult(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationResult(intent)
    }

    private fun beginAuthorization() {
        if (BuildConfig.OAUTH_CLIENT_ID.isBlank()) {
            viewModel.showError(
                "لم يُضبط معرّف العميل العام. ابنِ التطبيق مع OAUTH_PUBLIC_CLIENT_ID بعد مطابقته مع إعداد الخادم."
            )
            return
        }

        val redirectUri = Uri.parse(BuildConfig.OAUTH_REDIRECT_URI)
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.AUTH_ISSUER)) { configuration, exception ->
            runOnUiThread {
                if (configuration == null) {
                    viewModel.showError(
                        "تعذّر اكتشاف إعدادات الدخول. تحقّق من AUTH_SERVER_ISSUER وعنوان الخادم. " +
                            (exception?.errorDescription ?: "")
                    )
                    return@runOnUiThread
                }

                val request = AuthorizationRequest.Builder(
                    configuration,
                    BuildConfig.OAUTH_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    redirectUri
                )
                    .setScopes("openid", "profile")
                    .build()

                val completionIntent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // AppAuth must be able to attach the authorization result to the intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val completed = PendingIntent.getActivity(this, request.hashCode(), completionIntent, flags)
                val canceled = PendingIntent.getActivity(this, request.hashCode() + 1, completionIntent, flags)
                authorizationService.performAuthorizationRequest(request, completed, canceled)
            }
        }
    }

    private fun handleAuthorizationResult(resultIntent: Intent?) {
        if (resultIntent == null) return
        val response = AuthorizationResponse.fromIntent(resultIntent)
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (response == null && exception == null) return

        if (response == null) {
            viewModel.showError(
                if (exception?.type == AuthorizationException.TYPE_GENERAL_ERROR) {
                    "تعذّر إكمال تسجيل الدخول. تحقّق من إعداد OAuth في الخادم."
                } else {
                    "لم يكتمل تسجيل الدخول. يمكنك المحاولة مجددًا."
                }
            )
            return
        }

        authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
            if (tokenResponse == null) {
                viewModel.showError(tokenException?.errorDescription ?: "تعذّر إتمام تبادل رمز الدخول.")
                return@performTokenRequest
            }

            val authState = AuthState()
            authState.update(response, null)
            authState.update(tokenResponse, tokenException)
            sessionStore.save(authState)
            viewModel.refreshJourney()
        }
    }

    override fun onDestroy() {
        authorizationService.dispose()
        super.onDestroy()
    }
}
