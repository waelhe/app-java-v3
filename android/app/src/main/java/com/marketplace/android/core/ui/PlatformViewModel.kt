package com.marketplace.android.core.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marketplace.android.BuildConfig
import com.marketplace.android.core.auth.SecureTokenStore
import com.marketplace.android.core.data.CommunityRepository
import com.marketplace.android.core.data.SelectedPhoto
import com.marketplace.android.core.model.GeoNodeDto
import com.marketplace.android.core.model.NeighborhoodMembershipDto
import com.marketplace.android.core.model.PostCommentDto
import com.marketplace.android.core.model.PostDto
import com.marketplace.android.core.model.UserProfileDto
import com.marketplace.android.core.network.ApiFailureMessage
import com.marketplace.android.core.network.MarketplaceApiClient
import com.marketplace.android.core.validation.CommunityInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class CommunityUiState(
    val accessToken: String? = null,
    val profile: UserProfileDto? = null,
    val membership: NeighborhoodMembershipDto? = null,
    val membershipLoading: Boolean = false,
    val areaQuery: String = "",
    val areas: List<GeoNodeDto> = emptyList(),
    val areasLoading: Boolean = false,
    val areaError: String? = null,
    val feed: List<PostDto> = emptyList(),
    val feedLoading: Boolean = false,
    val feedError: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PostDto> = emptyList(),
    val searchLoading: Boolean = false,
    val searchSubmitted: Boolean = false,
    val composerCategory: String = "GENERAL",
    val composerTitle: String = "",
    val composerBody: String = "",
    val selectedPhotoUri: String? = null,
    val composerBusy: Boolean = false,
    val publicationRevision: Int = 0,
    val selectedPost: PostDto? = null,
    val comments: List<PostCommentDto> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentDraft: String = "",
    val detailBusy: Boolean = false,
    val registrationComplete: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null
)

class PlatformViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = SecureTokenStore(application)
    private val initialToken = tokenStore.readValidToken()
    private val client = MarketplaceApiClient(BuildConfig.BACKEND_BASE_URL) {
        tokenStore.readValidToken()
    }
    private val repository = CommunityRepository(client, application.contentResolver)
    private var selectedPhoto: SelectedPhoto? = null

    private val mutableState = MutableStateFlow(
        CommunityUiState(
            accessToken = initialToken,
            membershipLoading = initialToken != null
        )
    )
    val state: StateFlow<CommunityUiState> = mutableState.asStateFlow()

    init {
        if (initialToken != null) {
            loadProfile()
            loadNeighborhood()
        }
    }

    fun onAccessToken(token: String, expiresAtMillis: Long) {
        tokenStore.save(token, expiresAtMillis)
        mutableState.update {
            it.copy(
                accessToken = token,
                membership = null,
                membershipLoading = true,
                feed = emptyList(),
                registrationComplete = false
            )
        }
        loadProfile()
        loadNeighborhood()
    }

    fun setNotice(message: String) {
        mutableState.update { it.copy(notice = message) }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun register(displayName: String, email: String, password: String) {
        CommunityInputRules.registrationError(displayName, email, password)?.let {
            setNotice(it)
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, notice = null) }
            try {
                repository.register(email, password, displayName)
                mutableState.update {
                    it.copy(
                        busy = false,
                        registrationComplete = true,
                        notice = "أنشأنا الحساب. افتح رسالة التحقق في بريدك الإلكتروني قبل محاولة تسجيل الدخول."
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(busy = false, notice = ApiFailureMessage.from(error)) }
            }
        }
    }

    fun resendVerification(email: String) {
        if (email.isBlank() || email.length > 50) {
            setNotice("أدخل البريد الإلكتروني المستخدم في التسجيل.")
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, notice = null) }
            try {
                repository.resendEmailVerification(email)
                mutableState.update {
                    it.copy(
                        busy = false,
                        notice = "إذا كان الحساب بحاجة إلى التحقق، فسيصلك رابط جديد. افحص صندوق الوارد والبريد غير المرغوب."
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(busy = false, notice = ApiFailureMessage.from(error)) }
            }
        }
    }

    fun clearRegistrationComplete() {
        mutableState.update { it.copy(registrationComplete = false, notice = null) }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = repository.myProfile()
                mutableState.update { it.copy(profile = profile) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(notice = message) }
            }
        }
    }

    fun setAreaQuery(query: String) {
        mutableState.update { it.copy(areaQuery = query, areaError = null) }
    }

    fun findAreas(query: String) {
        val normalized = query.trim()
        if (normalized.codePointCount(0, normalized.length) < 2) {
            mutableState.update { it.copy(areas = emptyList(), areasLoading = false, areaError = null) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(areasLoading = true, areaError = null) }
            try {
                val areas = repository.suggestNeighborhoods(normalized)
                mutableState.update { it.copy(areas = areas, areasLoading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(areasLoading = false, areas = emptyList(), areaError = ApiFailureMessage.from(error))
                }
            }
        }
    }

    fun joinNeighborhood(area: GeoNodeDto) {
        val id = area.id ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, notice = null) }
            try {
                val membership = repository.joinNeighborhood(id)
                mutableState.update {
                    it.copy(
                        membership = membership,
                        busy = false,
                        areas = emptyList(),
                        areaQuery = "",
                        notice = "أصبحت عضوًا في حيّك. هذه هي آخر المشاركات الفعلية."
                    )
                }
                refreshFeed()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(busy = false, notice = message) }
            }
        }
    }

    fun changeNeighborhood() {
        mutableState.update {
            it.copy(
                membership = null,
                feed = emptyList(),
                areas = emptyList(),
                areaQuery = "",
                areaError = null
            )
        }
    }

    private fun loadNeighborhood() {
        viewModelScope.launch {
            mutableState.update { it.copy(membershipLoading = true) }
            try {
                val membership = repository.myNeighborhood()
                mutableState.update { it.copy(membership = membership, membershipLoading = false) }
                refreshFeed()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is HttpException && error.code() == 404) {
                    mutableState.update { it.copy(membership = null, membershipLoading = false) }
                } else {
                    val message = handleFailure(error)
                    mutableState.update {
                        it.copy(membershipLoading = false, notice = message)
                    }
                }
            }
        }
    }

    fun refreshFeed() {
        if (mutableState.value.accessToken == null || mutableState.value.membership == null) return
        viewModelScope.launch {
            mutableState.update { it.copy(feedLoading = true, feedError = null) }
            try {
                val posts = repository.feed()
                mutableState.update { it.copy(feed = posts, feedLoading = false, feedError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(feedLoading = false, feedError = message) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun searchPosts() {
        val query = mutableState.value.searchQuery.trim()
        CommunityInputRules.searchError(query)?.let { message ->
            mutableState.update {
                it.copy(searchSubmitted = false, searchResults = emptyList(), notice = message)
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(searchLoading = true, searchSubmitted = true, notice = null) }
            try {
                mutableState.update {
                    it.copy(searchResults = repository.searchPosts(query), searchLoading = false)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(searchLoading = false, notice = message) }
            }
        }
    }

    fun setComposerCategory(category: String) {
        mutableState.update { it.copy(composerCategory = category) }
    }

    fun setComposerTitle(title: String) {
        mutableState.update { it.copy(composerTitle = title) }
    }

    fun setComposerBody(body: String) {
        mutableState.update { it.copy(composerBody = body) }
    }

    fun selectPhoto(uri: Uri?) {
        if (uri == null) return
        val photo = runCatching { repository.photoDetails(uri) }.getOrNull()
        if (photo == null) {
            setNotice("تعذر قراءة هذه الصورة. اختر صورة أخرى من معرض الصور.")
            return
        }
        selectedPhoto = photo
        mutableState.update { it.copy(selectedPhotoUri = uri.toString(), notice = null) }
    }

    fun clearPhoto() {
        selectedPhoto = null
        mutableState.update { it.copy(selectedPhotoUri = null) }
    }

    fun publishPost() {
        val current = mutableState.value
        val locationId = current.membership?.locationId
        val title = current.composerTitle.trim()
        val body = current.composerBody.trim()
        CommunityInputRules.postError(locationId, current.composerCategory, title, body)?.let {
            setNotice(it)
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(composerBusy = true, notice = null) }
            try {
                val (createdPost, mediaCompleted) = repository.createPost(
                    locationId,
                    current.composerCategory,
                    title,
                    body,
                    selectedPhoto
                )
                selectedPhoto = null
                mutableState.update {
                    it.copy(
                        composerBusy = false,
                        publicationRevision = it.publicationRevision + 1,
                        composerTitle = "",
                        composerBody = "",
                        selectedPhotoUri = null,
                        notice = if (mediaCompleted) "نُشرت مشاركتك في الحي." else
                            "نُشرت المشاركة، لكن تعذّر إكمال رفع الصورة. افتح المشاركة وتحقق من المحتوى."
                    )
                }
                refreshFeed()
                if (!mediaCompleted) mutableState.update {
                    it.copy(selectedPost = createdPost)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update {
                    it.copy(composerBusy = false, notice = message)
                }
            }
        }
    }

    fun openPost(post: PostDto) {
        mutableState.update {
            it.copy(
                selectedPost = post,
                comments = emptyList(),
                commentsLoading = true,
                commentDraft = "",
                detailBusy = false
            )
        }
        viewModelScope.launch {
            try {
                val comments = repository.comments(post.id)
                mutableState.update { it.copy(comments = comments, commentsLoading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update {
                    it.copy(commentsLoading = false, notice = message)
                }
            }
        }
    }

    fun closePost() {
        mutableState.update {
            it.copy(selectedPost = null, comments = emptyList(), commentDraft = "", detailBusy = false)
        }
    }

    fun setCommentDraft(body: String) {
        mutableState.update { it.copy(commentDraft = body) }
    }

    fun sendComment() {
        val current = mutableState.value
        val post = current.selectedPost ?: return
        val body = current.commentDraft.trim()
        CommunityInputRules.commentError(body)?.let {
            setNotice(it)
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(detailBusy = true, notice = null) }
            try {
                repository.createComment(post.id, body)
                mutableState.update { it.copy(commentDraft = "", detailBusy = false) }
                val comments = repository.comments(post.id)
                mutableState.update { it.copy(comments = comments) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(detailBusy = false, notice = message) }
            }
        }
    }

    fun toggleReaction(post: PostDto) {
        val current = mutableState.value
        val latest = (current.feed + current.searchResults + listOfNotNull(current.selectedPost))
            .firstOrNull { it.id == post.id } ?: post
        viewModelScope.launch {
            try {
                repository.toggleReaction(latest)
                val next = latest.copy(
                    reactedByMe = !latest.reactedByMe,
                    reactionsCount = (latest.reactionsCount + if (latest.reactedByMe) -1 else 1).coerceAtLeast(0)
                )
                mutableState.update { state ->
                    state.copy(
                        feed = state.feed.map { if (it.id == next.id) next else it },
                        searchResults = state.searchResults.map { if (it.id == next.id) next else it },
                        selectedPost = if (state.selectedPost?.id == next.id) next else state.selectedPost
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                setNotice(handleFailure(error))
            }
        }
    }

    fun leaveNeighborhood() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, notice = null) }
            try {
                repository.leaveNeighborhood()
                mutableState.update {
                    it.copy(
                        busy = false,
                        membership = null,
                        feed = emptyList(),
                        notice = "غادرت الحي. اختر نطاقًا جديدًا للمتابعة."
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = handleFailure(error)
                mutableState.update { it.copy(busy = false, notice = message) }
            }
        }
    }

    fun signOut() {
        tokenStore.clear()
        selectedPhoto = null
        mutableState.value = CommunityUiState(notice = "تم تسجيل الخروج.")
    }

    private fun handleFailure(error: Throwable): String {
        if (error is HttpException && error.code() == 401) {
            tokenStore.clear()
            mutableState.update {
                it.copy(
                    accessToken = null,
                    membership = null,
                    membershipLoading = false,
                    feed = emptyList(),
                    searchResults = emptyList(),
                    selectedPost = null
                )
            }
        }
        return ApiFailureMessage.from(error)
    }
}
