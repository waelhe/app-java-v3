package com.marketplace.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.core.network.GeoNodeDto
import com.marketplace.android.feature.AppUiState
import com.marketplace.android.feature.ComposerKind
import com.marketplace.android.feature.ExploreMode
import com.marketplace.android.feature.MainTab

val Forest = Color(0xFF155D50)
val DeepForest = Color(0xFF103F38)
val Sage = Color(0xFFE4F1EB)
val WarmCanvas = Color(0xFFF7F7F2)
val Sand = Color(0xFFF0E8D9)
val Ink = Color(0xFF1B2926)
val Muted = Color(0xFF65736E)
val Line = Color(0xFFE0E7E2)
val Warning = Color(0xFF8A5018)
val WarningCanvas = Color(0xFFFFF1DB)

@Composable
fun DayfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest,
            onPrimary = Color.White,
            secondary = Color(0xFF4A6B5D),
            background = WarmCanvas,
            surface = Color.White,
            onSurface = Ink,
            onBackground = Ink,
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

@Composable
fun DayfApp(
    state: AppUiState,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onLocationQuery: (String) -> Unit,
    onJoinNeighborhood: (GeoNodeDto) -> Unit,
    onBeginComposer: (ComposerKind) -> Unit,
    onCloseComposer: () -> Unit,
    onPostCategory: (String) -> Unit,
    onPostTitle: (String) -> Unit,
    onPostBody: (String) -> Unit,
    onPublishPost: () -> Unit,
    onMarketCategory: (String) -> Unit,
    onMarketCondition: (String) -> Unit,
    onMarketTitle: (String) -> Unit,
    onMarketPrice: (String) -> Unit,
    onMarketCurrency: (String) -> Unit,
    onMarketPickup: (String) -> Unit,
    onPublishMarket: () -> Unit,
    onEventCategory: (String) -> Unit,
    onEventTitle: (String) -> Unit,
    onEventDescription: (String) -> Unit,
    onEventStartsAt: (String) -> Unit,
    onEventLocationLabel: (String) -> Unit,
    onEventOrganizerLabel: (String) -> Unit,
    onEventRegistration: (String) -> Unit,
    onEventCapacity: (String) -> Unit,
    onPublishEvent: () -> Unit,
    onPollQuestion: (String) -> Unit,
    onPollAuthorLabel: (String) -> Unit,
    onPollOption: (Int, String) -> Unit,
    onAddPollOption: () -> Unit,
    onRemovePollOption: (Int) -> Unit,
    onPublishPoll: () -> Unit,
    onWithdrawMarketItem: (com.marketplace.android.core.network.MarketItemDto) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onExploreMode: (ExploreMode) -> Unit,
    onRsvpEvent: (com.marketplace.android.core.network.EventDto) -> Unit,
    onToggleGroup: (com.marketplace.android.core.network.GroupDto) -> Unit,
    onVotePoll: (com.marketplace.android.core.network.PollDto, String) -> Unit,
    onWithdrawPollVote: (com.marketplace.android.core.network.PollDto) -> Unit,
    onLoadMore: () -> Unit,
    onReact: (com.marketplace.android.core.network.PostDto) -> Unit,
    onOpenComments: (com.marketplace.android.core.network.PostDto) -> Unit,
    onCloseComments: () -> Unit,
    onCommentDraft: (String) -> Unit,
    onSendComment: () -> Unit,
    onMarkRead: (com.marketplace.android.core.network.NotificationDto) -> Unit,
    onRequestVerification: () -> Unit,
    onSignOutOnDevice: () -> Unit,
    onDismissNotice: () -> Unit
) {
    when {
        !state.authenticated -> SignInScreen(state.authError, onSignIn)
        state.loadingAccount -> CenterState(
            title = "نجهّز مساحتك المحلية",
            detail = "نتحقق من عضويتك ونحمّل البيانات من الخدمة.",
            busy = true,
            onRetry = onRefresh
        )
        state.membershipRequired -> NeighborhoodSetupScreen(state, onLocationQuery, onJoinNeighborhood)
        state.membership == null -> CenterState(
            title = "تعذر فتح مساحة الحي",
            detail = state.notice ?: "لم نتمكن من قراءة عضوية الحي. تحقق من الاتصال وأعد المحاولة.",
            busy = false,
            onRetry = onRefresh
        )
        else -> SignedInScaffold(
            state = state,
            onRefresh = onRefresh,
            onSelectTab = onSelectTab,
            onBeginComposer = onBeginComposer,
            onLoadMore = onLoadMore,
            onReact = onReact,
            onOpenComments = onOpenComments,
            onSearchQuery = onSearchQuery,
            onSearch = onSearch,
            onExploreMode = onExploreMode,
            onRsvpEvent = onRsvpEvent,
            onToggleGroup = onToggleGroup,
            onVotePoll = onVotePoll,
            onWithdrawPollVote = onWithdrawPollVote,
            onWithdrawMarketItem = onWithdrawMarketItem,
            onMarkRead = onMarkRead,
            onRequestVerification = onRequestVerification,
            onSignOutOnDevice = onSignOutOnDevice
        )
    }

    if (state.composer != null) {
        ComposerSheet(
            state = state,
            onDismiss = onCloseComposer,
            onPostCategory = onPostCategory,
            onPostTitle = onPostTitle,
            onPostBody = onPostBody,
            onPublishPost = onPublishPost,
            onMarketCategory = onMarketCategory,
            onMarketCondition = onMarketCondition,
            onMarketTitle = onMarketTitle,
            onMarketPrice = onMarketPrice,
            onMarketCurrency = onMarketCurrency,
            onMarketPickup = onMarketPickup,
            onPublishMarket = onPublishMarket,
            onEventCategory = onEventCategory,
            onEventTitle = onEventTitle,
            onEventDescription = onEventDescription,
            onEventStartsAt = onEventStartsAt,
            onEventLocationLabel = onEventLocationLabel,
            onEventOrganizerLabel = onEventOrganizerLabel,
            onEventRegistration = onEventRegistration,
            onEventCapacity = onEventCapacity,
            onPublishEvent = onPublishEvent,
            onPollQuestion = onPollQuestion,
            onPollAuthorLabel = onPollAuthorLabel,
            onPollOption = onPollOption,
            onAddPollOption = onAddPollOption,
            onRemovePollOption = onRemovePollOption,
            onPublishPoll = onPublishPoll
        )
    }
    if (state.commentsPost != null) {
        CommentsSheet(
            post = state.commentsPost,
            comments = state.comments,
            loading = state.commentsBusy,
            draft = state.commentDraft,
            sending = state.commentBusy,
            notice = state.notice,
            onDraft = onCommentDraft,
            onSend = onSendComment,
            onDismiss = onCloseComments
        )
    }
    if (state.notice != null && state.composer == null && state.commentsPost == null && state.authenticated) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text("ملاحظة") },
            text = { Text(state.notice) },
            confirmButton = { TextButton(onClick = onDismissNotice) { Text("حسنًا") } }
        )
    }
}

