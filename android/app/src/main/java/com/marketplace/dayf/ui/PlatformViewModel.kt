package com.marketplace.dayf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.marketplace.dayf.data.ApiException
import com.marketplace.dayf.data.CreatePostRequest
import com.marketplace.dayf.data.GeoNode
import com.marketplace.dayf.data.NeighborhoodMembership
import com.marketplace.dayf.data.NeighborhoodPost
import com.marketplace.dayf.data.PlatformRepository
import com.marketplace.dayf.data.PostComment
import com.marketplace.dayf.data.SecureSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppStage { LOADING, SIGN_IN, ONBOARDING, HOME, ERROR }
enum class HomeTab { NEIGHBORHOOD, MARKET, CREATE, REAL_ESTATE, DIRECTORY }

data class DayfUiState(
    val stage: AppStage = AppStage.LOADING,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val geoRoot: GeoNode? = null,
    val selectedGovernorate: GeoNode? = null,
    val selectedCity: GeoNode? = null,
    val selectedNeighborhood: GeoNode? = null,
    val membership: NeighborhoodMembership? = null,
    val posts: List<NeighborhoodPost> = emptyList(),
    val isLoadingFeed: Boolean = false,
    val activeTab: HomeTab = HomeTab.NEIGHBORHOOD,
    val selectedPost: NeighborhoodPost? = null,
    val comments: List<PostComment> = emptyList(),
    val isLoadingComments: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val isSubmittingPost: Boolean = false
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
                val membership = repository.myNeighborhood()
                val geoRoot = runCatching { repository.geoTree() }.getOrNull()
                mutableUiState.update {
                    it.copy(
                        stage = AppStage.HOME,
                        isBusy = false,
                        errorMessage = null,
                        geoRoot = geoRoot,
                        membership = membership,
                        activeTab = HomeTab.NEIGHBORHOOD,
                        selectedPost = null
                    )
                }
                refreshFeed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is ApiException && failure.statusCode == 404) {
                    loadGeoForOnboarding()
                } else if (failure is ApiException && failure.statusCode == 401) {
                    sessionStore.clear()
                    mutableUiState.value = DayfUiState(
                        stage = AppStage.SIGN_IN,
                        errorMessage = failure.message
                    )
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

    fun setTab(tab: HomeTab) = mutableUiState.update {
        it.copy(
            activeTab = tab,
            selectedPost = if (tab == HomeTab.NEIGHBORHOOD) it.selectedPost else null,
            errorMessage = null
        )
    }

    fun refreshFeed() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingFeed = true, errorMessage = null) }
            try {
                val page = repository.feed()
                mutableUiState.update {
                    it.copy(stage = AppStage.HOME, isLoadingFeed = false, posts = page.content)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is ApiException && failure.statusCode == 401) {
                    sessionStore.clear()
                    mutableUiState.update {
                        it.copy(stage = AppStage.SIGN_IN, isLoadingFeed = false, errorMessage = failure.message)
                    }
                } else {
                    mutableUiState.update {
                        it.copy(isLoadingFeed = false, errorMessage = failure.message ?: "تعذّر تحميل المنشورات.")
                    }
                }
            }
        }
    }

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
                mutableUiState.update {
                    it.copy(errorMessage = failure.message ?: "تعذّر تحديث التفاعل.")
                }
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
                    CreatePostRequest(
                        locationId = locationId,
                        category = category,
                        title = title.trim(),
                        body = body.trim()
                    )
                )
                mutableUiState.update {
                    it.copy(isSubmittingPost = false, activeTab = HomeTab.NEIGHBORHOOD, selectedPost = null)
                }
                refreshFeed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.update {
                    it.copy(isSubmittingPost = false, errorMessage = failure.message ?: "تعذّر نشر المنشور.")
                }
            }
        }
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
                mutableUiState.update {
                    it.copy(isBusy = false, errorMessage = failure.message ?: "تعذّر طلب التوثيق.")
                }
            }
        }
    }

    fun showError(message: String) = mutableUiState.update { it.copy(errorMessage = message) }
}
