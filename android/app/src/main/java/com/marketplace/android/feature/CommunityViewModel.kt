package com.marketplace.android.feature

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marketplace.android.core.network.CommentDto
import com.marketplace.android.core.network.DiscoveryCardDto
import com.marketplace.android.core.network.DiscoveryRowDto
import com.marketplace.android.core.network.CreateMarketItemRequest
import com.marketplace.android.core.network.CreatePostRequest
import com.marketplace.android.core.network.CreateEventRequest
import com.marketplace.android.core.network.CreatePollRequest
import com.marketplace.android.core.network.EventDto
import com.marketplace.android.core.network.GroupDto
import com.marketplace.android.core.network.PollDto
import com.marketplace.android.core.network.GeoNodeDto
import com.marketplace.android.core.network.MarketItemDto
import com.marketplace.android.core.network.MembershipDto
import com.marketplace.android.core.network.NotificationDto
import com.marketplace.android.core.network.UrgentAlertDto
import com.marketplace.android.core.network.PostDto
import com.marketplace.android.core.network.ListingSummaryDto
import com.marketplace.android.data.MarketplaceRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class MainTab { HOME, MARKET, PROPERTY, DIRECTORY, EXPLORE, NOTIFICATIONS, PROFILE }
enum class ExploreMode { POSTS, EVENTS, GROUPS, POLLS, MARKET, OFFICIAL_ALERTS, DISCOVERY_ROW }
enum class ComposerKind { POST, MARKET, EVENT, POLL }

data class AppUiState(
    val authenticated: Boolean = false,
    val sessionRestoring: Boolean = false,
    val sessionRestoreAttempted: Boolean = false,
    val silentReauthRequired: Boolean = false,
    val nativePasskeySession: Boolean = false,
    val loadingAccount: Boolean = false,
    val authError: String? = null,
    val passkeySignInBusy: Boolean = false,
    val passkeyRegistrationBusy: Boolean = false,
    val membership: MembershipDto? = null,
    val scopeName: String? = null,
    val membershipRequired: Boolean = false,
    val locationQuery: String = "",
    val locationResults: List<GeoNodeDto> = emptyList(),
    val locationBusy: Boolean = false,
    val locationError: String? = null,
    val tab: MainTab = MainTab.HOME,
    val exploreMode: ExploreMode = ExploreMode.POSTS,
    val events: List<EventDto> = emptyList(),
    val eventsBusy: Boolean = false,
    val eventsError: String? = null,
    val groups: List<GroupDto> = emptyList(),
    val groupsBusy: Boolean = false,
    val groupsError: String? = null,
    val polls: List<PollDto> = emptyList(),
    val pollsBusy: Boolean = false,
    val pollsError: String? = null,
    val eventCategory: String = "SOCIAL",
    val eventTitle: String = "",
    val eventDescription: String = "",
    val eventStartsAt: String = "",
    val eventLocationLabel: String = "",
    val eventOrganizerLabel: String = "",
    val eventRegistration: String = "OPEN",
    val eventCapacity: String = "",
    val pollQuestion: String = "",
    val pollAuthorLabel: String = "",
    val pollOptions: List<String> = listOf("", ""),
    val pollActionIds: Set<String> = emptySet(),
    val posts: List<PostDto> = emptyList(),
    val feedBusy: Boolean = false,
    val feedError: String? = null,
    val feedPage: Int = 0,
    val feedLast: Boolean = true,
    val searchQuery: String = "",
    val searchResults: List<PostDto> = emptyList(),
    val searchResultsQuery: String = "",
    val searchPage: Int = 0,
    val searchLast: Boolean = true,
    val searchBusy: Boolean = false,
    val searchError: String? = null,
    val marketItems: List<MarketItemDto> = emptyList(),
    val marketBusy: Boolean = false,
    val marketError: String? = null,
    val notifications: List<NotificationDto> = emptyList(),
    val notificationsBusy: Boolean = false,
    val notificationsError: String? = null,
    val urgentAlerts: List<UrgentAlertDto> = emptyList(),
    val urgentAlertsScopeId: String? = null,
    val urgentAlertsBusy: Boolean = false,
    val urgentAlertsError: String? = null,
    val discoveryRows: List<DiscoveryRowDto> = emptyList(),
    val discoveryScopeId: String? = null,
    val discoveryBusy: Boolean = false,
    val discoveryError: String? = null,
    val selectedDiscoveryRow: String? = null,
    val discoveryRowCards: List<DiscoveryCardDto> = emptyList(),
    val discoveryRowTotal: Long = 0,
    val discoveryRowPage: Int = -1,
    val discoveryRowLast: Boolean = true,
    val discoveryRowBusy: Boolean = false,
    val discoveryRowError: String? = null,
    val discoveryPreviewCard: DiscoveryCardDto? = null,
    val propertyQuery: String = "",
    val propertyPurpose: String = "RENT",
    val propertyType: String = "APARTMENT",
    val propertyListings: List<ListingSummaryDto> = emptyList(),
    val propertyBusy: Boolean = false,
    val propertyError: String? = null,
    val directoryQuery: String = "",
    val directoryMinRating: Double? = null,
    val directoryListings: List<ListingSummaryDto> = emptyList(),
    val directoryBusy: Boolean = false,
    val directoryError: String? = null,
    val registrationBusy: Boolean = false,
    val registrationEmail: String = "",
    val verificationResendBusy: Boolean = false,
    val authSuccess: String? = null,
    val composer: ComposerKind? = null,
    val postCategory: String = "GENERAL",
    val postTitle: String = "",
    val postBody: String = "",
    val postPhotoUris: List<Uri> = emptyList(),
    val pendingPhotoPostId: String? = null,
    val uploadProgress: String? = null,
    val marketCategory: String = "FREE",
    val marketCondition: String = "GOOD",
    val marketTitle: String = "",
    val marketPrice: String = "",
    val marketCurrency: String = "SYP",
    val marketPickup: String = "",
    val submitBusy: Boolean = false,
    val profileBusy: Boolean = false,
    val commentsPost: PostDto? = null,
    val comments: List<CommentDto> = emptyList(),
    val commentsBusy: Boolean = false,
    val commentDraft: String = "",
    val commentBusy: Boolean = false,
    val reactingPostIds: Set<String> = emptySet(),
    val lostFoundActionIds: Set<String> = emptySet(),
    val notice: String? = null,
    val reportingContentKeys: Set<String> = emptySet(),
    val reportedContentKeys: Set<String> = emptySet()
)


/**
 * Enforce a Unicode-code-point limit without splitting UTF-16 surrogate pairs.
 * The posts-search API contract accepts at most 200 Unicode code points.
 */
internal fun takeCodePoints(value: String, maximum: Int): String {
    require(maximum >= 0) { "maximum must be non-negative" }
    val count = value.codePointCount(0, value.length)
    return if (count <= maximum) value
    else value.substring(0, value.offsetByCodePoints(0, maximum))
}

/**
 * Convert a user-entered major-unit amount into integer minor units exactly
 * within the API's Int range. BigDecimal.toInt() silently overflows, which
 * can otherwise turn a very large price into a small positive price.
 */
