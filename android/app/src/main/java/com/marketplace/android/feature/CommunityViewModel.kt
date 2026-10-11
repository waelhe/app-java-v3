package com.marketplace.android.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marketplace.android.core.network.CommentDto
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
import com.marketplace.android.core.network.PostDto
import com.marketplace.android.core.network.ListingSummaryDto
import com.marketplace.android.data.MarketplaceRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class MainTab { HOME, MARKET, PROPERTY, DIRECTORY, EXPLORE, NOTIFICATIONS, PROFILE }
enum class ExploreMode { POSTS, EVENTS, GROUPS, POLLS, MARKET }
enum class ComposerKind { POST, MARKET, EVENT, POLL }

data class AppUiState(
    val authenticated: Boolean = false,
    val loadingAccount: Boolean = false,
    val authError: String? = null,
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
    val searchBusy: Boolean = false,
    val searchError: String? = null,
    val marketItems: List<MarketItemDto> = emptyList(),
    val marketBusy: Boolean = false,
    val marketError: String? = null,
    val notifications: List<NotificationDto> = emptyList(),
    val notificationsBusy: Boolean = false,
    val notificationsError: String? = null,
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
    val authSuccess: String? = null,
    val composer: ComposerKind? = null,
    val postCategory: String = "GENERAL",
    val postTitle: String = "",
    val postBody: String = "",
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
    val notice: String? = null
)

