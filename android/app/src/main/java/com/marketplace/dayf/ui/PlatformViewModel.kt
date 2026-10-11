package com.marketplace.dayf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marketplace.dayf.data.ApiException
import com.marketplace.dayf.data.CreateEventRequest
import com.marketplace.dayf.data.CreateMarketItemRequest
import com.marketplace.dayf.data.CreatePollRequest
import com.marketplace.dayf.data.CreatePostRequest
import com.marketplace.dayf.data.GeoNode
import com.marketplace.dayf.data.InstitutionEntry
import com.marketplace.dayf.data.NeighborhoodEvent
import com.marketplace.dayf.data.NeighborhoodGroup
import com.marketplace.dayf.data.NeighborhoodPoll
import com.marketplace.dayf.data.NotificationItem
import com.marketplace.dayf.data.ListingDetail
import com.marketplace.dayf.data.ListingSummary
import com.marketplace.dayf.data.MarketCategoryLabels
import com.marketplace.dayf.data.NeighborhoodMarketItem
import com.marketplace.dayf.data.NeighborhoodMembership
import com.marketplace.dayf.data.NeighborhoodPost
import com.marketplace.dayf.data.PlatformRepository
import com.marketplace.dayf.data.PostComment
import com.marketplace.dayf.data.SecureSessionStore
import com.marketplace.dayf.data.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class AppStage { LOADING, SIGN_IN, ONBOARDING, HOME, ERROR }
enum class HomeTab { NEIGHBORHOOD, MARKET, CREATE, REAL_ESTATE, DIRECTORY }
enum class CommunitySection { FEED, EVENTS, GROUPS, POLLS, NOTIFICATIONS }

data class DayfUiState(
    val stage: AppStage = AppStage.LOADING,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val geoRoot: GeoNode? = null,
    val selectedGovernorate: GeoNode? = null,
    val selectedCity: GeoNode? = null,
    val selectedNeighborhood: GeoNode? = null,
    val membership: NeighborhoodMembership? = null,
    val myProfile: UserProfile? = null,
    val posts: List<NeighborhoodPost> = emptyList(),
    val isLoadingFeed: Boolean = false,
    val activeTab: HomeTab = HomeTab.NEIGHBORHOOD,
    val communitySection: CommunitySection = CommunitySection.FEED,
    val events: List<NeighborhoodEvent> = emptyList(),
    val isLoadingEvents: Boolean = false,
    val isSubmittingEvent: Boolean = false,
    val eventCreateCompleted: Boolean = false,
    val groups: List<NeighborhoodGroup> = emptyList(),
    val isLoadingGroups: Boolean = false,
    val polls: List<NeighborhoodPoll> = emptyList(),
    val isLoadingPolls: Boolean = false,
    val isSubmittingPoll: Boolean = false,
    val pollCreateCompleted: Boolean = false,
    val notifications: List<NotificationItem> = emptyList(),
    val unreadNotificationCount: Long = 0,
    val isLoadingNotifications: Boolean = false,
    val institutions: List<InstitutionEntry> = emptyList(),
    val isLoadingInstitutions: Boolean = false,
    val selectedPost: NeighborhoodPost? = null,
    val comments: List<PostComment> = emptyList(),
    val isLoadingComments: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val isSubmittingPost: Boolean = false,
    val isUploadingPhoto: Boolean = false,
    val marketItems: List<NeighborhoodMarketItem> = emptyList(),
    val isLoadingMarket: Boolean = false,
    val marketQuery: String = "",
    val marketCategory: String? = null,
    val marketMineOnly: Boolean = false,
    val isSubmittingMarketItem: Boolean = false,
    val marketCreateCompleted: Boolean = false,
    val listingResults: List<ListingSummary> = emptyList(),
    val isLoadingListings: Boolean = false,
    val listingQuery: String = "",
    val selectedListing: ListingDetail? = null,
    val isLoadingListingDetail: Boolean = false
)

