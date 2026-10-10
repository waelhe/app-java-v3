package com.marketplace.dayf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marketplace.dayf.data.GeoNode
import com.marketplace.dayf.data.NeighborhoodPost
import com.marketplace.dayf.data.PostCategoryLabels

@Composable
fun FeedScreen(state: DayfUiState, viewModel: PlatformViewModel, padding: PaddingValues, onSignOut: () -> Unit) {
    val neighborhoodName = remember(state.geoRoot, state.membership?.locationId) {
        findLocationName(state.geoRoot, state.membership?.locationId) ?: "مجتمعك المحلي"
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ضَيف", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(neighborhoodName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                Row {
                    IconButton(onClick = viewModel::refreshFeed) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "تحديث الخلاصة")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Outlined.Logout, contentDescription = "تسجيل الخروج من هذا الجهاز")
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أهلاً بك بين جيرانك", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("سؤال، توصية، طلب مساعدة أو خبر محلي؛ ابدأ بما يفيد مجتمعك.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f))
                    FilledTonalButton(onClick = { viewModel.setTab(HomeTab.CREATE) }) {
                        Text("شارك شيئًا مع الحي")
                    }
                }
            }
        }
        if (state.membership?.verificationState == "UNVERIFIED") {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("عضويتك غير موثقة بعد", fontWeight = FontWeight.SemiBold)
                            Text("اطلب مراجعة يدوية لإثبات انتمائك إلى الحي.", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = viewModel::requestVerification, enabled = !state.isBusy) {
                            Text("طلب التوثيق")
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("منشورات الحي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("المحتوى الفعلي من الخادم", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = viewModel::refreshFeed) { Text("تحديث") }
            }
        }
        if (!state.errorMessage.isNullOrBlank()) item { ErrorMessage(state.errorMessage) }
        if (state.isLoadingFeed) {
            item {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        } else if (state.posts.isEmpty()) {
            item {
                EmptyState(
                    title = "لا توجد منشورات ظاهرة حتى الآن",
                    body = "ابدأ بمشاركة سؤال أو توصية. ستظهر هنا المنشورات المرئية في حيّك.",
                    action = "إنشاء أول منشور",
                    onAction = { viewModel.setTab(HomeTab.CREATE) }
                )
            }
        } else {
            items(state.posts, key = { it.id }) { post ->
                PostCard(
                    post = post,
                    onOpen = { viewModel.openPost(post) },
                    onReact = { viewModel.toggleReaction(post) }
                )
            }
        }
    }
}

@Composable
private fun PostCard(post: NeighborhoodPost, onOpen: () -> Unit, onReact: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandAvatar()
                Column(Modifier.weight(1f)) {
                    Text("عضو من الحي", fontWeight = FontWeight.SemiBold)
                    Text(post.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Text(
                        PostCategoryLabels.arabic(post.category),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(post.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(post.body, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) {
                    Icon(Icons.Outlined.Forum, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("التعليقات")
                }
                TextButton(onClick = onReact) {
                    Icon(
                        Icons.Outlined.ThumbUp,
                        contentDescription = if (post.reactedByMe) "إزالة التفاعل" else "تفاعل مع المنشور",
                        tint = if (post.reactedByMe) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(post.reactionsCount.toString())
                }
            }
        }
    }
}

@Composable
fun PostDetailScreen(
    state: DayfUiState,
    post: NeighborhoodPost,
    viewModel: PlatformViewModel,
    padding: PaddingValues
) {
    var commentText by remember(post.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closePost) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "العودة إلى الخلاصة")
            }
            Text("تفاصيل المنشور", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(PostCategoryLabels.arabic(post.category), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                        Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(post.body, style = MaterialTheme.typography.bodyLarge)
                        Text(post.createdAt.take(10), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.toggleReaction(post) }) {
                            Icon(Icons.Outlined.ThumbUp, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (post.reactedByMe) "إزالة التفاعل (" + post.reactionsCount + ")" else "تفاعل (" + post.reactionsCount + ")")
                        }
                    }
                }
            }
            item { Text("التعليقات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (state.isLoadingComments) {
                item { androidx.compose.material3.CircularProgressIndicator(Modifier.padding(12.dp)) }
            } else if (state.comments.isEmpty()) {
                item { Text("لا توجد تعليقات بعد. ابدأ حوارًا مفيدًا.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.comments, key = { it.id }) { comment ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("تعليق من عضو", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(4.dp))
                            Text(comment.body)
                            Spacer(Modifier.height(4.dp))
                            Text(comment.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!state.errorMessage.isNullOrBlank()) item { ErrorMessage(state.errorMessage) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                modifier = Modifier.weight(1f),
                label = { Text("اكتب تعليقًا") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            Button(
                onClick = {
                    viewModel.submitComment(commentText)
                    commentText = ""
                },
                enabled = commentText.isNotBlank() && !state.isSubmittingComment
            ) {
                Text(if (state.isSubmittingComment) "..." else "إرسال")
            }
        }
    }
}

@Composable
fun CreatePostScreen(state: DayfUiState, viewModel: PlatformViewModel, padding: PaddingValues) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("GENERAL") }
    var expanded by remember { mutableStateOf(false) }
    val categories = listOf(
        "GENERAL" to "حديث عام",
        "QUESTION" to "سؤال",
        "REQUEST" to "طلب مساعدة",
        "RECOMMENDATION" to "توصية",
        "LOST_FOUND" to "مفقودات",
        "CLASSIFIED" to "إعلان"
    )
    Column(
        Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("شارك مع الحي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("سينشر المحتوى في الحي الذي اخترته فقط.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(categories.first { it.first == category }.second)
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                categories.forEach { item ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(item.second) },
                        onClick = { category = item.first; expanded = false }
                    )
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= 200) title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان المنشور") },
            supportingText = { Text(title.length.toString() + "/200") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )
        OutlinedTextField(
            value = body,
            onValueChange = { if (it.length <= 2000) body = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("ما الذي تريد مشاركته؟") },
            placeholder = { Text("اكتب التفاصيل التي تساعد جيرانك...") },
            supportingText = { Text(body.length.toString() + "/2000") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text
            )
        )
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        Button(
            onClick = { viewModel.submitPost(category, title, body) },
            enabled = title.isNotBlank() && body.isNotBlank() && !state.isSubmittingPost,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (state.isSubmittingPost) "جارٍ النشر..." else "نشر في الحي")
        }
    }
}

private fun findLocationName(root: GeoNode?, id: String?): String? {
    if (root == null || id == null) return null
    if (root.id == id) return root.nameAr
    root.children.forEach { child -> findLocationName(child, id)?.let { return it } }
    return null
}
