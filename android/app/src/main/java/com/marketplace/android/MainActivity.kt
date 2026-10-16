package com.marketplace.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.marketplace.android.data.MarketplaceRepository
import com.marketplace.android.feature.CommunityViewModel
import com.marketplace.android.feature.ExploreMode
import com.marketplace.android.ui.DayfApp
import com.marketplace.android.ui.DayfTheme
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val repository: MarketplaceRepository by lazy { MarketplaceRepository(applicationContext) }
    private val viewModel: CommunityViewModel by viewModels {
        CommunityViewModel.Factory(repository)
    }
    private lateinit var authorizationService: AuthorizationService
    private lateinit var credentialManager: CredentialManager
    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>
    private lateinit var silentAuthorizationLauncher: ActivityResultLauncher<Intent>
    private lateinit var endSessionLauncher: ActivityResultLauncher<Intent>
    private lateinit var postPhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authorizationService = AuthorizationService(this)
        credentialManager = CredentialManager.create(this)
        authorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleAuthorizationResult(result, silent = false)
        }
        silentAuthorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleAuthorizationResult(result, silent = true)
        }
        endSessionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleEndSessionResult(result)
        }
        postPhotoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5)
        ) { selectedUris ->
            viewModel.addPostPhotos(selectedUris)
        }

        // API access tokens are intentionally memory-only. Do not silently open an
        // external user-agent at launch; show the native sign-in UI and let the user
        // choose a passkey or the explicitly labelled OAuth/PKCE fallback.
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.silentReauthRequired) {
                if (state.silentReauthRequired) {
                    val nativePasskeySession = state.nativePasskeySession
                    viewModel.consumeSilentReauthRequest()
                    if (nativePasskeySession) {
                        // A native passkey grant creates no browser session and no ID token.
                        // Reauthenticate through Credential Manager rather than unexpectedly
                        // reopening the system browser when the 15-minute access token expires.
                        beginPasskeySignIn()
                    } else {
                        beginSignIn(silent = true)
                    }
                }
            }
            DayfTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DayfApp(
                        state = state,
                        onSignIn = { beginSignIn(silent = false) },
                        onPasskeySignIn = ::beginPasskeySignIn,
                        passkeysAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                        onRegisterPasskey = ::beginRegisterPasskey,
                        onRegisterAccount = viewModel::registerAccount,
                        onResendVerification = viewModel::resendVerificationEmail,
                        onRefresh = viewModel::refresh,
                        onSelectTab = viewModel::selectTab,
                        onLocationQuery = viewModel::setLocationQuery,
                        onJoinNeighborhood = viewModel::joinNeighborhood,
                        onBeginComposer = viewModel::beginComposer,
                        onCloseComposer = viewModel::closeComposer,
                        onPostCategory = viewModel::setPostCategory,
                        onPostTitle = viewModel::setPostTitle,
                        onPostBody = viewModel::setPostBody,
                        onPickPostPhotos = ::pickPostPhotos,
                        onRemovePostPhoto = viewModel::removePostPhoto,
                        onSkipPendingPhotoUpload = viewModel::skipPendingPhotoUpload,
                        onPublishPost = viewModel::publishPost,
                        onMarketCategory = viewModel::setMarketCategory,
                        onMarketCondition = viewModel::setMarketCondition,
                        onMarketTitle = viewModel::setMarketTitle,
                        onMarketPrice = viewModel::setMarketPrice,
                        onMarketCurrency = viewModel::setMarketCurrency,
                        onMarketPickup = viewModel::setMarketPickup,
                        onPublishMarket = viewModel::publishMarketItem,
                        onEventCategory = viewModel::setEventCategory,
                        onEventTitle = viewModel::setEventTitle,
                        onEventDescription = viewModel::setEventDescription,
                        onEventStartsAt = viewModel::setEventStartsAt,
                        onEventLocationLabel = viewModel::setEventLocationLabel,
                        onEventOrganizerLabel = viewModel::setEventOrganizerLabel,
                        onEventRegistration = viewModel::setEventRegistration,
                        onEventCapacity = viewModel::setEventCapacity,
                        onPublishEvent = viewModel::publishEvent,
                        onPollQuestion = viewModel::setPollQuestion,
                        onPollAuthorLabel = viewModel::setPollAuthorLabel,
                        onPollOption = viewModel::setPollOption,
                        onAddPollOption = viewModel::addPollOption,
                        onRemovePollOption = viewModel::removePollOption,
                        onPublishPoll = viewModel::publishPoll,
                        onWithdrawMarketItem = viewModel::withdrawMarketItem,
                        onSearchQuery = viewModel::updateSearchQuery,
                        onSearch = viewModel::searchPosts,
                        onLoadMoreSearchResults = viewModel::loadMoreSearchResults,
                        onExploreMode = viewModel::selectExploreMode,
                        onRsvpEvent = viewModel::toggleEventRsvp,
                        onToggleGroup = viewModel::toggleGroupMembership,
                        onVotePoll = viewModel::castPollVote,
                        onWithdrawPollVote = viewModel::withdrawPollVote,
                        onLoadMore = viewModel::loadMorePosts,
                        onReact = viewModel::toggleReaction,
                        onOpenComments = viewModel::openComments,
                        onUpdateLostFoundState = viewModel::updateLostFoundState,
                        onReportPost = viewModel::reportPost,
                        onCloseComments = viewModel::closeComments,
                        onCommentDraft = viewModel::setCommentDraft,
                        onSendComment = viewModel::sendComment,
                        onReportComment = viewModel::reportComment,
                        onMarkRead = viewModel::markNotificationRead,
                        onRequestVerification = viewModel::requestVerification,
                        onSignOutOnDevice = ::beginSignOut,
                        onDismissNotice = viewModel::dismissNotice,
                        onPropertyQuery = viewModel::updatePropertyQuery,
                        onPropertyPurpose = viewModel::setPropertyPurpose,
                        onPropertyType = viewModel::setPropertyType,
                        onSearchProperties = viewModel::loadPropertyListings,
                        onDirectoryQuery = viewModel::updateDirectoryQuery,
                        onDirectoryMinRating = viewModel::setDirectoryMinRating,
                        onSearchDirectory = viewModel::loadDirectoryListings,
                        onOpenNeighborhoodMarket = viewModel::openNeighborhoodMarket
                    )
                }
            }
        }
    }

    private fun pickPostPhotos() {
        if (viewModel.uiState.value.postPhotoUris.size >= 5) return
        postPhotoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun handleAuthorizationResult(result: ActivityResult, silent: Boolean) {
        val data = result.data ?: Intent()
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        when {
            response != null -> authorizationService.performTokenRequest(
                response.createTokenExchangeRequest()
            ) { tokenResponse, tokenException ->
                runOnUiThread {
                    val token = tokenResponse?.accessToken
                    if (token.isNullOrBlank()) {
                        val message = tokenException?.errorDescription ?: "تعذر إكمال تسجيل الدخول."
                        if (silent) viewModel.sessionRestorationFailed(message)
                        else viewModel.authenticationFailed(message)
                    } else {
                        viewModel.authenticated(token, tokenResponse?.idToken)
                    }
                }
            }
            exception != null && silent -> {
                val message = when (exception.error) {
                    "login_required" ->
                        "لا توجد جلسة نشطة. سجّل الدخول للمتابعة."
                    "consent_required", "interaction_required" ->
                        "تحتاج الجلسة إلى تسجيل دخول تفاعلي. اضغط تسجيل الدخول للمتابعة."
                    else -> exception.errorDescription ?: "تعذر استعادة جلسة الدخول تلقائيًا."
                }
                viewModel.sessionRestorationFailed(message)
            }
            exception != null -> viewModel.authenticationFailed(
                exception.errorDescription ?: "أُلغي تسجيل الدخول أو تعذر إكماله."
            )
            silent -> viewModel.sessionRestorationFailed()
            else -> viewModel.authenticationFailed("لم يكتمل تسجيل الدخول.")
        }
    }

    private fun handleEndSessionResult(result: ActivityResult) {
        val data = result.data ?: Intent()
        val response = EndSessionResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        when {
            response != null -> viewModel.completeSignOut()
            exception != null -> viewModel.completeSignOut(
                "سُجّل الخروج من هذا الجهاز، لكن لم يؤكّد مزوّد الهوية إنهاء الجلسة. تحقق من الاتصال ثم أعد المحاولة."
            )
            else -> viewModel.completeSignOut(
                "سُجّل الخروج من هذا الجهاز، لكن لم يصل تأكيد إنهاء جلسة الخادم."
            )
        }
    }


    private fun beginPasskeySignIn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            viewModel.passkeySignInFailed("مفاتيح المرور تتطلب Android 9 أو أحدث.");
            return
        }
        if (BuildConfig.OIDC_CLIENT_ID.isBlank()) {
            viewModel.passkeySignInFailed("لم يُضبط معرّف عميل Android العام في هذا الإصدار.");
            return
        }

        viewModel.passkeySignInStarted()
        lifecycleScope.launch {
            try {
                val ceremony = repository.createPasskeyAssertionOptions()
                val request = GetCredentialRequest(
                    listOf(GetPublicKeyCredentialOption(ceremony.optionsJson, null))
                )
                val credentialResponse = credentialManager.getCredential(this@MainActivity, request)
                val credential = credentialResponse.credential as? PublicKeyCredential
                    ?: throw IllegalStateException("لم يُرجع مدير بيانات الاعتماد مفتاح مرور صالحًا.")
                val accessToken = repository.signInWithPasskey(
                    ceremony.challengeId,
                    credential.authenticationResponseJson
                )
                // This OAuth extension issues an API access token only (no ID or refresh token).
                // Keep it in memory through the existing ViewModel/repository boundary.
                viewModel.authenticated(accessToken, nativePasskey = true)
            } catch (cancelled: GetCredentialCancellationException) {
                viewModel.passkeySignInFailed("أُلغيت عملية تسجيل الدخول بمفتاح المرور.")
            } catch (missing: NoCredentialException) {
                viewModel.passkeySignInFailed(
                    "لا يوجد مفتاح مرور متاح لهذا الحساب على الجهاز. سجّل الدخول بالطريقة التقليدية، " +
                            "ثم أضف مفتاح مرور من صفحة حسابك."
                )
            } catch (failure: GetCredentialException) {
                viewModel.passkeySignInFailed(
                    "تعذر استخدام مفتاح المرور. جرّب الدخول بالطريقة التقليدية أو أعد المحاولة لاحقًا."
                )
            } catch (cancelled: CancellationException) {
                // Coroutine cancellation is not a sign-in failure: rethrow so the
                // cancelled scope ends quietly instead of clearing the session
                // (kotlinx.coroutines guidance for catching Exception broadly).
                throw cancelled
            } catch (failure: Exception) {
                viewModel.passkeySignInFailed(
                    passkeyBackendFailureMessage(
                        failure,
                        disabledMessage = "مفاتيح المرور غير مفعّلة على الخادم بعد. استخدم الدخول التقليدي مؤقتًا.",
                        retryMessage = "تعذر إكمال تسجيل الدخول بمفتاح المرور. جرّب الدخول التقليدي أو أعد المحاولة لاحقًا."
                    )
                )
            }
        }
    }

    private fun beginRegisterPasskey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            viewModel.passkeyRegistrationFailed("مفاتيح المرور تتطلب Android 9 أو أحدث.");
            return
        }

        viewModel.passkeyRegistrationStarted()
        lifecycleScope.launch {
            try {
                val ceremony = repository.createPasskeyRegistrationOptions()
                val response = credentialManager.createCredential(
                    this@MainActivity,
                    CreatePublicKeyCredentialRequest(ceremony.optionsJson)
                ) as? CreatePublicKeyCredentialResponse
                    ?: throw IllegalStateException("لم يُرجع مدير بيانات الاعتماد نتيجة تسجيل مفتاح مرور.")
                // The credential now exists on the device; complete the server-side
                // registration even if the activity's scope is cancelled mid-flight,
                // otherwise the device keeps an orphaned passkey the server never saw.
                withContext(NonCancellable) {
                    repository.registerPasskey(
                        challengeId = ceremony.challengeId,
                        credentialJson = response.registrationResponseJson
                    )
                }
                viewModel.passkeyRegistrationCompleted()
            } catch (failure: CreateCredentialException) {
                viewModel.passkeyRegistrationFailed(
                    "تعذر إعداد مفتاح المرور. تأكد من إعدادات بيانات الاعتماد ثم أعد المحاولة."
                )
            } catch (cancelled: CancellationException) {
                // Cancellation is not a registration failure — rethrow (the device-side
                // creation, when it happened, was completed by the NonCancellable block).
                throw cancelled
            } catch (failure: Exception) {
                viewModel.passkeyRegistrationFailed(
                    passkeyBackendFailureMessage(
                        failure,
                        disabledMessage = "مفاتيح المرور غير مفعّلة على الخادم بعد. لم يُحفظ أي مفتاح مرور.",
                        retryMessage = "تعذر إكمال إعداد مفتاح المرور. تحقق من الاتصال وحاول مجددًا."
                    )
                )
            }
        }
    }

    private fun passkeyBackendFailureMessage(
        failure: Exception,
        disabledMessage: String,
        retryMessage: String
    ): String {
        val httpFailure = failure as? retrofit2.HttpException ?: return retryMessage
        if (httpFailure.code() != 503) return retryMessage

        // Spring's ProblemDetail includes the ResponseStatusException reason in its detail.
        // A 503 can also mean the challenge/credential store is unavailable, so status alone
        // is not enough to tell users that the passkey feature is disabled.
        val problem = runCatching {
            httpFailure.response()?.errorBody()?.string().orEmpty()
        }.getOrDefault("")
        return if (problem.contains("not enabled", ignoreCase = true)) disabledMessage else retryMessage
    }

    private fun beginSignIn(silent: Boolean = false) {
        if (BuildConfig.OIDC_CLIENT_ID.isBlank()) {
            val message = "لم يُضبط معرّف عميل Android العام بعد. اضبط OAUTH_PUBLIC_CLIENT_ID على الخادم وابنِ التطبيق باستخدام -PoidcClientId."
            if (silent) viewModel.sessionRestorationFailed()
            else viewModel.authenticationFailed(message)
            return
        }

        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { configuration, exception ->
            runOnUiThread {
                if (configuration == null) {
                    val message = exception?.errorDescription ?: "تعذر الوصول إلى إعدادات تسجيل الدخول."
                    if (silent) viewModel.sessionRestorationFailed(message)
                    else viewModel.authenticationFailed(message)
                    return@runOnUiThread
                }

                val requestBuilder = AuthorizationRequest.Builder(
                    configuration,
                    BuildConfig.OIDC_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    Uri.parse(BuildConfig.OIDC_REDIRECT_URI)
                )
                    .setScope("openid profile")

                if (silent) requestBuilder.setPrompt("none")

                val requestIntent = authorizationService.getAuthorizationRequestIntent(requestBuilder.build())
                if (silent) silentAuthorizationLauncher.launch(requestIntent)
                else authorizationLauncher.launch(requestIntent)
            }
        }
    }

    private fun beginSignOut() {
        val idTokenHint = viewModel.signOutOnDevice()

        // Passkey login doesn't mint an OIDC ID token or a browser session. Avoid
        // opening a browser merely to report local sign-out for that flow.
        if (idTokenHint.isNullOrBlank()) {
            viewModel.completeLocalSignOut()
            return
        }

        if (BuildConfig.OIDC_CLIENT_ID.isBlank()) {
            viewModel.completeSignOut(
                "سُجّل الخروج من هذا الجهاز، لكن إعداد إنهاء جلسة الخادم غير مكتمل."
            )
            return
        }

        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { configuration, exception ->
            runOnUiThread {
                if (configuration == null) {
                    viewModel.completeSignOut(
                        "سُجّل الخروج من هذا الجهاز، لكن تعذر الوصول إلى مزوّد الهوية لإنهاء الجلسة."
                    )
                    return@runOnUiThread
                }

                // The current public-client registration has no post-logout redirect URI.
                // Do not send an unregistered URI: request RP-initiated logout with the
                // ID-token hint/client id and report honestly if the provider gives no callback.
                val requestBuilder = EndSessionRequest.Builder(configuration)
                    .setAdditionalParameters(mapOf("client_id" to BuildConfig.OIDC_CLIENT_ID))
                if (!idTokenHint.isNullOrBlank()) requestBuilder.setIdTokenHint(idTokenHint)

                try {
                    val request = requestBuilder.build()
                    endSessionLauncher.launch(authorizationService.getEndSessionRequestIntent(request))
                } catch (error: Exception) {
                    viewModel.completeSignOut(
                        "سُجّل الخروج من هذا الجهاز، لكن تعذر بدء إنهاء جلسة الخادم."
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        authorizationService.dispose()
        super.onDestroy()
    }
}