class PlatformViewModel(
    private val repository: PlatformRepository,
    private val sessionStore: SecureSessionStore
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DayfUiState())
    val uiState: StateFlow<DayfUiState> = mutableUiState.asStateFlow()

    fun refreshJourney() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null) }
            if (sessionStore.accessToken() == null) {
                mutableUiState.value = DayfUiState(stage = AppStage.SIGN_IN)
                return@launch
            }

            try {
                val profile = repository.myProfile()
                val membership = repository.myNeighborhood()
                val root = runCatching { repository.geoTree() }.getOrNull()
                mutableUiState.update {
                    it.copy(
                        stage = AppStage.HOME,
                        isBusy = false,
                        errorMessage = null,
                        geoRoot = root,
                        membership = membership,
                        myProfile = profile,
                        activeTab = HomeTab.NEIGHBORHOOD,
                        selectedPost = null,
                        selectedListing = null
                    )
                }
                refreshUnreadNotificationCount()
                refreshFeed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is ApiException && failure.statusCode == 404) {
                    loadGeoForOnboarding()
                } else if (failure is ApiException && failure.statusCode == 401) {
                    sessionStore.clear()
                    mutableUiState.value = DayfUiState(stage = AppStage.SIGN_IN, errorMessage = failure.message)
                } else {
                    mutableUiState.update {
                        it.copy(
                            stage = AppStage.ERROR,
                            isBusy = false,
                            errorMessage = failure.message ?: "تعذّر الاتصال بالخادم."
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadGeoForOnboarding() {
        try {
            val root = repository.geoTree()
            mutableUiState.update {
                it.copy(
                    stage = AppStage.ONBOARDING,
                    isBusy = false,
                    errorMessage = null,
                    geoRoot = root,
                    selectedGovernorate = null,
                    selectedCity = null,
                    selectedNeighborhood = null,
                    membership = null
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableUiState.update {
                it.copy(
                    stage = AppStage.ERROR,
                    isBusy = false,
                    errorMessage = failure.message ?: "تعذّر تحميل المناطق."
                )
            }
        }
    }

    fun selectGovernorate(node: GeoNode) = mutableUiState.update {
        it.copy(selectedGovernorate = node, selectedCity = null, selectedNeighborhood = null, errorMessage = null)
    }

    fun selectCity(node: GeoNode) = mutableUiState.update {
        it.copy(selectedCity = node, selectedNeighborhood = null, errorMessage = null)
    }

    fun selectNeighborhood(node: GeoNode) = mutableUiState.update {
        it.copy(selectedNeighborhood = node, errorMessage = null)
    }

    fun joinSelectedNeighborhood() {
        val neighborhood = mutableUiState.value.selectedNeighborhood ?: return
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null) }
            try {
                repository.joinNeighborhood(neighborhood.id)
                refreshJourney()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.update {
                    it.copy(isBusy = false, errorMessage = failure.message ?: "تعذّر الانضمام إلى الحي.")
                }
            }
        }
    }

    fun setTab(tab: HomeTab) {
        mutableUiState.update {
            it.copy(
                activeTab = tab,
                selectedPost = null,
                selectedListing = null,
                errorMessage = null
            )
        }
        when (tab) {
            HomeTab.NEIGHBORHOOD -> refreshCommunitySection()
            HomeTab.MARKET -> refreshMarket()
            HomeTab.REAL_ESTATE -> searchListings(mutableUiState.value.listingQuery)
            HomeTab.DIRECTORY -> refreshInstitutions()
            HomeTab.CREATE -> Unit
        }
    }

    fun selectCommunitySection(section: CommunitySection) {
        mutableUiState.update { it.copy(communitySection = section, errorMessage = null) }
        when (section) {
            CommunitySection.FEED -> refreshFeed()
            CommunitySection.EVENTS -> refreshEvents()
            CommunitySection.GROUPS -> refreshGroups()
            CommunitySection.POLLS -> refreshPolls()
            CommunitySection.NOTIFICATIONS -> refreshNotifications()
        }
    }

    private fun refreshCommunitySection() {
        when (mutableUiState.value.communitySection) {
            CommunitySection.FEED -> refreshFeed()
            CommunitySection.EVENTS -> refreshEvents()
            CommunitySection.GROUPS -> refreshGroups()
            CommunitySection.POLLS -> refreshPolls()
            CommunitySection.NOTIFICATIONS -> refreshNotifications()
        }
    }

    fun submitEvent(
        category: String,
        title: String,
        description: String,
        startsAt: String,
        locationLabel: String,
        organizerLabel: String
    ) {
        val locationId = mutableUiState.value.membership?.locationId
        if (locationId == null) {
            mutableUiState.update { it.copy(errorMessage = "اختر حيّك أولًا.") }
            return
        }
        if (title.isBlank() || description.isBlank() || locationLabel.isBlank() || organizerLabel.isBlank()) {
            mutableUiState.update { it.copy(errorMessage = "أكمل عنوان الفعالية ووصفها ومكانها والجهة المنظمة.") }
            return
        }
        val start = runCatching { Instant.parse(startsAt) }.getOrNull()
        if (start == null || !start.isAfter(Instant.now())) {
            mutableUiState.update { it.copy(errorMessage = "اختر موعدًا مستقبليًا صالحًا.") }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmittingEvent = true, errorMessage = null) }
            try {
                repository.createEvent(
                    CreateEventRequest(
                        locationId = locationId,
                        category = category,
                        title = title.trim(),
                        description = description.trim(),
                        startsAt = start.toString(),
                        endsAt = null,
                        locationLabel = locationLabel.trim(),
                        organizerLabel = organizerLabel.trim(),
                        capacity = null,
                        registration = "OPEN"
                    )
                )
                val events = repository.events().content
                mutableUiState.update {
                    it.copy(
                        events = events,
                        isSubmittingEvent = false,
                        eventCreateCompleted = true,
                        communitySection = CommunitySection.EVENTS,
                        errorMessage = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isSubmittingEvent = false, errorMessage = failure.message ?: "تعذّر نشر الفعالية.")
                }
            }
        }
    }

    fun acknowledgeEventCreate() = mutableUiState.update { it.copy(eventCreateCompleted = false) }

    fun refreshEvents() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingEvents = true, errorMessage = null) }
            try {
                val events = repository.events().content
                mutableUiState.update { it.copy(events = events, isLoadingEvents = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(isLoadingEvents = false, errorMessage = failure.message ?: "تعذّر تحميل الفعاليات.") }
            }
        }
    }

    fun toggleRsvp(event: NeighborhoodEvent) {
        viewModelScope.launch {
            try {
                if (event.rsvpedByMe) repository.unrsvp(event.id) else repository.rsvp(event.id)
                val events = repository.events().content
                mutableUiState.update { it.copy(events = events, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر تحديث الحضور.") }
            }
        }
    }

    fun refreshGroups() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingGroups = true, errorMessage = null) }
            try {
                val groups = repository.groups().content
                mutableUiState.update { it.copy(groups = groups, isLoadingGroups = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(isLoadingGroups = false, errorMessage = failure.message ?: "تعذّر تحميل المجموعات.") }
            }
        }
    }

    fun toggleGroupMembership(group: NeighborhoodGroup) {
        viewModelScope.launch {
            try {
                if (group.joinedByMe) repository.leaveGroup(group.id) else repository.joinGroup(group.id)
                val groups = repository.groups().content
                mutableUiState.update { it.copy(groups = groups, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر تحديث عضوية المجموعة.") }
            }
        }
    }

    fun submitPoll(question: String, options: List<String>) {
        val locationId = mutableUiState.value.membership?.locationId
        if (locationId == null) {
            mutableUiState.update { it.copy(errorMessage = "اختر حيّك أولًا.") }
            return
        }
        val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }
        if (question.isBlank() || question.length > 200 || cleanOptions.size !in 2..5 ||
            cleanOptions.any { it.length > 200 } || cleanOptions.distinct().size != cleanOptions.size) {
            mutableUiState.update {
                it.copy(errorMessage = "أدخل سؤالًا وخيارين إلى خمسة خيارات مختلفة، وكل واحد بحد أقصى 200 حرف.")
            }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmittingPoll = true, errorMessage = null) }
            try {
                repository.createPoll(
                    CreatePollRequest(
                        locationId = locationId,
                        question = question.trim(),
                        authorLabel = "أعضاء الحي",
                        options = cleanOptions
                    )
                )
                val polls = repository.polls().content
                mutableUiState.update {
                    it.copy(
                        polls = polls,
                        isSubmittingPoll = false,
                        pollCreateCompleted = true,
                        communitySection = CommunitySection.POLLS,
                        errorMessage = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isSubmittingPoll = false, errorMessage = failure.message ?: "تعذّر نشر الاستطلاع.")
                }
            }
        }
    }

    fun acknowledgePollCreate() = mutableUiState.update { it.copy(pollCreateCompleted = false) }

    fun refreshPolls() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingPolls = true, errorMessage = null) }
            try {
                val polls = repository.polls().content
                mutableUiState.update { it.copy(polls = polls, isLoadingPolls = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(isLoadingPolls = false, errorMessage = failure.message ?: "تعذّر تحميل الاستطلاعات.") }
            }
        }
    }

    fun voteOnPoll(poll: NeighborhoodPoll, optionId: String) {
        if (poll.votedByMe != null) return
        viewModelScope.launch {
            try {
                repository.vote(poll.id, optionId)
                mutableUiState.update { it.copy(polls = repository.polls().content, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر تسجيل التصويت.") }
            }
        }
    }

    fun withdrawPollVote(poll: NeighborhoodPoll) {
        if (poll.votedByMe == null) return
        viewModelScope.launch {
            try {
                repository.withdrawVote(poll.id)
                mutableUiState.update { it.copy(polls = repository.polls().content, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر سحب التصويت.") }
            }
        }
    }

    fun refreshNotifications() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingNotifications = true, errorMessage = null) }
            try {
                val page = repository.notifications()
                val count = repository.unreadNotificationCount().unreadCount
                mutableUiState.update {
                    it.copy(notifications = page.content, unreadNotificationCount = count, isLoadingNotifications = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(isLoadingNotifications = false, errorMessage = failure.message ?: "تعذّر تحميل الإشعارات.") }
            }
        }
    }

    fun markNotificationRead(notification: NotificationItem) {
        if (notification.read) return
        viewModelScope.launch {
            try {
                repository.markNotificationRead(notification.id)
                refreshNotifications()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر تحديث الإشعار.") }
            }
        }
    }

    fun refreshInstitutions() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingInstitutions = true, errorMessage = null) }
            try {
                val institutions = repository.institutions().content
                mutableUiState.update { it.copy(institutions = institutions, isLoadingInstitutions = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.update { it.copy(isLoadingInstitutions = false, errorMessage = failure.message ?: "تعذّر تحميل سجل الجهات.") }
            }
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingFeed = true, errorMessage = null) }
            try {
                val page = repository.feed()
                mutableUiState.update {
                    val selectedId = it.selectedPost?.id
                    it.copy(
                        stage = AppStage.HOME,
                        isLoadingFeed = false,
                        posts = page.content,
                        selectedPost = selectedId?.let { id -> page.content.firstOrNull { post -> post.id == id } }
                            ?: it.selectedPost
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isLoadingFeed = false, errorMessage = failure.message ?: "تعذّر تحميل المنشورات.")
                }
            }
        }
    }

    fun canUploadPhoto(post: NeighborhoodPost): Boolean =
        mutableUiState.value.myProfile?.id == post.authorId

    fun openPost(post: NeighborhoodPost) {
        mutableUiState.update { it.copy(selectedPost = post, comments = emptyList(), isLoadingComments = true) }
        loadComments(post.id)
    }

    fun closePost() = mutableUiState.update {
        it.copy(selectedPost = null, comments = emptyList(), isLoadingComments = false, errorMessage = null)
    }

    fun loadComments(postId: String) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingComments = true, errorMessage = null) }
            try {
                val comments = repository.comments(postId).content
                mutableUiState.update {
                    it.copy(
                        isLoadingComments = false,
                        comments = comments,
                        selectedPost = it.posts.firstOrNull { post -> post.id == postId } ?: it.selectedPost
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isLoadingComments = false, errorMessage = failure.message ?: "تعذّر تحميل التعليقات.")
                }
            }
        }
    }

    fun submitComment(body: String) {
        val post = mutableUiState.value.selectedPost ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmittingComment = true, errorMessage = null) }
            try {
                repository.addComment(post.id, body.trim())
                val comments = repository.comments(post.id).content
                mutableUiState.update { it.copy(isSubmittingComment = false, comments = comments) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isSubmittingComment = false, errorMessage = failure.message ?: "تعذّر إرسال التعليق.")
                }
            }
        }
    }

    fun toggleReaction(post: NeighborhoodPost) {
        viewModelScope.launch {
            try {
                if (post.reactedByMe) repository.removeReaction(post.id) else repository.react(post.id)
                val updated = repository.feed().content
                mutableUiState.update {
                    it.copy(
                        posts = updated,
                        selectedPost = if (it.selectedPost?.id == post.id) {
                            updated.firstOrNull { item -> item.id == post.id }
                        } else it.selectedPost
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر تحديث التفاعل.") }
            }
        }
    }

    fun uploadPostPhoto(postId: String, contentType: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            mutableUiState.update { it.copy(errorMessage = "الصورة المحددة فارغة.") }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isUploadingPhoto = true, errorMessage = null) }
            try {
                repository.uploadPostPhoto(postId, contentType, bytes)
                val page = repository.feed()
                mutableUiState.update {
                    it.copy(
                        isUploadingPhoto = false,
                        posts = page.content,
                        selectedPost = page.content.firstOrNull { post -> post.id == postId } ?: it.selectedPost
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isUploadingPhoto = false, errorMessage = failure.message ?: "تعذّر إرفاق الصورة.")
                }
            }
        }
    }

    private fun refreshUnreadNotificationCount() {
        viewModelScope.launch {
            try {
                val count = repository.unreadNotificationCount().unreadCount
                mutableUiState.update { it.copy(unreadNotificationCount = count) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Badge refresh must not block the main feed journey.
            }
        }
    }

    fun submitPost(category: String, title: String, body: String) {
        val locationId = mutableUiState.value.membership?.locationId ?: return
        if (title.isBlank() || body.isBlank()) {
            mutableUiState.update { it.copy(errorMessage = "أدخل عنوان المنشور ومحتواه.") }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmittingPost = true, errorMessage = null) }
            try {
                repository.createPost(
                    CreatePostRequest(locationId, category, title.trim(), body.trim())
                )
                mutableUiState.update {
                    it.copy(isSubmittingPost = false, activeTab = HomeTab.NEIGHBORHOOD, selectedPost = null)
                }
                refreshFeed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isSubmittingPost = false, errorMessage = failure.message ?: "تعذّر نشر المنشور.")
                }
            }
        }
    }

    fun refreshMarket(
        query: String = mutableUiState.value.marketQuery,
        category: String? = mutableUiState.value.marketCategory,
        mineOnly: Boolean = mutableUiState.value.marketMineOnly
    ) {
        mutableUiState.update {
            it.copy(
                isLoadingMarket = true,
                errorMessage = null,
                marketQuery = query,
                marketCategory = category,
                marketMineOnly = mineOnly
            )
        }
        viewModelScope.launch {
            try {
                val items = repository.market(category, query, mineOnly).content
                mutableUiState.update { it.copy(isLoadingMarket = false, marketItems = items) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isLoadingMarket = false, errorMessage = failure.message ?: "تعذّر تحميل سوق الحي.")
                }
            }
        }
    }

    fun createMarketItem(
        category: String,
        title: String,
        condition: String,
        priceCents: Int?,
        currency: String?,
        locationLabel: String
    ) {
        val membership = mutableUiState.value.membership ?: return
        if (title.isBlank() || locationLabel.isBlank()) {
            mutableUiState.update { it.copy(errorMessage = "أدخل عنوان الغرض ومكان الاستلام.") }
            return
        }
        if (category == "FREE") {
            if (priceCents != null || !currency.isNullOrBlank()) {
                mutableUiState.update { it.copy(errorMessage = "الغرض المجاني لا يحمل سعرًا أو عملة.") }
                return
            }
        } else if (priceCents == null || priceCents <= 0 || currency.isNullOrBlank()) {
            mutableUiState.update { it.copy(errorMessage = "أدخل سعرًا موجبًا ورمز عملة ISO صالحًا.") }
            return
        }

        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmittingMarketItem = true, errorMessage = null) }
            try {
                repository.createMarketItem(
                    CreateMarketItemRequest(
                        locationId = membership.locationId,
                        category = category,
                        title = title.trim(),
                        condition = condition,
                        priceCents = priceCents,
                        priceCurrency = currency?.trim()?.uppercase()?.takeIf { category != "FREE" },
                        locationLabel = locationLabel.trim()
                    )
                )
                mutableUiState.update { it.copy(isSubmittingMarketItem = false, marketCreateCompleted = true) }
                refreshMarket()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isSubmittingMarketItem = false, errorMessage = failure.message ?: "تعذّر نشر الغرض.")
                }
            }
        }
    }

    fun acknowledgeMarketCreate() = mutableUiState.update { it.copy(marketCreateCompleted = false) }

    fun withdrawMarketItem(item: NeighborhoodMarketItem) {
        if (!item.mine) return
        viewModelScope.launch {
            try {
                repository.withdrawMarketItem(item.id)
                refreshMarket()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update { it.copy(errorMessage = failure.message ?: "تعذّر سحب الإعلان.") }
            }
        }
    }

    fun searchListings(query: String = mutableUiState.value.listingQuery) {
        mutableUiState.update {
            it.copy(isLoadingListings = true, listingQuery = query, selectedListing = null, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                val results = repository.searchListings(query).content
                mutableUiState.update { it.copy(isLoadingListings = false, listingResults = results) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.update {
                    it.copy(isLoadingListings = false, errorMessage = failure.message ?: "تعذّر البحث عن العقارات والخدمات.")
                }
            }
        }
    }

    fun openListing(listing: ListingSummary) {
        mutableUiState.update { it.copy(selectedListing = null, isLoadingListingDetail = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val detail = repository.listingDetail(listing.id)
                mutableUiState.update { it.copy(selectedListing = detail, isLoadingListingDetail = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.update {
                    it.copy(isLoadingListingDetail = false, errorMessage = failure.message ?: "تعذّر تحميل تفاصيل الإعلان.")
                }
            }
        }
    }

    fun closeListing() = mutableUiState.update {
        it.copy(selectedListing = null, isLoadingListingDetail = false, errorMessage = null)
    }

    fun requestVerification() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, errorMessage = null) }
            try {
                val membership = repository.requestNeighborhoodVerification()
                mutableUiState.update {
                    it.copy(isBusy = false, membership = membership, errorMessage = "تم إرسال طلب توثيق العضوية.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleProtectedFailure(failure)
                mutableUiState.update {
                    it.copy(isBusy = false, errorMessage = failure.message ?: "تعذّر طلب التوثيق.")
                }
            }
        }
    }

    private fun handleProtectedFailure(failure: Exception) {
        if (failure is ApiException && failure.statusCode == 401) {
            sessionStore.clear()
            mutableUiState.update { it.copy(stage = AppStage.SIGN_IN, errorMessage = failure.message) }
        }
    }

    fun showError(message: String) = mutableUiState.update { it.copy(errorMessage = message) }
}
