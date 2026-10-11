package com.marketplace.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marketplace.android.feature.CommunityViewModel
import com.marketplace.android.feature.ExploreMode
import com.marketplace.android.ui.DayfApp
import com.marketplace.android.ui.DayfTheme
import java.security.MessageDigest
import java.security.SecureRandom
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

class MainActivity : ComponentActivity() {
    private val viewModel: CommunityViewModel by viewModels { CommunityViewModel.Factory() }
    private lateinit var authorizationService: AuthorizationService
    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authorizationService = AuthorizationService(this)
        authorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
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
                            viewModel.authenticationFailed(
                                tokenException?.errorDescription ?: "تعذر إكمال تسجيل الدخول."
                            )
                        } else {
                            viewModel.authenticated(token)
                        }
                    }
                }
                exception != null -> viewModel.authenticationFailed(
                    exception.errorDescription ?: "أُلغي تسجيل الدخول أو تعذر إكماله."
                )
                else -> viewModel.authenticationFailed("لم يكتمل تسجيل الدخول.")
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DayfTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DayfApp(
                        state = state,
                        onSignIn = ::beginSignIn,
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
                        onExploreMode = viewModel::selectExploreMode,
                        onRsvpEvent = viewModel::toggleEventRsvp,
                        onToggleGroup = viewModel::toggleGroupMembership,
                        onVotePoll = viewModel::castPollVote,
                        onWithdrawPollVote = viewModel::withdrawPollVote,
                        onLoadMore = viewModel::loadMorePosts,
                        onReact = viewModel::toggleReaction,
                        onOpenComments = viewModel::openComments,
                        onCloseComments = viewModel::closeComments,
                        onCommentDraft = viewModel::setCommentDraft,
                        onSendComment = viewModel::sendComment,
                        onMarkRead = viewModel::markNotificationRead,
                        onRequestVerification = viewModel::requestVerification,
                        onSignOutOnDevice = viewModel::signOutOnDevice,
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

    private fun beginSignIn() {
        if (BuildConfig.OIDC_CLIENT_ID.isBlank()) {
            viewModel.authenticationFailed(
                "لم يُضبط معرّف عميل Android العام بعد. اضبط OAUTH_PUBLIC_CLIENT_ID على الخادم وابنِ التطبيق باستخدام -PoidcClientId."
            )
            return
        }
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { configuration, exception ->
            runOnUiThread {
                if (configuration == null) {
                    viewModel.authenticationFailed(
                        exception?.errorDescription ?: "تعذر الوصول إلى إعدادات تسجيل الدخول."
                    )
                    return@runOnUiThread
                }
                val verifier = createCodeVerifier()
                val challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                val request = AuthorizationRequest.Builder(
                    configuration,
                    BuildConfig.OIDC_CLIENT_ID,
                    ResponseTypeValues.CODE,
                    Uri.parse(BuildConfig.OIDC_REDIRECT_URI)
                )
                    .setScope("openid profile")
                    .setCodeVerifier(verifier, challenge, "S256")
                    .build()
                authorizationLauncher.launch(authorizationService.getAuthorizationRequestIntent(request))
            }
        }
    }

    private fun createCodeVerifier(): String {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(
            random,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    override fun onDestroy() {
        authorizationService.dispose()
        super.onDestroy()
    }
}
