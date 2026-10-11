package com.marketplace.android.core.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.marketplace.android.core.model.GeoNodeDto
import com.marketplace.android.core.model.PostCommentDto
import com.marketplace.android.core.model.PostDto
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.temporal.ChronoUnit

private enum class MainTab { HOME, SEARCH, COMPOSE, ACCOUNT }

@Composable
fun MarketplaceApp(viewModel: PlatformViewModel, onSignIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        when {
            state.accessToken == null -> WelcomeScreen(state, viewModel, onSignIn)
            state.membershipLoading -> FullScreenLoading("نجهّز مساحتك المجتمعية…")
            state.membership == null -> NeighborhoodScreen(state, viewModel)
            else -> MainShell(state, viewModel)
        }
    }
}

@Composable
private fun WelcomeScreen(state: CommunityUiState, viewModel: PlatformViewModel, onSignIn: () -> Unit) {
    var registration by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.registrationComplete) {
        if (state.registrationComplete) {
            registration = false
            password = ""
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(82.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("ض", color = MaterialTheme.colorScheme.onPrimary, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text("ضَيف", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("مجتمعك المحلي، أقرب إليك", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (registration) "إنشاء حساب جديد" else "أهلًا بك في ضَيف", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (state.registrationComplete) {
                        Text("تحقّق من بريدك الإلكتروني", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("أنشأنا الحساب للبريد $email. افتح رسالة التحقق واتبع الرابط قبل تسجيل الدخول. افحص أيضًا مجلد البريد غير المرغوب.")
                        Button(
                            onClick = { viewModel.resendVerification(email) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy
                        ) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("إعادة إرسال رابط التحقق")
                        }
                        OutlinedButton(
                            onClick = viewModel::clearRegistrationComplete,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("العودة إلى تسجيل الدخول") }
                    } else if (registration) {
                        OutlinedTextField(displayName, { displayName = it }, label = { Text("الاسم الظاهر") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                        OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                        Button(onClick = { viewModel.register(displayName, email, password) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("إنشاء الحساب")
                        }
                        TextButton(onClick = { registration = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("لدي حساب بالفعل") }
                    } else {
                        Text("تعرّف إلى ما يحدث حولك، وشارك جيرانك، واكتشف الخدمات والمعلومات المحلية من مصادرها.")
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول الآمن") }
                        OutlinedButton(onClick = { registration = true }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب") }
                    }
                    NoticeCard(state.notice, viewModel::clearNotice)
                    Text("يتم تسجيل الدخول عبر المتصفح باستخدام OAuth PKCE. لا يخزّن التطبيق كلمة المرور أو سرّ عميل OAuth.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NeighborhoodScreen(state: CommunityUiState, viewModel: PlatformViewModel) {
    var query by rememberSaveable { mutableStateOf(state.areaQuery) }
    LaunchedEffect(query) {
        viewModel.setAreaQuery(query)
        if (query.trim().length >= 2) {
            delay(350)
            viewModel.findAreas(query)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 18.dp)) {
        BrandHeader("اختر حيّك", "تبدأ التجربة من نطاق محلي واضح")
        Spacer(Modifier.height(12.dp))
        Text("ابحث باسم الحي باللغة العربية أو الإنجليزية. نعرض الأحياء الموجودة في سجل المواقع الفعلي.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("اسم الحي") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
        )
        if (state.areasLoading) ProgressRow("نبحث في سجل الأحياء…")
        state.areaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        NoticeCard(state.notice, viewModel::clearNotice)
        if (query.trim().length >= 2 && !state.areasLoading && state.areas.isEmpty() && state.areaError == null) {
            EmptyPanel("لم نجد حيًا مطابقًا", "جرّب اسمًا آخر أو جزءًا أطول من اسم الحي.")
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.areas, key = { it.id.orEmpty() }) { area ->
                AreaRow(area, state.busy) { viewModel.joinNeighborhood(area) }
            }
        }
        TextButton(onClick = viewModel::signOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.Logout, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("تسجيل الخروج")
        }
    }
}

@Composable
private fun AreaRow(area: GeoNodeDto, busy: Boolean, onChoose: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onChoose),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(area.displayName(), fontWeight = FontWeight.SemiBold)
                area.slug?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("اختيار", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MainShell(state: CommunityUiState, viewModel: PlatformViewModel) {
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.selectPhoto(uri) }
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            if (state.selectedPost == null) {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    NavigationBarItem(tab == MainTab.HOME, { tab = MainTab.HOME }, { Icon(Icons.Outlined.Home, contentDescription = null) }, label = { Text("الرئيسية") })
                    NavigationBarItem(tab == MainTab.SEARCH, { tab = MainTab.SEARCH }, { Icon(Icons.Outlined.Search, contentDescription = null) }, label = { Text("اكتشف") })
                    NavigationBarItem(tab == MainTab.COMPOSE, { tab = MainTab.COMPOSE }, { Icon(Icons.Outlined.AddCircle, contentDescription = null) }, label = { Text("مشاركة") })
                    NavigationBarItem(tab == MainTab.ACCOUNT, { tab = MainTab.ACCOUNT }, { Icon(Icons.Outlined.PersonOutline, contentDescription = null) }, label = { Text("حسابي") })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            NoticeCard(state.notice, viewModel::clearNotice, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (state.selectedPost != null) {
                PostDetailScreen(state, viewModel) { viewModel.closePost() }
            } else when (tab) {
                MainTab.HOME -> FeedScreen(state, viewModel)
                MainTab.SEARCH -> SearchScreen(state, viewModel)
                MainTab.COMPOSE -> ComposePostScreen(
                    state = state,
                    viewModel = viewModel,
                    onChoosePhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPublished = { tab = MainTab.HOME }
                )
                MainTab.ACCOUNT -> AccountScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun FeedScreen(state: CommunityUiState, viewModel: PlatformViewModel) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader(
            "مساحة حيّك", "محتوى المجتمع من الخادم مباشرة",
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            trailing = { IconButton(onClick = viewModel::refreshFeed, enabled = !state.feedLoading) { Icon(Icons.Outlined.Refresh, contentDescription = "تحديث الخلاصة") } }
        )
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        when {
            state.feedLoading && state.feed.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.feedError != null && state.feed.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(state.feedError, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::refreshFeed) { Text("إعادة المحاولة") }
            }
            state.feed.isEmpty() -> EmptyPanel("حيّك يبدأ بمشاركة", "لا توجد مشاركات ظاهرة حاليًا. اطرح سؤالًا أو شارك معلومة مفيدة.", "اكتب أول مشاركة") {
                viewModel.setNotice("استخدم تبويب «مشاركة» لإضافة أول منشور إلى الحي.")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.feed, key = { it.id }) { post ->
                    PostCard(post, { viewModel.openPost(post) }, { viewModel.toggleReaction(post) })
                }
                item { Text("تحديث الخلاصة يجلب أحدث النتائج من الخادم.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: CommunityUiState, viewModel: PlatformViewModel) {
    Column(Modifier.fillMaxSize()) {
        BrandHeader("اكتشف داخل حيّك", "بحث عربي في المنشورات الظاهرة", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("ماذا تبحث عنه؟") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchPosts() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = viewModel::searchPosts, enabled = !state.searchLoading) { Text("بحث") }
        }
        if (state.searchLoading) ProgressRow("نبحث في منشورات الحي…")
        if (state.searchSubmitted && !state.searchLoading && state.searchResults.isEmpty()) {
            EmptyPanel("لم نجد نتائج", "جرّب صياغة أقصر أو كلمة مختلفة.")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.searchResults, key = { it.id }) { post ->
                    PostCard(post, { viewModel.openPost(post) }, { viewModel.toggleReaction(post) })
                }
            }
        }
    }
}

@Composable
private fun ComposePostScreen(
    state: CommunityUiState,
    viewModel: PlatformViewModel,
    onChoosePhoto: () -> Unit,
    onPublished: () -> Unit
) {
    LaunchedEffect(state.publicationRevision) {
        if (state.publicationRevision > 0) onPublished()
    }
    val categories = listOf("GENERAL" to "حديث", "QUESTION" to "سؤال", "REQUEST" to "طلب مساعدة", "RECOMMENDATION" to "توصية", "LOST_FOUND" to "مفقودات", "CLASSIFIED" to "إعلان")
    Column(Modifier.fillMaxSize()) {
        BrandHeader("شارك جيرانك", "مشاركة واحدة قد تكون مفيدة للحي كله", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ما غرض المشاركة؟", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (value, label) ->
                    FilterChip(state.composerCategory == value, { viewModel.setComposerCategory(value) }, label = { Text(label) })
                }
            }
            OutlinedTextField(state.composerTitle, viewModel::setComposerTitle, label = { Text("عنوان واضح") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("${state.composerTitle.length}/200") })
            OutlinedTextField(state.composerBody, viewModel::setComposerBody, label = { Text("اكتب التفاصيل") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, supportingText = { Text("${state.composerBody.length}/2000") })
            if (state.selectedPhotoUri != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(Uri.parse(state.selectedPhotoUri), contentDescription = "الصورة المرفقة", modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Text("الصورة جاهزة للإرفاق", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    IconButton(onClick = viewModel::clearPhoto) { Icon(Icons.Outlined.Close, contentDescription = "إزالة الصورة") }
                }
            } else {
                OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("إرفاق صورة")
                }
            }
            Text("ستُنشر المشاركة في حيّك الحالي. تجنّب مشاركة بيانات شخصية أو صور أشخاص دون موافقتهم.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { viewModel.publishPost() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.composerBusy
            ) {
                if (state.composerBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else { Icon(Icons.Outlined.Send, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("نشر في الحي") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PostDetailScreen(state: CommunityUiState, viewModel: PlatformViewModel, onBack: () -> Unit) {
    val post = state.selectedPost ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "رجوع") }
            Column(Modifier.weight(1f)) {
                Text("تفاصيل المشاركة", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("المصدر: عضو في الحي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PostCard(post, {}, { viewModel.toggleReaction(post) }, showOpenAction = false, expanded = true) }
            item { Text("المحادثة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            when {
                state.commentsLoading -> item { ProgressRow("نحمّل التعليقات…") }
                state.comments.isEmpty() -> item { EmptyPanel("ابدأ الحوار", "لا توجد تعليقات حتى الآن. أضف ردًا مفيدًا ومحترمًا.") }
                else -> items(state.comments, key = { it.id ?: "${it.postId}-${it.createdAt}-${it.body}" }) { CommentCard(it) }
            }
        }
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.commentDraft, viewModel::setCommentDraft,
                    modifier = Modifier.weight(1f), label = { Text("اكتب تعليقًا") }, maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.sendComment() })
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::sendComment, enabled = !state.detailBusy && state.commentDraft.isNotBlank()) {
                    if (state.detailBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Send, contentDescription = "إرسال التعليق")
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: PostCommentDto) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("عضو في الحي", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(relativeTime(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(comment.body)
        }
    }
}

@Composable
private fun AccountScreen(state: CommunityUiState, viewModel: PlatformViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BrandHeader("حسابك ومجتمعك", "إدارة العضوية والنطاق")
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.profile?.displayName?.takeIf { it.isNotBlank() } ?: "عضو ضَيف", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(state.profile?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("عضوية الحي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(when (state.membership?.verificationState) {
                    "VERIFIED" -> "حالة العضوية: موثّقة"
                    "PENDING" -> "حالة العضوية: قيد المراجعة"
                    "REJECTED" -> "حالة العضوية: لم تُقبل بعد"
                    else -> "حالة العضوية: غير موثّقة"
                })
                Text("التوثيق يقرره الخادم والمراجعة الإدارية، ولا يستنتجه التطبيق من موقع الجهاز.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = viewModel::changeNeighborhood, modifier = Modifier.fillMaxWidth()) { Text("تغيير الحي") }
                OutlinedButton(onClick = viewModel::leaveNeighborhood, modifier = Modifier.fillMaxWidth()) { Text("مغادرة الحي") }
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الخصوصية والجلسة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("رمز الوصول قصير العمر ومشفّر باستخدام Android Keystore. لا يُخزّن التطبيق كلمة المرور أو سرّ عميل OAuth.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Logout, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("تسجيل الخروج")
                }
            }
        }
    }
}

@Composable
private fun PostCard(
    post: PostDto,
    onOpen: () -> Unit,
    onReact: () -> Unit,
    showOpenAction: Boolean = true,
    expanded: Boolean = false
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("ج", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("عضو من الحي", fontWeight = FontWeight.SemiBold)
                    Text(relativeTime(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = {}, label = { Text(categoryLabel(post.category)) })
            }
            Spacer(Modifier.height(12.dp))
            Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(post.body, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
            val media = post.media.sortedBy { it.position }.firstOrNull()
            val photoUrl = media?.thumbUrl?.takeIf { it.isNotBlank() } ?: media?.url
            if (!photoUrl.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(photoUrl, contentDescription = "صورة مرفقة بالمشاركة", modifier = Modifier.fillMaxWidth().height(if (expanded) 250.dp else 190.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                if (post.media.size > 1) Text("ومعها ${post.media.size - 1} صور أخرى", modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReact) {
                    Icon(if (post.reactedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = if (post.reactedByMe) "إزالة التفاعل" else "شكر صاحب المشاركة", tint = if (post.reactedByMe) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                    Text("${post.reactionsCount} شكر")
                }
                Spacer(Modifier.weight(1f))
                if (showOpenAction) {
                    TextButton(onClick = onOpen) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                        Spacer(Modifier.width(5.dp)); Text("التفاصيل والتعليقات")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun NoticeCard(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, contentDescription = "إغلاق التنبيه") }
        }
    }
}

@Composable
private fun ProgressRow(message: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FullScreenLoading(message: String) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(); Spacer(Modifier.height(14.dp)); Text(message)
    }
}

@Composable
private fun EmptyPanel(title: String, description: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
    }
}

private fun categoryLabel(value: String): String = when (value) {
    "QUESTION" -> "سؤال"
    "REQUEST" -> "طلب مساعدة"
    "RECOMMENDATION" -> "توصية"
    "LOST_FOUND" -> "مفقودات"
    "CLASSIFIED" -> "إعلان"
    else -> "حديث الحي"
}

private fun relativeTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "الآن"
    val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return raw.take(10)
    val seconds = ChronoUnit.SECONDS.between(instant, Instant.now()).coerceAtLeast(0)
    return when {
        seconds < 60 -> "الآن"
        seconds < 3600 -> "قبل ${seconds / 60} د"
        seconds < 86_400 -> "قبل ${seconds / 3600} س"
        seconds < 604_800 -> "قبل ${seconds / 86_400} يوم"
        else -> instant.toString().take(10)
    }
}
