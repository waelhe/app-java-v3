package com.marketplace.android.core.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.marketplace.android.core.model.CreateCommentRequest
import com.marketplace.android.core.model.CreatePostRequest
import com.marketplace.android.core.model.EmailOnlyRequest
import com.marketplace.android.core.model.GeoNodeDto
import com.marketplace.android.core.model.NeighborhoodJoinRequest
import com.marketplace.android.core.model.NeighborhoodMembershipDto
import com.marketplace.android.core.model.PostCommentDto
import com.marketplace.android.core.model.PostDto
import com.marketplace.android.core.model.RegisterRequest
import com.marketplace.android.core.model.RequestMediaUpload
import com.marketplace.android.core.model.UserProfileDto
import com.marketplace.android.core.network.MarketplaceApiClient
import com.marketplace.android.core.network.streamingBody
import kotlinx.coroutines.CancellationException

class SelectedPhoto(
    val uri: Uri,
    val contentType: String,
    val sizeBytes: Long,
    val displayName: String
)

class CommunityRepository(
    private val client: MarketplaceApiClient,
    private val contentResolver: ContentResolver
) {
    private val api = client.api

    suspend fun myProfile(): UserProfileDto = api.myProfile()

    suspend fun resendEmailVerification(email: String) =
        api.resendEmailVerification(EmailOnlyRequest(email.trim()))

    suspend fun suggestNeighborhoods(query: String): List<GeoNodeDto> =
        api.suggestNeighborhoods(query).filter { it.level == 3 && !it.id.isNullOrBlank() }

    suspend fun myNeighborhood(): NeighborhoodMembershipDto = api.myNeighborhood()

    suspend fun joinNeighborhood(locationId: String): NeighborhoodMembershipDto =
        api.joinNeighborhood(NeighborhoodJoinRequest(locationId))

    suspend fun leaveNeighborhood() = api.leaveNeighborhood()

    suspend fun feed(): List<PostDto> = api.feed().content

    suspend fun searchPosts(query: String): List<PostDto> =
        api.searchPosts(query = query.trim()).content

    suspend fun comments(postId: String): List<PostCommentDto> =
        api.comments(postId).content

    suspend fun createComment(postId: String, body: String): PostCommentDto =
        api.createComment(postId, CreateCommentRequest(body.trim()))

    suspend fun toggleReaction(post: PostDto) {
        if (post.reactedByMe) api.removeReaction(post.id) else api.react(post.id)
    }

    suspend fun register(email: String, password: String, displayName: String) =
        api.register(RegisterRequest(email.trim(), password, displayName.trim()))

    fun photoDetails(uri: Uri): SelectedPhoto? {
        val mimeType = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: return null
        var size = -1L
        var displayName = "photo"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size <= 0L) {
            val descriptor = contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            descriptor.use { size = it.length }
        }
        if (size <= 0L) return null
        return SelectedPhoto(uri, mimeType, size, displayName)
    }

    suspend fun createPost(
        locationId: String,
        category: String,
        title: String,
        body: String,
        photo: SelectedPhoto?
    ): Pair<PostDto, Boolean> {
        val post = api.createPost(CreatePostRequest(locationId, category, title.trim(), body.trim()))
        if (photo == null) return post to true

        // The media contract requires the post to exist first, then a signed PUT,
        // then an explicit server-side completion/HeadObject verification.
        return try {
            val upload = api.requestMediaUpload(
                RequestMediaUpload(
                    postId = post.id,
                    contentType = photo.contentType,
                    sizeBytes = photo.sizeBytes
                )
            )
            val bodyStream = streamingBody(photo.contentType, photo.sizeBytes) {
                contentResolver.openInputStream(photo.uri)
            }
            client.putPresignedObject(upload.uploadUrl, bodyStream)
            api.completeMediaUpload(upload.mediaId)
            post to true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The post is already created; report partial completion honestly.
            post to false
        }
    }
}