@Composable
private fun SignInScreen(error: String?, onSignIn: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepForest, Forest, WarmCanvas)))
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(92.dp),
                shape = RoundedCornerShape(30.dp),
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Home, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("ضَيف", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("مجتمعك المحلي، أقرب إليك", color = Color.White.copy(alpha = 0.92f), fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "حيّك، جيرانك، ما تحتاجه وما يمكنك تقديمه — في مساحة واحدة موثوقة.",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 14.sp,
                lineHeight = 23.sp
            )
            Spacer(Modifier.height(34.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("أهلًا بعودتك", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "سجّل دخولك بحسابك للانضمام إلى حيّك والوصول إلى المحتوى الحقيقي.",
                        color = Muted,
                        lineHeight = 22.sp
                    )
                    if (!error.isNullOrBlank()) InfoBanner(error, isError = true)
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("تسجيل الدخول الآمن", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                    }
                    Text(
                        "سيُفتح تسجيل الدخول عبر متصفح النظام. لا يطلب التطبيق كلمة مرورك داخله.",
                        fontSize = 12.sp,
                        color = Muted,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("هوية العضو، عضوية الحي، والتوثيق حالات منفصلة.", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NeighborhoodSetupScreen(
    state: AppUiState,
    onQuery: (String) -> Unit,
    onChoose: (GeoNodeDto) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(WarmCanvas).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BrandMark()
        Text("لنبدأ من حيّك", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "ابحث عن اسم الحي واختر نتيجة من المستوى المحلي. لن تُفعّل عضويتك إلا بعد تأكيد الاختيار.",
            color = Muted,
            lineHeight = 23.sp
        )
        OutlinedTextField(
            value = state.locationQuery,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم الحي") },
            placeholder = { Text("مثال: الميدان") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        if (state.locationBusy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("نبحث في دليل المواقع…", color = Muted)
            }
        }
        state.locationError?.let { InfoBanner(it, isError = true) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.locationResults, key = { it.id }) { location ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(location) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(shape = CircleShape, color = Sage, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Forest)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(location.nameAr, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(location.nameEn ?: location.slug, color = Muted, fontSize = 12.sp)
                            Text("حيّ · مستوى 3", color = Forest, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.ArrowForward, contentDescription = "اختيار ${location.nameAr}", tint = Forest)
                    }
                }
            }
            if (state.locationQuery.trim().length >= 2 && state.locationResults.isEmpty() && !state.locationBusy) {
                item { EmptyState(title = "لا توجد أحياء مطابقة", detail = "جرّب اسمًا آخر أو راجع تهجئة المكان.") }
            }
        }
        Text(
            "يُحفظ الانضمام عبر الخدمة. اختيار الحي لا يعني أنك موثّق.",
            fontSize = 12.sp,
            color = Muted,
            lineHeight = 18.sp
        )
    }
}