internal fun parsePriceCents(raw: String): Int? =
    raw.trim().replace(',', '.')
        .toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.let { scaled ->
            try {
                scaled.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

/**
 * Adding an RSVP requires an explicitly active event, but freeing an existing
 * seat remains allowed when the event is later cancelled/postponed or its
 * status is missing. This mirrors the backend's separate RSVP/un-RSVP gates.
 */
internal fun canChangeEventRsvp(event: EventDto): Boolean =
    event.status.equals("ACTIVE", ignoreCase = true) || event.rsvpedByMe

/**
 * Merge only the lifecycle field returned by the command endpoint.
 * Its write projection intentionally does not contain the feed's grouped
 * media/reaction aggregates, so replacing a feed row with it would lose
 * presentation data until the next full feed read.
 */
internal fun mergeLostFoundLifecycleState(current: PostDto, serverUpdate: PostDto): PostDto =
    if (current.id == serverUpdate.id) {
        current.copy(lostFoundState = serverUpdate.lostFoundState)
    } else {
        current
    }

class CommunityViewModel(
    private val repository: MarketplaceRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    private var idTokenHint: String? = null
    private var silentReauthInFlight: Boolean = false
    private var stateBeforeSessionRecovery: AppUiState? = null
    val uiState = _uiState.asStateFlow()
    private var locationSearchJob: Job? = null
    private var urgentAlertsJob: Job? = null
    private var searchJob: Job? = null
    private var searchRequestId: Long = 0

    fun authenticationFailed(message: String) {
        _uiState.update {
            it.copy(
                authError = message,
                authSuccess = null,
                loadingAccount = false,
                passkeySignInBusy = false
            )
        }
    }

    fun passkeySignInStarted() {
        _uiState.update { it.copy(passkeySignInBusy = true, authError = null, authSuccess = null) }
    }

    fun passkeySignInFailed(message: String) {
        repository.clearAccessToken()
        silentReauthInFlight = false
        stateBeforeSessionRecovery = null
        _uiState.update {
            it.copy(
                authenticated = false,
                sessionRestoring = false,
                sessionRestoreAttempted = true,
                silentReauthRequired = false,
                nativePasskeySession = false,
                passkeySignInBusy = false,
                authError = message,
                authSuccess = null,
                loadingAccount = false
            )
        }
    }

    fun passkeyRegistrationStarted() {
        _uiState.update { it.copy(passkeyRegistrationBusy = true, notice = null) }
    }

    fun passkeyRegistrationCompleted() {
        _uiState.update {
            it.copy(
                passkeyRegistrationBusy = false,
                notice = "تم إعداد مفتاح المرور. يمكنك استخدامه لتسجيل الدخول من هذا الجهاز."
            )
        }
    }

    fun passkeyRegistrationFailed(message: String) {
        _uiState.update { it.copy(passkeyRegistrationBusy = false, notice = message) }
    }

    fun authenticated(
        accessToken: String,
        idToken: String? = null,
        nativePasskey: Boolean = false
    ) {
        silentReauthInFlight = false
        idTokenHint = idToken
        repository.setAccessToken(accessToken)
        val savedState = stateBeforeSessionRecovery
        stateBeforeSessionRecovery = null
        _uiState.value = (savedState ?: AppUiState()).copy(
            authenticated = true,
            sessionRestoring = false,
            sessionRestoreAttempted = true,
            silentReauthRequired = false,
            nativePasskeySession = nativePasskey,
            loadingAccount = true,
            authError = null,
            authSuccess = null,
            passkeySignInBusy = false,
            passkeyRegistrationBusy = false,
            submitBusy = false,
            uploadProgress = null,
            notice = if (savedState?.pendingPhotoPostId != null) {
                "استُعيد تسجيل الدخول. أكمل رفع الصور المتبقية للمنشور نفسه."
            } else {
                savedState?.notice
            }
        )
        loadAccount()
    }

    fun requestSessionRestoreIfNeeded() {
        _uiState.update {
            if (it.authenticated || it.sessionRestoreAttempted || it.sessionRestoring) {
                it
            } else {
                silentReauthInFlight = true
                it.copy(
                    sessionRestoring = true,
                    sessionRestoreAttempted = true,
                    silentReauthRequired = true
                )
            }
        }
    }

    fun consumeSilentReauthRequest() {
        _uiState.update { it.copy(silentReauthRequired = false) }
    }

    fun sessionRestorationFailed(message: String? = null) {
        _uiState.update {
            it.copy(
                sessionRestoring = false,
                silentReauthRequired = false,
                loadingAccount = false,
                authError = message ?: it.authError
            )
        }
    }

    fun signOutOnDevice(): String? {
        repository.clearAccessToken()
        silentReauthInFlight = true
        stateBeforeSessionRecovery = null
        val idToken = idTokenHint
        idTokenHint = null
        _uiState.value = AppUiState(sessionRestoreAttempted = true)
        return idToken
    }

    fun completeLocalSignOut() {
        idTokenHint = null
        silentReauthInFlight = true
        stateBeforeSessionRecovery = null
        _uiState.value = AppUiState(
            sessionRestoreAttempted = true,
            authSuccess = "تم تسجيل الخروج من هذا الجهاز. تنتهي صلاحية رمز الوصول تلقائيًا خلال 15 دقيقة."
        )
    }

    fun completeSignOut(errorMessage: String? = null) {
        idTokenHint = null
        silentReauthInFlight = true
        stateBeforeSessionRecovery = null
        _uiState.update {
            it.copy(
                authenticated = false,
                loadingAccount = false,
                sessionRestoring = false,
                sessionRestoreAttempted = true,
                silentReauthRequired = false,
                authError = errorMessage,
                authSuccess = if (errorMessage == null) "تم تسجيل الخروج وإنهاء جلسة الدخول." else null,
                urgentAlerts = emptyList(),
                urgentAlertsScopeId = null,
                urgentAlertsBusy = false,
                urgentAlertsError = null,
                reportingContentKeys = emptySet(),
                reportedContentKeys = emptySet(),
                discoveryRows = emptyList(),
                discoveryScopeId = null,
                discoveryBusy = false,
                discoveryError = null,
                selectedDiscoveryRow = null,
                discoveryRowCards = emptyList(),
                discoveryRowTotal = 0,
                discoveryRowPage = -1,
                discoveryRowLast = true,
                discoveryRowBusy = false,
                discoveryRowError = null,
                discoveryPreviewCard = null,
            )
        }
    }

    fun dismissNotice() { _uiState.update { it.copy(notice = null) } }

    private fun loadAccount() {
        viewModelScope.launch {
            try {
                val membership = repository.getMyNeighborhood()
                val locationName = try {
                    findNode(repository.getGeoTree(), membership.locationId)?.nameAr
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                _uiState.update {
                    val discoveryScopeChanged =
                        it.membership?.locationId != membership.locationId ||
                            it.discoveryScopeId != membership.locationId
                    it.copy(
                        authenticated = true, loadingAccount = false, membership = membership,
                        scopeName = locationName, membershipRequired = false, authError = null, notice = null,
                        discoveryRows = if (discoveryScopeChanged) emptyList() else it.discoveryRows,
                        discoveryScopeId = if (discoveryScopeChanged) null else it.discoveryScopeId,
                        discoveryBusy = if (discoveryScopeChanged) false else it.discoveryBusy,
                        discoveryError = if (discoveryScopeChanged) null else it.discoveryError,
                        selectedDiscoveryRow = if (discoveryScopeChanged) null else it.selectedDiscoveryRow,
                        discoveryRowCards = if (discoveryScopeChanged) emptyList() else it.discoveryRowCards,
                        discoveryRowTotal = if (discoveryScopeChanged) 0 else it.discoveryRowTotal,
                        discoveryRowPage = if (discoveryScopeChanged) -1 else it.discoveryRowPage,
                        discoveryRowLast = if (discoveryScopeChanged) true else it.discoveryRowLast,
                        discoveryRowBusy = if (discoveryScopeChanged) false else it.discoveryRowBusy,
                        discoveryRowError = if (discoveryScopeChanged) null else it.discoveryRowError,
                        discoveryPreviewCard = if (discoveryScopeChanged) null else it.discoveryPreviewCard
                    )
                }
                loadFeed()
                loadUrgentAlerts()
                loadDiscoveryHome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 404) {
                    _uiState.update {
                        it.copy(
                            authenticated = true,
                            loadingAccount = false,
                            membership = null,
                            membershipRequired = true,
                            authError = null,
                            urgentAlerts = emptyList(),
                            urgentAlertsScopeId = null,
                            urgentAlertsBusy = false,
                            urgentAlertsError = null,
                            reportingContentKeys = emptySet(),
                            discoveryRows = emptyList(),
                            discoveryScopeId = null,
                            discoveryBusy = false,
                            discoveryError = null,
                            selectedDiscoveryRow = null,
                            discoveryRowCards = emptyList(),
                            discoveryRowTotal = 0,
                            discoveryRowPage = -1,
                            discoveryRowLast = true,
                            discoveryRowBusy = false,
                            discoveryRowError = null,
                            discoveryPreviewCard = null,
                        )
                    }
                } else {
                    val message = failureMessage(error)
                    _uiState.update { it.copy(loadingAccount = false, notice = message) }
                }
            }
        }
    }

    fun setLocationQuery(query: String) {
        _uiState.update { it.copy(locationQuery = query, locationError = null) }
        locationSearchJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.update { it.copy(locationResults = emptyList(), locationBusy = false) }
            return
        }
        locationSearchJob = viewModelScope.launch {
            _uiState.update { it.copy(locationBusy = true) }
            try {
                val results = repository.suggestLocations(query.trim()).filter { it.level == 3 }
                _uiState.update { it.copy(locationResults = results, locationBusy = false, locationError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(locationBusy = false, locationError = failureMessage(error)) }
            }
        }
    }

    fun joinNeighborhood(node: GeoNodeDto) {
        if (node.level != 3) {
            _uiState.update { it.copy(locationError = "اختر حيًا، وليس بلدًا أو مدينة.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(locationBusy = true, locationError = null) }
            try {
                val membership = repository.joinNeighborhood(node.id)
                _uiState.update {
                    it.copy(
                        membership = membership, scopeName = node.nameAr, membershipRequired = false,
                        locationBusy = false, locationResults = emptyList(), locationQuery = "",
                        urgentAlerts = emptyList(), urgentAlertsScopeId = node.id,
                        urgentAlertsBusy = true, urgentAlertsError = null,
                        discoveryRows = emptyList(), discoveryScopeId = node.id,
                        discoveryBusy = true, discoveryError = null,
                        selectedDiscoveryRow = null, discoveryRowCards = emptyList(),
                        discoveryRowTotal = 0, discoveryRowPage = -1, discoveryRowLast = true,
                        discoveryPreviewCard = null,
                        tab = MainTab.HOME, notice = "انضممت إلى ${node.nameAr}."
                    )
                }
                loadFeed()
                loadMarket()
                loadUrgentAlerts()
                loadDiscoveryHome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(locationBusy = false, locationError = failureMessage(error)) }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(tab = tab, notice = null) }
        when (tab) {
            MainTab.HOME -> {
                loadFeed()
                loadUrgentAlerts()
                loadDiscoveryHome()
            }
            MainTab.MARKET -> Unit // Public product catalog/cart is not exposed by the current API.
            MainTab.PROPERTY -> loadPropertyListings()
            MainTab.DIRECTORY -> loadDirectoryListings()
            MainTab.EXPLORE -> loadExploreBoard()
            MainTab.NOTIFICATIONS -> loadNotifications()
            MainTab.PROFILE -> Unit
        }
    }

    fun refresh() {
        when (_uiState.value.tab) {
            MainTab.HOME -> {
                loadFeed()
                loadUrgentAlerts()
                loadDiscoveryHome()
            }
            MainTab.MARKET -> Unit
            MainTab.PROPERTY -> loadPropertyListings()
            MainTab.DIRECTORY -> loadDirectoryListings()
            MainTab.EXPLORE -> loadExploreBoard()
            MainTab.NOTIFICATIONS -> loadNotifications()
            MainTab.PROFILE -> loadAccount()
        }
    }

    fun openNeighborhoodMarket() {
        _uiState.update { it.copy(tab = MainTab.EXPLORE, exploreMode = ExploreMode.MARKET, notice = null) }
        loadMarket()
    }

    fun updatePropertyQuery(value: String) {
        _uiState.update { it.copy(propertyQuery = value.take(120), propertyError = null) }
    }

    fun setPropertyPurpose(value: String) {
        if (value !in setOf("RENT", "SALE")) return
        _uiState.update { it.copy(propertyPurpose = value, propertyError = null) }
    }

    fun setPropertyType(value: String) {
        if (value !in setOf("APARTMENT", "VILLA", "LAND", "SHOP", "OFFICE", "GARAGE")) return
        _uiState.update { it.copy(propertyType = value, propertyError = null) }
    }

    fun loadPropertyListings() {
        val current = _uiState.value
        if (!current.authenticated || current.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(propertyBusy = true, propertyError = null) }
            try {
                val page = repository.searchListings(
                    query = current.propertyQuery.trim().ifBlank { null },
                    locationId = current.membership.locationId,
                    purpose = current.propertyPurpose,
                    propertyType = current.propertyType,
                    page = 0,
                    size = 20
                )
                _uiState.update { it.copy(propertyListings = page.content, propertyBusy = false, propertyError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(propertyBusy = false, propertyError = failureMessage(error)) }
            }
        }
    }

    fun updateDirectoryQuery(value: String) {
        _uiState.update { it.copy(directoryQuery = value.take(120), directoryError = null) }
    }

    fun setDirectoryMinRating(value: Double?) {
        if (value != null && value !in 1.0..5.0) return
        _uiState.update { it.copy(directoryMinRating = value, directoryError = null) }
    }

    fun loadDirectoryListings() {
        val current = _uiState.value
        if (!current.authenticated || current.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(directoryBusy = true, directoryError = null) }
            try {
                val page = repository.searchListings(
                    query = current.directoryQuery.trim().ifBlank { null },
                    locationId = current.membership.locationId,
                    minRating = current.directoryMinRating,
                    page = 0,
                    size = 30
                )
                _uiState.update { it.copy(directoryListings = page.content, directoryBusy = false, directoryError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(directoryBusy = false, directoryError = failureMessage(error)) }
            }
        }
    }

    fun registerAccount(displayName: String, email: String, password: String) {
        val name = displayName.trim()
        val normalizedEmail = email.trim()
        if (!normalizedEmail.contains("@") || !normalizedEmail.substringAfter("@").contains(".") ||
            name.isBlank() || password.length !in 8..72) {
            _uiState.update {
                it.copy(authError = "تحقق من الاسم والبريد الإلكتروني وكلمة المرور (8 إلى 72 محرفًا).", authSuccess = null)
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(registrationBusy = true, authError = null, authSuccess = null) }
            try {
                repository.registerAccount(normalizedEmail, password, name)
                _uiState.update {
                    it.copy(
                        registrationBusy = false,
                        registrationEmail = normalizedEmail,
                        authError = null,
                        authSuccess = "تم إنشاء الحساب. تحقّق من بريدك الإلكتروني لتفعيل الحساب قبل تسجيل الدخول."
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is HttpException -> when (error.code()) {
                        400 -> "بيانات التسجيل غير صالحة. تحقق من البريد وكلمة المرور."
                        409 -> "هذا البريد الإلكتروني مسجّل بالفعل."
                        429 -> "محاولات التسجيل كثيرة مؤقتًا. حاول لاحقًا."
                        else -> failureMessage(error)
                    }
                    else -> failureMessage(error)
                }
                _uiState.update { it.copy(registrationBusy = false, authError = message, authSuccess = null) }
            }
        }
    }

    fun resendVerificationEmail() {
        val email = _uiState.value.registrationEmail.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(authError = "أعد إدخال البريد عبر إنشاء الحساب أو أعد المحاولة من جديد.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(verificationResendBusy = true, authError = null) }
            try {
                repository.resendVerificationEmail(email)
                _uiState.update {
                    it.copy(
                        verificationResendBusy = false,
                        authError = null,
                        authSuccess = "قُبل الطلب. إذا كان الحساب بانتظار التحقق، فستصل رسالة إلى البريد المسجّل."
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(verificationResendBusy = false, authError = failureMessage(error))
                }
            }
        }
    }

    fun loadFeed() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(feedBusy = true, feedError = null) }
            try {
                val page = repository.getFeed()
                _uiState.update {
                    it.copy(posts = page.content, feedPage = page.pageNumber, feedLast = page.last, feedBusy = false, feedError = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(feedBusy = false, feedError = failureMessage(error)) }
            }
        }
    }

    fun loadMorePosts() {
        val current = _uiState.value
        if (current.feedBusy || current.feedLast || current.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(feedBusy = true, feedError = null) }
            try {
                val page = repository.getFeed(current.feedPage + 1)
                _uiState.update {
                    it.copy(posts = it.posts + page.content, feedPage = page.pageNumber, feedLast = page.last, feedBusy = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(feedBusy = false, feedError = failureMessage(error)) }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val boundedQuery = takeCodePoints(query, 200)
        if (_uiState.value.searchQuery == boundedQuery) return
        searchRequestId += 1
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchQuery = boundedQuery, searchBusy = false, searchError = null)
        }
    }

    fun searchPosts() {
        val query = _uiState.value.searchQuery.trim()
        if (query.codePointCount(0, query.length) < 2) {
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    searchResultsQuery = "",
                    searchPage = 0,
                    searchLast = true,
                    searchBusy = false,
                    searchError = "اكتب حرفين على الأقل لبدء البحث."
                )
            }
            return
        }

        searchJob?.cancel()
        searchRequestId += 1
        val requestId = searchRequestId
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    searchResultsQuery = query,
                    searchPage = 0,
                    searchLast = true,
                    searchBusy = true,
                    searchError = null
                )
            }
            try {
                val page = repository.searchPosts(query, page = 0)
                _uiState.update { state ->
                    if (requestId != searchRequestId ||
                        state.searchQuery.trim() != query || state.searchResultsQuery != query) {
                        state
                    } else {
                        state.copy(
                            searchResults = page.content,
                            searchPage = page.pageNumber,
                            searchLast = page.last,
                            searchBusy = false,
                            searchError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = failureMessage(error)
                _uiState.update { state ->
                    if (requestId == searchRequestId &&
                        state.searchResultsQuery == query && state.searchQuery.trim() == query) {
                        state.copy(searchBusy = false, searchError = message)
                    } else state
                }
            }
        }
    }

    fun loadMoreSearchResults() {
        val current = _uiState.value
        val query = current.searchResultsQuery
        if (current.searchBusy || current.searchLast || query.isBlank() ||
            query != current.searchQuery.trim()) return
        val requestedPage = current.searchPage + 1

        searchJob?.cancel()
        searchRequestId += 1
        val requestId = searchRequestId
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(searchBusy = true, searchError = null) }
            try {
                val page = repository.searchPosts(query, page = requestedPage)
                _uiState.update { state ->
                    if (requestId != searchRequestId ||
                        state.searchResultsQuery != query || state.searchQuery.trim() != query) {
                        state
                    } else {
                        state.copy(
                            searchResults = (state.searchResults + page.content).distinctBy { it.id },
                            searchPage = page.pageNumber,
                            searchLast = page.last,
                            searchBusy = false,
                            searchError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = failureMessage(error)
                _uiState.update { state ->
                    if (requestId == searchRequestId &&
                        state.searchResultsQuery == query && state.searchQuery.trim() == query) {
                        state.copy(searchBusy = false, searchError = message)
                    } else state
                }
            }
        }
    }

    fun beginComposer(kind: ComposerKind) {
        _uiState.update { current ->
            if (kind == ComposerKind.POST && current.pendingPhotoPostId != null) {
                current.copy(
                    composer = ComposerKind.POST,
                    submitBusy = false,
                    uploadProgress = null,
                    notice = "أكمل رفع الصور المتبقية للمنشور المنشور مسبقًا."
                )
            } else {
                current.copy(
                    composer = kind, postCategory = "GENERAL", postTitle = "", postBody = "",
                    postPhotoUris = if (current.pendingPhotoPostId != null) current.postPhotoUris else emptyList(),
                    uploadProgress = null,
                    marketCategory = "FREE", marketCondition = "GOOD", marketTitle = "",
                    marketPrice = "", marketCurrency = "SYP", marketPickup = "",
                    eventCategory = "SOCIAL", eventTitle = "", eventDescription = "", eventStartsAt = "",
                    eventLocationLabel = "", eventOrganizerLabel = "", eventRegistration = "OPEN",
                    eventCapacity = "", pollQuestion = "", pollAuthorLabel = "", pollOptions = listOf("", ""),
                    notice = null
                )
            }
        }
    }

    fun closeComposer() {
        _uiState.update {
            if (it.submitBusy) it
            else it.copy(
                composer = null,
                submitBusy = false,
                uploadProgress = null,
                postPhotoUris = if (it.pendingPhotoPostId != null) it.postPhotoUris else emptyList()
            )
        }
    }

    fun addPostPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.update { current ->
            val merged = (current.postPhotoUris + uris).distinctBy { it.toString() }
            current.copy(
                postPhotoUris = merged.take(5),
                notice = if (merged.size > 5) "يمكن إرفاق خمس صور كحد أقصى لكل منشور." else current.notice
            )
        }
    }

    fun removePostPhoto(uri: Uri) {
        _uiState.update { it.copy(postPhotoUris = it.postPhotoUris.filterNot { selected -> selected == uri }) }
    }

    fun skipPendingPhotoUpload() {
        _uiState.update {
            if (it.pendingPhotoPostId == null) it
            else it.copy(
                composer = null,
                pendingPhotoPostId = null,
                postPhotoUris = emptyList(),
                uploadProgress = null,
                submitBusy = false,
                notice = "أُبقي المنشور كما نُشر دون الصور التي تعذّر رفعها."
            )
        }
    }

    fun setPostCategory(value: String) { _uiState.update { it.copy(postCategory = value) } }
    fun setPostTitle(value: String) { _uiState.update { it.copy(postTitle = value.take(200)) } }
    fun setPostBody(value: String) { _uiState.update { it.copy(postBody = value.take(2000)) } }
    fun setMarketCategory(value: String) { _uiState.update { it.copy(marketCategory = value) } }
    fun setMarketCondition(value: String) { _uiState.update { it.copy(marketCondition = value) } }
    fun setMarketTitle(value: String) { _uiState.update { it.copy(marketTitle = value.take(200)) } }
    fun setMarketPrice(value: String) { _uiState.update { it.copy(marketPrice = value.take(15)) } }
    fun setMarketCurrency(value: String) { _uiState.update { it.copy(marketCurrency = value.take(3).uppercase()) } }
    fun setMarketPickup(value: String) { _uiState.update { it.copy(marketPickup = value.take(200)) } }
    fun setCommentDraft(value: String) { _uiState.update { it.copy(commentDraft = value.take(2000)) } }

    fun selectExploreMode(mode: ExploreMode) {
        _uiState.update {
            it.copy(
                exploreMode = mode,
                selectedDiscoveryRow = if (mode == ExploreMode.DISCOVERY_ROW) it.selectedDiscoveryRow else null,
                discoveryPreviewCard = null,
                notice = null
            )
        }
        when (mode) {
            ExploreMode.POSTS -> Unit
            ExploreMode.EVENTS -> loadEvents()
            ExploreMode.GROUPS -> loadGroups()
            ExploreMode.POLLS -> loadPolls()
            ExploreMode.MARKET -> loadMarket()
            ExploreMode.OFFICIAL_ALERTS -> loadUrgentAlerts()
            ExploreMode.DISCOVERY_ROW -> loadDiscoveryRow(reset = true)
        }
    }

    private fun loadExploreBoard() {
        when (_uiState.value.exploreMode) {
            ExploreMode.POSTS -> Unit
            ExploreMode.EVENTS -> loadEvents()
            ExploreMode.GROUPS -> loadGroups()
            ExploreMode.POLLS -> loadPolls()
            ExploreMode.MARKET -> loadMarket()
            ExploreMode.OFFICIAL_ALERTS -> loadUrgentAlerts()
            ExploreMode.DISCOVERY_ROW -> loadDiscoveryRow(reset = true)
        }
    }


    private val supportedDiscoveryRows = setOf(
        "URGENT_ALERTS",
        "FOLLOWED_SOURCES",
        "LOST_FOUND",
        "NEIGHBORHOOD_RECOMMENDATIONS",
        "EVENTS_AND_OPPORTUNITIES",
        "FOR_YOU"
    )

    /** Load server-assembled rows only for the active membership. */
    fun loadDiscoveryHome() {
        val scopeId = _uiState.value.membership?.locationId
        if (!_uiState.value.authenticated || scopeId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    discoveryRows = emptyList(),
                    discoveryScopeId = null,
                    discoveryBusy = false,
                    discoveryError = null,
                    selectedDiscoveryRow = null,
                    discoveryRowCards = emptyList(),
                    discoveryRowTotal = 0,
                    discoveryRowPage = -1,
                    discoveryRowLast = true,
                    discoveryRowBusy = false,
                    discoveryRowError = null,
                    discoveryPreviewCard = null
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                if (it.authenticated && it.membership?.locationId == scopeId) {
                    it.copy(discoveryRows = emptyList(), discoveryScopeId = scopeId, discoveryBusy = true, discoveryError = null)
                } else it
            }
            try {
                val rows = repository.getDiscoveryHome()
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == scopeId) {
                        it.copy(discoveryRows = rows, discoveryScopeId = scopeId, discoveryBusy = false, discoveryError = null)
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == scopeId) {
                        it.copy(discoveryRows = emptyList(), discoveryScopeId = scopeId, discoveryBusy = false, discoveryError = failureMessage(error))
                    } else it
                }
            }
        }
    }

    fun openDiscoveryRow(row: String) {
        if (row !in supportedDiscoveryRows) return
        val current = _uiState.value
        val scopeId = current.membership?.locationId
        if (!current.authenticated || scopeId.isNullOrBlank()) return
        _uiState.update {
            it.copy(
                tab = MainTab.EXPLORE,
                exploreMode = ExploreMode.DISCOVERY_ROW,
                selectedDiscoveryRow = row,
                discoveryRowCards = emptyList(),
                discoveryRowTotal = 0,
                discoveryRowPage = -1,
                discoveryRowLast = true,
                discoveryRowBusy = false,
                discoveryRowError = null,
                discoveryPreviewCard = null
            )
        }
        loadDiscoveryRow(reset = true)
    }

    fun loadMoreDiscoveryRow() = loadDiscoveryRow(reset = false)


    private fun loadDiscoveryRow(reset: Boolean) {
        val current = _uiState.value
        val row = current.selectedDiscoveryRow
        val scopeId = current.membership?.locationId
        if (!current.authenticated || scopeId.isNullOrBlank() || row.isNullOrBlank() || row !in supportedDiscoveryRows) return
        if (!reset && (current.discoveryRowBusy || current.discoveryRowLast)) return
        if (!reset && current.discoveryScopeId != scopeId) return
        val requestedPage = if (reset) 0 else current.discoveryRowPage + 1
        viewModelScope.launch {
            _uiState.update {
                if (it.authenticated && it.membership?.locationId == scopeId && it.selectedDiscoveryRow == row) {
                    it.copy(
                        discoveryRowBusy = true,
                        discoveryRowError = null,
                        discoveryRowCards = if (reset) emptyList() else it.discoveryRowCards,
                        discoveryRowPage = if (reset) -1 else it.discoveryRowPage
                    )
                } else it
            }
            try {
                val page = repository.getDiscoveryRow(row, page = requestedPage, size = 20)
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == scopeId && it.selectedDiscoveryRow == row) {
                        val allCards = if (reset) page.content else it.discoveryRowCards + page.content
                        it.copy(
                            discoveryRowCards = allCards.distinctBy { card -> "${card.sourceType}:${card.sourceId}" },
                            discoveryRowTotal = page.totalElements,
                            discoveryRowPage = page.pageNumber,
                            discoveryRowLast = page.last,
                            discoveryRowBusy = false,
                            discoveryRowError = null,
                            discoveryScopeId = scopeId
                        )
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == scopeId && it.selectedDiscoveryRow == row) {
                        it.copy(discoveryRowBusy = false, discoveryRowError = failureMessage(error))
                    } else it
                }
            }
        }
    }

    private fun isCurrentDiscoveryCard(current: AppUiState, row: String, card: DiscoveryCardDto): Boolean {
        val locationId = current.membership?.locationId ?: return false
        if (current.discoveryScopeId != locationId) return false
        if (card.scopeLocationId != null && card.scopeLocationId != locationId) return false

        val inHomeRow = current.discoveryRows.firstOrNull { it.row == row }?.cards
            ?.any { it.sourceType == card.sourceType && it.sourceId == card.sourceId } == true
        val inTopicPage = current.selectedDiscoveryRow == row &&
            current.discoveryRowCards.any { it.sourceType == card.sourceType && it.sourceId == card.sourceId }
        return inHomeRow || inTopicPage
    }

    fun previewDiscoveryCard(card: DiscoveryCardDto) {
        val current = _uiState.value
        val topicRow = current.selectedDiscoveryRow?.takeIf { row ->
            current.discoveryRowCards.any { it.sourceType == card.sourceType && it.sourceId == card.sourceId } &&
                isCurrentDiscoveryCard(current, row, card)
        }
        val homeRow = current.discoveryRows.firstOrNull { row ->
            row.cards.any { it.sourceType == card.sourceType && it.sourceId == card.sourceId } &&
                isCurrentDiscoveryCard(current, row.row, card)
        }?.row
        val row = topicRow ?: homeRow
        if (current.authenticated && !row.isNullOrBlank()) {
            _uiState.update { it.copy(discoveryPreviewCard = card) }
        }
    }

    fun dismissDiscoveryPreview() {
        _uiState.update { it.copy(discoveryPreviewCard = null) }
    }

    fun recordDiscoveryImpression(row: String, card: DiscoveryCardDto) {
        val current = _uiState.value
        if (!current.authenticated ||
            row !in supportedDiscoveryRows ||
            card.sourceType !in setOf("NEIGHBORHOOD_POST", "NEIGHBORHOOD_EVENT", "JOB", "URGENT_ALERT", "PROVIDER_LISTING") ||
            card.sourceId.isBlank() ||
            !isCurrentDiscoveryCard(current, row, card)) return
        viewModelScope.launch {
            try {
                repository.recordDiscoveryImpression(row, card)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Impression bookkeeping must not break browsing.
            }
        }
    }

    fun loadEvents() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(eventsBusy = true, eventsError = null) }
            try {
                val page = repository.getEvents()
                _uiState.update { it.copy(events = page.content, eventsBusy = false, eventsError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(eventsBusy = false, eventsError = failureMessage(error)) }
            }
        }
    }

    fun loadGroups() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(groupsBusy = true, groupsError = null) }
            try {
                val page = repository.getGroups()
                _uiState.update { it.copy(groups = page.content, groupsBusy = false, groupsError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(groupsBusy = false, groupsError = failureMessage(error)) }
            }
        }
    }

    fun loadPolls() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollsBusy = true, pollsError = null) }
            try {
                val page = repository.getPolls()
                _uiState.update { it.copy(polls = page.content, pollsBusy = false, pollsError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(pollsBusy = false, pollsError = failureMessage(error)) }
            }
        }
    }

    fun toggleEventRsvp(event: EventDto) {
        if (event.id in _uiState.value.pollActionIds || !canChangeEventRsvp(event)) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollActionIds = it.pollActionIds + event.id) }
            try {
                if (event.rsvpedByMe) repository.cancelEventRsvp(event.id) else repository.rsvpEvent(event.id)
                loadEvents()
                _uiState.update { it.copy(notice = if (event.rsvpedByMe) "أُلغي تسجيل حضورك." else "سُجّل حضورك للفعالية.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            } finally {
                _uiState.update { it.copy(pollActionIds = it.pollActionIds - event.id) }
            }
        }
    }

    fun toggleGroupMembership(group: GroupDto) {
        if (group.id in _uiState.value.pollActionIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollActionIds = it.pollActionIds + group.id) }
            try {
                if (group.joinedByMe) repository.leaveGroup(group.id) else repository.joinGroup(group.id)
                loadGroups()
                _uiState.update { it.copy(notice = if (group.joinedByMe) "غادرت المجموعة." else "انضممت إلى المجموعة.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            } finally {
                _uiState.update { it.copy(pollActionIds = it.pollActionIds - group.id) }
            }
        }
    }

    fun castPollVote(poll: PollDto, optionId: String) {
        if (poll.id in _uiState.value.pollActionIds || poll.votedByMe != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollActionIds = it.pollActionIds + poll.id) }
            try {
                repository.voteOnPoll(poll.id, optionId)
                loadPolls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            } finally {
                _uiState.update { it.copy(pollActionIds = it.pollActionIds - poll.id) }
            }
        }
    }

    fun withdrawPollVote(poll: PollDto) {
        if (poll.id in _uiState.value.pollActionIds || poll.votedByMe == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollActionIds = it.pollActionIds + poll.id) }
            try {
                repository.withdrawPollVote(poll.id)
                loadPolls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            } finally {
                _uiState.update { it.copy(pollActionIds = it.pollActionIds - poll.id) }
            }
        }
    }

    fun setEventCategory(value: String) { _uiState.update { it.copy(eventCategory = value) } }
    fun setEventTitle(value: String) { _uiState.update { it.copy(eventTitle = value.take(200)) } }
    fun setEventDescription(value: String) { _uiState.update { it.copy(eventDescription = value.take(2000)) } }
    fun setEventStartsAt(value: String) { _uiState.update { it.copy(eventStartsAt = value.take(40)) } }
    fun setEventLocationLabel(value: String) { _uiState.update { it.copy(eventLocationLabel = value.take(200)) } }
    fun setEventOrganizerLabel(value: String) { _uiState.update { it.copy(eventOrganizerLabel = value.take(200)) } }
    fun setEventRegistration(value: String) { _uiState.update { it.copy(eventRegistration = value) } }
    fun setEventCapacity(value: String) { _uiState.update { it.copy(eventCapacity = value.take(8)) } }
    fun setPollQuestion(value: String) { _uiState.update { it.copy(pollQuestion = value.take(200)) } }
    fun setPollAuthorLabel(value: String) { _uiState.update { it.copy(pollAuthorLabel = value.take(200)) } }
    fun setPollOption(index: Int, value: String) {
        _uiState.update { state -> state.copy(pollOptions = state.pollOptions.mapIndexed { i, old -> if (i == index) value.take(200) else old }) }
    }
    fun addPollOption() {
        _uiState.update { state -> if (state.pollOptions.size < 5) state.copy(pollOptions = state.pollOptions + "") else state }
    }
    fun removePollOption(index: Int) {
        _uiState.update { state -> if (state.pollOptions.size > 2) state.copy(pollOptions = state.pollOptions.filterIndexed { i, _ -> i != index }) else state }
    }

    fun publishEvent() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val startsAt = runCatching { java.time.Instant.parse(current.eventStartsAt.trim()) }.getOrNull()
        val title = current.eventTitle.trim()
        val description = current.eventDescription.trim()
        val location = current.eventLocationLabel.trim()
        val organizer = current.eventOrganizerLabel.trim()
        val capacity = current.eventCapacity.trim().toIntOrNull()
        if (title.isEmpty() || description.isEmpty() || location.isEmpty() || organizer.isEmpty() || startsAt == null) {
            _uiState.update { it.copy(notice = "أكمل عنوان الفعالية وتفاصيلها ومكانها والجهة المنظمة وموعدها بصيغة ISO-8601.") }
            return
        }
        if (!startsAt.isAfter(java.time.Instant.now())) {
            _uiState.update { it.copy(notice = "موعد الفعالية يجب أن يكون في المستقبل.") }
            return
        }
        if (current.eventRegistration == "OPEN" && current.eventCapacity.isNotBlank() ||
            current.eventRegistration != "OPEN" && (capacity == null || capacity <= 0)) {
            _uiState.update { it.copy(notice = "الفعالية المفتوحة لا تحتاج سعة؛ والفعالية محدودة المقاعد تتطلب سعة موجبة.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitBusy = true, notice = null) }
            try {
                repository.createEvent(
                    CreateEventRequest(
                        locationId = membership.locationId,
                        category = current.eventCategory,
                        title = title,
                        description = description,
                        startsAt = startsAt.toString(),
                        endsAt = null,
                        locationLabel = location,
                        organizerLabel = organizer,
                        capacity = if (current.eventRegistration == "OPEN") null else capacity,
                        registration = current.eventRegistration
                    )
                )
                _uiState.update { it.copy(composer = null, submitBusy = false, exploreMode = ExploreMode.EVENTS, tab = MainTab.EXPLORE, notice = "أُنشئت الفعالية في حيّك.") }
                loadEvents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(submitBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun publishPoll() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val question = current.pollQuestion.trim()
        val author = current.pollAuthorLabel.trim()
        val options = current.pollOptions.map(String::trim).filter(String::isNotEmpty)
        if (question.isEmpty() || author.isEmpty() || options.size !in 2..5 || options.distinct().size != options.size) {
            _uiState.update { it.copy(notice = "أدخل سؤالًا واسم الجهة الناشرة وخيارين إلى خمسة خيارات مختلفة.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitBusy = true, notice = null) }
            try {
                repository.createPoll(CreatePollRequest(membership.locationId, question, author, options))
                _uiState.update { it.copy(composer = null, submitBusy = false, pollQuestion = "", pollAuthorLabel = "", pollOptions = listOf("", ""), exploreMode = ExploreMode.POLLS, tab = MainTab.EXPLORE, notice = "نُشر الاستطلاع في حيّك.") }
                loadPolls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(submitBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun publishPost() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val retryPostId = current.pendingPhotoPostId
        val title = current.postTitle.trim()
        val body = current.postBody.trim()
        if (retryPostId == null && (title.isEmpty() || body.isEmpty())) {
            _uiState.update { it.copy(notice = "أدخل عنوان المنشور ونصه.") }
            return
        }
        if (retryPostId != null && current.postPhotoUris.isEmpty()) {
            _uiState.update { it.copy(notice = "اختر صورة لإعادة المحاولة، أو أنهِ الرفع دون صور.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(submitBusy = true, notice = null, uploadProgress = null) }
            try {
                val targetPostId: String = if (retryPostId == null) {
                    val created = repository.createPost(
                        CreatePostRequest(membership.locationId, current.postCategory, title, body)
                    )
                    _uiState.update {
                        it.copy(
                            pendingPhotoPostId = created.id,
                            uploadProgress = if (current.postPhotoUris.isEmpty()) null else "نُشر النص؛ نبدأ رفع الصور…"
                        )
                    }
                    created.id
                } else {
                    retryPostId
                }

                val selectedPhotos = current.postPhotoUris
                val failedPhotos = mutableListOf<Uri>()
                val failureDetails = mutableListOf<String>()
                selectedPhotos.forEachIndexed { index, uri ->
                    _uiState.update {
                        it.copy(uploadProgress = "رفع الصورة ${index + 1} من ${selectedPhotos.size}…")
                    }
                    try {
                        repository.uploadPostMedia(targetPostId, uri)
                        _uiState.update { state ->
                            state.copy(postPhotoUris = state.postPhotoUris.filterNot { it == uri })
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: HttpException) {
                        if (error.code() == 400) {
                            failedPhotos += uri
                            failureDetails += "رفض الخادم الصورة؛ تحقق من الصيغة والحجم."
                        } else {
                            throw error
                        }
                    } catch (error: java.io.IOException) {
                        failedPhotos += uri
                        failureDetails += when {
                            error.message?.contains("حجم الصورة") == true -> error.message.orEmpty()
                            error.message?.contains("صيغة الصورة") == true -> error.message.orEmpty()
                            error.message?.contains("مخزن الصور") == true -> error.message.orEmpty()
                            else -> "تعذر الاتصال بمخزن الصور أو تأكيد الرفع."
                        }
                    }
                }

                if (failedPhotos.isEmpty()) {
                    val message = when {
                        retryPostId != null -> "اكتمل رفع الصور المتبقية للمنشور."
                        selectedPhotos.isEmpty() -> "نُشر المنشور بنجاح."
                        else -> "نُشر المنشور وأُرفقت الصور بنجاح."
                    }
                    _uiState.update {
                        it.copy(
                            composer = null,
                            submitBusy = false,
                            tab = MainTab.HOME,
                            postTitle = "",
                            postBody = "",
                            postPhotoUris = emptyList(),
                            pendingPhotoPostId = null,
                            uploadProgress = null,
                            notice = message
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            composer = ComposerKind.POST,
                            submitBusy = false,
                            postPhotoUris = failedPhotos,
                            pendingPhotoPostId = targetPostId,
                            uploadProgress = null,
                            notice = "نُشر النص، لكن تعذر رفع ${failedPhotos.size} من ${selectedPhotos.size} صور. " +
                                (failureDetails.firstOrNull()?.let { "السبب: $it. " } ?: "") +
                                "استبدل الصورة غير المدعومة أو الكبيرة، أو أعد المحاولة. لن يُنشأ منشور جديد."
                        )
                    }
                }
                loadFeed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(submitBusy = false, uploadProgress = null, notice = failureMessage(error)) }
            }
        }
    }

    fun publishMarketItem() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val title = current.marketTitle.trim()
        val pickup = current.marketPickup.trim()
        val isFree = current.marketCategory == "FREE"
        val priceCents = if (isFree) null else parsePriceCents(current.marketPrice)

        if (title.isEmpty() || pickup.isEmpty()) {
            _uiState.update { it.copy(notice = "أدخل اسم الغرض ومكان الاستلام داخل الحي.") }
            return
        }
        if (!isFree && (priceCents == null || priceCents <= 0 || current.marketCurrency.length != 3)) {
            _uiState.update { it.copy(notice = "أدخل سعرًا موجبًا وعملة من ثلاثة أحرف، مثل SYP.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitBusy = true, notice = null) }
            try {
                repository.createMarketItem(
                    CreateMarketItemRequest(
                        membership.locationId, current.marketCategory, title, current.marketCondition,
                        priceCents, if (isFree) null else current.marketCurrency, pickup
                    )
                )
                _uiState.update {
                    it.copy(
                        composer = null, submitBusy = false, marketTitle = "", marketPickup = "",
                        marketPrice = "", tab = MainTab.EXPLORE, exploreMode = ExploreMode.MARKET, notice = "أُضيف العرض إلى حراج الحي."
                    )
                }
                loadMarket()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(submitBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun withdrawMarketItem(item: MarketItemDto) {
        if (!item.mine) return
        viewModelScope.launch {
            try {
                repository.withdrawMarketItem(item.id)
                _uiState.update {
                    it.copy(marketItems = it.marketItems.filterNot { row -> row.id == item.id }, notice = "سُحب العرض من السوق.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            }
        }
    }

    fun loadMarket() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(marketBusy = true, marketError = null) }
            try {
                val page = repository.getMarket()
                _uiState.update { it.copy(marketItems = page.content, marketBusy = false, marketError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(marketBusy = false, marketError = failureMessage(error)) }
            }
        }
    }

    fun loadNotifications() {
        if (!_uiState.value.authenticated) return
        viewModelScope.launch {
            _uiState.update { it.copy(notificationsBusy = true, notificationsError = null) }
            try {
                val page = repository.getNotifications()
                _uiState.update { it.copy(notifications = page.content, notificationsBusy = false, notificationsError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notificationsBusy = false, notificationsError = failureMessage(error)) }
            }
        }
    }

    /**
     * Fetch only currently active, source-verified alerts for the member's
     * exact level-3 neighborhood. If the user switches neighborhoods while
     * the request is in flight, discard the stale response rather than leak
     * the previous neighborhood's alert list into the new scope.
     */
    fun loadUrgentAlerts() {
        val current = _uiState.value
        val locationId = current.membership?.locationId
        if (!current.authenticated || locationId.isNullOrBlank()) {
            urgentAlertsJob?.cancel()
            urgentAlertsJob = null
            _uiState.update {
                it.copy(
                    urgentAlerts = emptyList(),
                    urgentAlertsScopeId = null,
                    urgentAlertsBusy = false,
                    urgentAlertsError = null
                )
            }
            return
        }

        urgentAlertsJob?.cancel()
        urgentAlertsJob = viewModelScope.launch {
            _uiState.update {
                if (it.authenticated && it.membership?.locationId == locationId) {
                    it.copy(
                        // Active/withdrawn is server-owned. Fail closed while refreshing:
                        // never keep showing a previously active alert as current.
                        urgentAlerts = emptyList(),
                        urgentAlertsScopeId = locationId,
                        urgentAlertsBusy = true,
                        urgentAlertsError = null
                    )
                } else it
            }
            try {
                val alerts = repository.getUrgentAlerts(locationId)
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == locationId) {
                        it.copy(
                            urgentAlerts = alerts,
                            urgentAlertsScopeId = locationId,
                            urgentAlertsBusy = false,
                            urgentAlertsError = null
                        )
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    if (it.authenticated && it.membership?.locationId == locationId) {
                        it.copy(
                            urgentAlerts = emptyList(),
                            urgentAlertsBusy = false,
                            urgentAlertsError = failureMessage(error)
                        )
                    } else it
                }
            }
        }
    }

    fun markNotificationRead(notification: NotificationDto) {
        if (notification.read) return
        viewModelScope.launch {
            try {
                val updated = repository.markNotificationRead(notification.id)
                _uiState.update {
                    it.copy(notifications = it.notifications.map { row -> if (row.id == updated.id) updated else row })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            }
        }
    }

    fun requestVerification() {
        viewModelScope.launch {
            _uiState.update { it.copy(profileBusy = true, notice = null) }
            try {
                val membership = repository.requestNeighborhoodVerification()
                _uiState.update {
                    it.copy(membership = membership, profileBusy = false, notice = "سُجّل طلب التحقق؛ لم تصبح العضوية موثّقة بعد.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(profileBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    /**
     * Reports are submitted only through the backend's closed POST/COMMENT
     * contract. We do not send free-text notes or promise a moderation outcome
     * because neither is part of the public report request.
     */
    fun reportPost(post: PostDto, reason: String) {
        submitContentReport("POST", post.id, post.authorId, reason)
    }

    fun reportComment(comment: CommentDto, reason: String) {
        submitContentReport("COMMENT", comment.id, comment.authorId, reason)
    }

    private fun submitContentReport(targetType: String, targetId: String, authorId: String, reason: String) {
        val current = _uiState.value
        val reporterId = current.membership?.userId
        val key = "$targetType:$targetId"
        if (!current.authenticated || reporterId.isNullOrBlank() || targetId.isBlank()) return
        if (targetType !in setOf("POST", "COMMENT") ||
            reason !in setOf("SPAM", "HARASSMENT", "INAPPROPRIATE", "OTHER")) {
            _uiState.update { it.copy(notice = "اختر سببًا صالحًا للإبلاغ.") }
            return
        }
        if (reporterId.equals(authorId, ignoreCase = true)) {
            _uiState.update { it.copy(notice = "لا يمكنك الإبلاغ عن محتواك أنت.") }
            return
        }
        if (key in current.reportingContentKeys || key in current.reportedContentKeys) return

        viewModelScope.launch {
            _uiState.update {
                if (it.authenticated && it.membership?.userId?.equals(reporterId, ignoreCase = true) == true) {
                    it.copy(reportingContentKeys = it.reportingContentKeys + key, notice = null)
                } else it
            }
            if (_uiState.value.let {
                    !it.authenticated || it.membership?.userId?.equals(reporterId, ignoreCase = true) != true
                }) return@launch
            try {
                repository.reportContent(targetType, targetId, reason)
                _uiState.update {
                    if (it.authenticated && it.membership?.userId?.equals(reporterId, ignoreCase = true) == true) {
                        it.copy(
                            reportingContentKeys = it.reportingContentKeys - key,
                            reportedContentKeys = it.reportedContentKeys + key,
                            notice = "أُرسل البلاغ للمراجعة. لا يمكن الجزم بنتيجة المراجعة مسبقًا."
                        )
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 409) {
                    _uiState.update {
                        if (it.authenticated && it.membership?.userId?.equals(reporterId, ignoreCase = true) == true) {
                            it.copy(
                                reportingContentKeys = it.reportingContentKeys - key,
                                reportedContentKeys = it.reportedContentKeys + key,
                                notice = "سبق أن أرسلت بلاغًا عن هذا المحتوى؛ لم يُنشأ بلاغ مكرر."
                            )
                        } else it
                    }
                } else {
                    _uiState.update {
                        if (it.authenticated && it.membership?.userId?.equals(reporterId, ignoreCase = true) == true) {
                            it.copy(
                                reportingContentKeys = it.reportingContentKeys - key,
                                notice = failureMessage(error)
                            )
                        } else it
                    }
                }
            }
        }
    }

    fun openComments(post: PostDto) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(commentsPost = post, comments = emptyList(), commentsBusy = true, commentDraft = "", notice = null)
            }
            try {
                val page = repository.getComments(post.id)
                _uiState.update { it.copy(comments = page.content, commentsBusy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(commentsBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun closeComments() {
        _uiState.update { it.copy(commentsPost = null, comments = emptyList(), commentDraft = "") }
    }

    fun sendComment() {
        val current = _uiState.value
        val post = current.commentsPost ?: return
        val body = current.commentDraft.trim()
        if (body.isEmpty()) {
            _uiState.update { it.copy(notice = "اكتب تعليقًا قبل الإرسال.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(commentBusy = true, notice = null) }
            try {
                val comment = repository.createComment(post.id, body)
                _uiState.update { it.copy(comments = it.comments + comment, commentDraft = "", commentBusy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(commentBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun toggleReaction(post: PostDto) {
        if (post.id in _uiState.value.reactingPostIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(reactingPostIds = it.reactingPostIds + post.id) }
            try {
                if (post.reactedByMe) repository.removeReaction(post.id) else repository.react(post.id)
                _uiState.update { state ->
                    fun updateRow(row: PostDto): PostDto = if (row.id != post.id) row else row.copy(
                        reactedByMe = !post.reactedByMe,
                        reactionsCount = (row.reactionsCount + if (post.reactedByMe) -1 else 1).coerceAtLeast(0)
                    )
                    state.copy(
                        posts = state.posts.map(::updateRow),
                        searchResults = state.searchResults.map(::updateRow),
                        reactingPostIds = state.reactingPostIds - post.id
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(reactingPostIds = it.reactingPostIds - post.id, notice = failureMessage(error)) }
            }
        }
    }

    /**
     * The backend is the authority for the LOST_FOUND lifecycle. Only the
     * author may close an active report, and only the server-confirmed view
     * replaces the row in local state.
     */
    fun updateLostFoundState(post: PostDto, targetState: String) {
        val current = _uiState.value
        if (!post.category.equals("LOST_FOUND", ignoreCase = true) ||
            targetState !in setOf("FOUND", "RESOLVED") ||
            !post.lostFoundState.equals("ACTIVE", ignoreCase = true) ||
            current.membership?.userId?.equals(post.authorId, ignoreCase = true) != true ||
            post.id in current.lostFoundActionIds
        ) return

        viewModelScope.launch {
            _uiState.update { it.copy(lostFoundActionIds = it.lostFoundActionIds + post.id) }
            try {
                val updated = repository.updateLostFoundState(post.id, targetState)
                _uiState.update { state ->
                    // The command response is a write-path projection; it does not
                    // carry the feed's grouped media/reaction aggregates. Merge only
                    // the lifecycle fact into existing rows so UI state cannot lose
                    // images or reset reaction counts after a successful command.
                    val mergeLifecycleState: (PostDto) -> PostDto = { row ->
                        mergeLostFoundLifecycleState(row, updated)
                    }
                    state.copy(
                        posts = state.posts.map(mergeLifecycleState),
                        searchResults = state.searchResults.map(mergeLifecycleState),
                        commentsPost = state.commentsPost?.let(mergeLifecycleState),
                        notice = if (targetState == "FOUND") {
                            "تم تحديث البلاغ: عُثر على المفقود."
                        } else {
                            "تم إغلاق بلاغ المفقودات."
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = failureMessage(error)) }
            } finally {
                _uiState.update {
                    it.copy(lostFoundActionIds = it.lostFoundActionIds - post.id)
                }
            }
        }
    }

    private fun failureMessage(error: Throwable): String {
        if (error is HttpException) {
            return when (error.code()) {
                401 -> {
                    repository.clearAccessToken()
                    val current = _uiState.value
                    if (current.authenticated &&
                        (current.composer == ComposerKind.POST ||
                            current.pendingPhotoPostId != null ||
                            current.postPhotoUris.isNotEmpty())) {
                        stateBeforeSessionRecovery = current.copy(
                            submitBusy = false,
                            uploadProgress = null,
                            sessionRestoring = false,
                            silentReauthRequired = false,
                            authError = null,
                            notice = if (current.pendingPhotoPostId != null) {
                                "انتهت الجلسة أثناء رفع الصور. بعد تسجيل الدخول، أعد محاولة الصور المتبقية دون إنشاء منشور جديد."
                            } else {
                                current.notice
                            }
                        )
                    }
                    if (!silentReauthInFlight) {
                        silentReauthInFlight = true
                        _uiState.value = AppUiState(
                            sessionRestoring = true,
                            sessionRestoreAttempted = true,
                            silentReauthRequired = true,
                            nativePasskeySession = current.nativePasskeySession,
                            authError = "انتهت جلسة الدخول. نحاول استعادتها بأمان…"
                        )
                    }
                    "انتهت جلسة الدخول."
                }
                403 -> "لا تملك الصلاحية لهذا الإجراء أو أن عضويتك في الحي غير نشطة."
                404 -> "لم يعد هذا المحتوى متاحًا. حدّث الشاشة للتحقق من حالته."
                409 -> "تغيّرت الحالة أثناء العملية. حدّث الشاشة ثم أعد المحاولة."
                429 -> "تجاوزت حد الطلبات مؤقتًا. حاول مجددًا بعد قليل."
                in 500..599 -> "الخدمة غير متاحة مؤقتًا. حاول مجددًا لاحقًا."
                else -> "تعذر إكمال الطلب (HTTP ${error.code()})."
            }
        }
        return if (error is SocketTimeoutException) {
            "استغرقت الاستجابة وقتًا أطول من المتوقع. تحقق من الاتصال وأعد المحاولة."
        } else {
            "تعذر الاتصال بالخدمة. تحقق من الاتصال بالإنترنت ثم أعد المحاولة."
        }
    }

    private fun findNode(root: GeoNodeDto, id: String): GeoNodeDto? {
        if (root.id == id) return root
        root.children.forEach { child ->
            val match = findNode(child, id)
            if (match != null) return match
        }
        return null
    }

    class Factory(private val repository: MarketplaceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(CommunityViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            return CommunityViewModel(repository) as T
        }
    }
}