class CommunityViewModel(
    private val repository: MarketplaceRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()
    private var locationSearchJob: Job? = null

    fun authenticationFailed(message: String) {
        _uiState.update { it.copy(authError = message, authSuccess = null, loadingAccount = false) }
    }

    fun authenticated(accessToken: String) {
        repository.setAccessToken(accessToken)
        _uiState.value = AppUiState(authenticated = true, loadingAccount = true)
        loadAccount()
    }

    fun signOutOnDevice() {
        repository.clearAccessToken()
        _uiState.value = AppUiState()
    }

    fun dismissNotice() { _uiState.update { it.copy(notice = null) } }

    private fun loadAccount() {
        viewModelScope.launch {
            try {
                val membership = repository.getMyNeighborhood()
                val locationName = runCatching {
                    findNode(repository.getGeoTree(), membership.locationId)?.nameAr
                }.getOrNull()
                _uiState.update {
                    it.copy(
                        authenticated = true, loadingAccount = false, membership = membership,
                        scopeName = locationName, membershipRequired = false, authError = null, notice = null
                    )
                }
                loadFeed()
            } catch (error: Throwable) {
                if (error is HttpException && error.code() == 404) {
                    _uiState.update {
                        it.copy(authenticated = true, loadingAccount = false, membership = null, membershipRequired = true, authError = null)
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
            } catch (error: Throwable) {
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
                        tab = MainTab.HOME, notice = "انضممت إلى ${node.nameAr}."
                    )
                }
                loadFeed()
                loadMarket()
            } catch (error: Throwable) {
                _uiState.update { it.copy(locationBusy = false, locationError = failureMessage(error)) }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(tab = tab, notice = null) }
        when (tab) {
            MainTab.HOME -> loadFeed()
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
            MainTab.HOME -> loadFeed()
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
                    purpose = current.propertyPurpose,
                    propertyType = current.propertyType,
                    page = 0,
                    size = 20
                )
                _uiState.update { it.copy(propertyListings = page.content, propertyBusy = false, propertyError = null) }
            } catch (error: Throwable) {
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
                    minRating = current.directoryMinRating,
                    page = 0,
                    size = 30
                )
                _uiState.update { it.copy(directoryListings = page.content, directoryBusy = false, directoryError = null) }
            } catch (error: Throwable) {
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
                        authError = null,
                        authSuccess = "تم إنشاء الحساب. سجّل الدخول الآن باستخدام بريدك وكلمة المرور."
                    )
                }
            } catch (error: Throwable) {
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

    fun loadFeed() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(feedBusy = true, feedError = null) }
            try {
                val page = repository.getFeed()
                _uiState.update {
                    it.copy(posts = page.content, feedPage = page.pageNumber, feedLast = page.last, feedBusy = false, feedError = null)
                }
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(feedBusy = false, feedError = failureMessage(error)) }
            }
        }
    }

    fun updateSearchQuery(query: String) { _uiState.update { it.copy(searchQuery = query, searchError = null) } }

    fun searchPosts() {
        val query = _uiState.value.searchQuery.trim()
        if (query.length < 2) {
            _uiState.update { it.copy(searchError = "اكتب حرفين على الأقل لبدء البحث.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(searchBusy = true, searchError = null) }
            try {
                val page = repository.searchPosts(query)
                _uiState.update { it.copy(searchResults = page.content, searchBusy = false, searchError = null) }
            } catch (error: Throwable) {
                _uiState.update { it.copy(searchBusy = false, searchError = failureMessage(error)) }
            }
        }
    }

    fun beginComposer(kind: ComposerKind) {
        _uiState.update {
            it.copy(
                composer = kind, postCategory = "GENERAL", postTitle = "", postBody = "",
                marketCategory = "FREE", marketCondition = "GOOD", marketTitle = "",
                marketPrice = "", marketCurrency = "SYP", marketPickup = "",
                eventCategory = "SOCIAL", eventTitle = "", eventDescription = "", eventStartsAt = "",
                eventLocationLabel = "", eventOrganizerLabel = "", eventRegistration = "OPEN",
                eventCapacity = "", pollQuestion = "", pollAuthorLabel = "", pollOptions = listOf("", ""),
                notice = null
            )
        }
    }

    fun closeComposer() { _uiState.update { it.copy(composer = null, submitBusy = false) } }
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
        _uiState.update { it.copy(exploreMode = mode, notice = null) }
        when (mode) {
            ExploreMode.POSTS -> Unit
            ExploreMode.EVENTS -> loadEvents()
            ExploreMode.GROUPS -> loadGroups()
            ExploreMode.POLLS -> loadPolls()
            ExploreMode.MARKET -> loadMarket()
        }
    }

    private fun loadExploreBoard() {
        when (_uiState.value.exploreMode) {
            ExploreMode.POSTS -> Unit
            ExploreMode.EVENTS -> loadEvents()
            ExploreMode.GROUPS -> loadGroups()
            ExploreMode.POLLS -> loadPolls()
            ExploreMode.MARKET -> loadMarket()
        }
    }

    fun loadEvents() {
        if (!_uiState.value.authenticated || _uiState.value.membership == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(eventsBusy = true, eventsError = null) }
            try {
                val page = repository.getEvents()
                _uiState.update { it.copy(events = page.content, eventsBusy = false, eventsError = null) }
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(pollsBusy = false, pollsError = failureMessage(error)) }
            }
        }
    }

    fun toggleEventRsvp(event: EventDto) {
        if (event.id in _uiState.value.pollActionIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(pollActionIds = it.pollActionIds + event.id) }
            try {
                if (event.rsvpedByMe) repository.cancelEventRsvp(event.id) else repository.rsvpEvent(event.id)
                loadEvents()
                _uiState.update { it.copy(notice = if (event.rsvpedByMe) "أُلغي تسجيل حضورك." else "سُجّل حضورك للفعالية.") }
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(submitBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun publishPost() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val title = current.postTitle.trim()
        val body = current.postBody.trim()
        if (title.isEmpty() || body.isEmpty()) {
            _uiState.update { it.copy(notice = "أدخل عنوان المنشور ونصه.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitBusy = true, notice = null) }
            try {
                repository.createPost(CreatePostRequest(membership.locationId, current.postCategory, title, body))
                _uiState.update {
                    it.copy(composer = null, submitBusy = false, tab = MainTab.HOME, postTitle = "", postBody = "", notice = "نُشر المحتوى بنجاح.")
                }
                loadFeed()
            } catch (error: Throwable) {
                _uiState.update { it.copy(submitBusy = false, notice = failureMessage(error)) }
            }
        }
    }

    fun publishMarketItem() {
        val current = _uiState.value
        val membership = current.membership ?: return
        val title = current.marketTitle.trim()
        val pickup = current.marketPickup.trim()
        val isFree = current.marketCategory == "FREE"
        val priceCents = if (isFree) null else current.marketPrice.trim().replace(',', '.')
            .toBigDecimalOrNull()?.multiply(BigDecimal(100))?.setScale(0, RoundingMode.HALF_UP)?.toInt()

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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(notificationsBusy = false, notificationsError = failureMessage(error)) }
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(profileBusy = false, notice = failureMessage(error)) }
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
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
            } catch (error: Throwable) {
                _uiState.update { it.copy(reactingPostIds = it.reactingPostIds - post.id, notice = failureMessage(error)) }
            }
        }
    }

    private fun failureMessage(error: Throwable): String {
        if (error is HttpException) {
            return when (error.code()) {
                401 -> {
                    repository.clearAccessToken()
                    _uiState.value = AppUiState(authError = "انتهت جلسة الدخول. سجّل الدخول مجددًا لمتابعة الاستخدام.")
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

    class Factory(private val repository: MarketplaceRepository = MarketplaceRepository()) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(CommunityViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            return CommunityViewModel(repository) as T
        }
    }
}
