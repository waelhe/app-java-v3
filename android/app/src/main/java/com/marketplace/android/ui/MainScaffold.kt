package com.marketplace.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.marketplace.android.core.network.MarketItemDto
import com.marketplace.android.core.network.NotificationDto
import com.marketplace.android.core.network.EventDto
import com.marketplace.android.core.network.GroupDto
import com.marketplace.android.core.network.PollDto
import com.marketplace.android.core.network.PostDto
import com.marketplace.android.feature.AppUiState
import com.marketplace.android.feature.ComposerKind
import com.marketplace.android.feature.MainTab
import com.marketplace.android.feature.ExploreMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignedInScaffold(
    state: AppUiState,
    onRefresh: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onBeginComposer: (ComposerKind) -> Unit,
    onLoadMore: () -> Unit,
    onReact: (PostDto) -> Unit,
    onOpenComments: (PostDto) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onExploreMode: (ExploreMode) -> Unit,
    onRsvpEvent: (EventDto) -> Unit,
    onToggleGroup: (GroupDto) -> Unit,
    onVotePoll: (PollDto, String) -> Unit,
    onWithdrawPollVote: (PollDto) -> Unit,
    onWithdrawMarketItem: (MarketItemDto) -> Unit,
    onMarkRead: (NotificationDto) -> Unit,
    onRequestVerification: () -> Unit,
    onSignOutOnDevice: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ضَيف", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = Forest)
                            Text(
                                state.scopeName?.let { "نطاقك: $it" } ?: "نطاق الحي",
                                fontSize = 11.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onSelectTab(MainTab.NOTIFICATIONS) }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "التنبيهات")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmCanvas, titleContentColor = Ink)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                NavigationBarItem(
                    selected = state.tab == MainTab.HOME,
                    onClick = { onSelectTab(MainTab.HOME) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("الرئيسية") }
                )
                NavigationBarItem(
                    selected = state.tab == MainTab.EXPLORE,
                    onClick = { onSelectTab(MainTab.EXPLORE) },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("استكشف") }
                )
                NavigationBarItem(
                    selected = state.tab == MainTab.MARKET,
                    onClick = { onSelectTab(MainTab.MARKET) },
                    icon = { Icon(Icons.Filled.Store, contentDescription = null) },
                    label = { Text("السوق") }
                )
                NavigationBarItem(
                    selected = state.tab == MainTab.NOTIFICATIONS,
                    onClick = { onSelectTab(MainTab.NOTIFICATIONS) },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    label = { Text("النشاط") }
                )
                NavigationBarItem(
                    selected = state.tab == MainTab.PROFILE,
                    onClick = { onSelectTab(MainTab.PROFILE) },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text("حسابي") }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onBeginComposer(
                        when (state.tab) {
                            MainTab.MARKET -> ComposerKind.MARKET
                            MainTab.EXPLORE -> when (state.exploreMode) {
                                ExploreMode.EVENTS -> ComposerKind.EVENT
                                ExploreMode.POLLS -> ComposerKind.POLL
                                else -> ComposerKind.POST
                            }
                            else -> ComposerKind.POST
                        }
                    )
                },
                containerColor = Forest,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = RoundedCornerShape(18.dp)
            ) { Icon(Icons.Filled.Add, contentDescription = "إنشاء محتوى") }
        },
        containerColor = WarmCanvas
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                MainTab.HOME -> FeedScreen(
                    state, onRefresh, onLoadMore, onReact, onOpenComments,
                    onCreate = { onBeginComposer(ComposerKind.POST) }
                )
                MainTab.EXPLORE -> ExploreScreen(
                    state, onSearchQuery, onSearch, onExploreMode, onReact, onOpenComments,
                    onRsvpEvent, onToggleGroup, onVotePoll, onWithdrawPollVote, onBeginComposer, onRefresh
                )
                MainTab.MARKET -> MarketScreen(
                    state, onRefresh, onWithdrawMarketItem,
                    onCreate = { onBeginComposer(ComposerKind.MARKET) }
                )
                MainTab.NOTIFICATIONS -> NotificationsScreen(state, onRefresh, onMarkRead)
                MainTab.PROFILE -> ProfileScreen(state, onRequestVerification, onSignOutOnDevice)
            }
        }
    }
}

@Composable
private fun FeedScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onReact: (PostDto) -> Unit,
    onOpenComments: (PostDto) -> Unit,
    onCreate: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ما الذي يحدث حولك؟", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("منشورات أعضاء الحي، مرتبة حسب الأحدث من المصدر.", color = Muted, fontSize = 13.sp)
            }
        }
        if (state.feedBusy && state.posts.isEmpty()) item { LoadingCard("نحمّل منشورات الحي…") }
        else if (state.feedError != null && state.posts.isEmpty()) item { ErrorCard(state.feedError, onRefresh) }
        else if (state.posts.isEmpty()) item {
            EmptyState(
                title = "لا توجد منشورات بعد",
                detail = "ابدأ بمشاركة سؤال أو طلب أو توصية مع أعضاء حيّك.",
                actionLabel = "إنشاء أول منشور",
                onAction = onCreate
            )
        } else {
            items(state.posts, key = { it.id }) { post ->
                PostCard(
                    post = post,
                    reacting = post.id in state.reactingPostIds,
                    onReact = { onReact(post) },
                    onComments = { onOpenComments(post) }
                )
            }
            if (state.feedBusy) item { LoadingCard("نحمّل المزيد…") }
            else if (!state.feedLast) item {
                OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("عرض منشورات أقدم")
                }
            }
            state.feedError?.let { message -> item { ErrorCard(message, onRefresh) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreScreen(
    state: AppUiState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onMode: (ExploreMode) -> Unit,
    onReact: (PostDto) -> Unit,
    onOpenComments: (PostDto) -> Unit,
    onRsvpEvent: (EventDto) -> Unit,
    onToggleGroup: (GroupDto) -> Unit,
    onVotePoll: (PollDto, String) -> Unit,
    onWithdrawPollVote: (PollDto) -> Unit,
    onBeginComposer: (ComposerKind) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("اكتشف مجتمعك", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("منشورات وفعاليات ومجموعات واستطلاعات حيّك من الخدمة نفسها.", color = Muted, fontSize = 13.sp)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                ExploreMode.POSTS to "منشورات",
                ExploreMode.EVENTS to "فعاليات",
                ExploreMode.GROUPS to "مجموعات",
                ExploreMode.POLLS to "استطلاعات"
            ).forEach { (mode, label) ->
                AssistChip(
                    onClick = { onMode(mode) },
                    label = { Text(label) },
                    leadingIcon = if (state.exploreMode == mode) {
                        { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, contentDescription = null) }
                    } else null
                )
            }
        }
        when (state.exploreMode) {
            ExploreMode.POSTS -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onQuery,
                        modifier = Modifier.weight(1f),
                        label = { Text("ابحث في منشورات الحي") },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp)
                    )
                    Button(onClick = onSearch, enabled = !state.searchBusy, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Filled.Search, contentDescription = "بحث")
                    }
                }
                state.searchError?.let { InfoBanner(it, isError = true) }
                if (state.searchBusy) LinearLoading()
                if (state.searchResults.isEmpty() && !state.searchBusy && state.searchError == null) {
                    EmptyState(
                        title = "ابدأ الاكتشاف",
                        detail = "ابحث في محتوى الحي أو افتح تبويب الفعاليات والمجموعات والاستطلاعات."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 95.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.searchResults, key = { it.id }) { post ->
                            PostCard(
                                post = post,
                                reacting = post.id in state.reactingPostIds,
                                onReact = { onReact(post) },
                                onComments = { onOpenComments(post) }
                            )
                        }
                    }
                }
            }
            ExploreMode.EVENTS -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ما سيحدث قريبًا", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onBeginComposer(ComposerKind.EVENT) }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("نظّم فعالية")
                    }
                }
                when {
                    state.eventsBusy && state.events.isEmpty() -> LoadingCard("نحمّل فعاليات الحي…")
                    state.eventsError != null && state.events.isEmpty() -> ErrorCard(state.eventsError, onRefresh)
                    state.events.isEmpty() -> EmptyState("لا توجد فعاليات قادمة", "أنشئ فعالية مجتمعية أو عد لاحقًا لمشاهدة ما ينظمه جيرانك.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 95.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.events, key = { it.id }) { event ->
                            EventCard(event, busy = event.id in state.pollActionIds, onRsvp = { onRsvpEvent(event) })
                        }
                        state.eventsError?.let { error -> item { ErrorCard(error, onRefresh) } }
                    }
                }
            }
            ExploreMode.GROUPS -> {
                Text("مساحات تجمع الجيران حسب الاهتمام", color = Muted, fontSize = 13.sp)
                when {
                    state.groupsBusy && state.groups.isEmpty() -> LoadingCard("نحمّل مجموعات الحي…")
                    state.groupsError != null && state.groups.isEmpty() -> ErrorCard(state.groupsError, onRefresh)
                    state.groups.isEmpty() -> EmptyState("لا توجد مجموعات متاحة", "لا نعرض مجموعات تجريبية. ستظهر هنا المجموعات المسجلة لحيّك.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 95.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.groups, key = { it.id }) { group ->
                            GroupCard(group, busy = group.id in state.pollActionIds, onToggle = { onToggleGroup(group) })
                        }
                        state.groupsError?.let { error -> item { ErrorCard(error, onRefresh) } }
                    }
                }
            }
            ExploreMode.POLLS -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("صوتك جزء من الحي", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onBeginComposer(ComposerKind.POLL) }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("أنشئ استطلاعًا")
                    }
                }
                when {
                    state.pollsBusy && state.polls.isEmpty() -> LoadingCard("نحمّل استطلاعات الحي…")
                    state.pollsError != null && state.polls.isEmpty() -> ErrorCard(state.pollsError, onRefresh)
                    state.polls.isEmpty() -> EmptyState("لا توجد استطلاعات", "ابدأ سؤالًا مع خيارات واضحة ليشارك جيرانك في القرار.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 95.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.polls, key = { it.id }) { poll ->
                            PollCard(
                                poll,
                                busy = poll.id in state.pollActionIds,
                                onVote = { optionId -> onVotePoll(poll, optionId) },
                                onWithdraw = { onWithdrawPollVote(poll) }
                            )
                        }
                        state.pollsError?.let { error -> item { ErrorCard(error, onRefresh) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onWithdraw: (MarketItemDto) -> Unit,
    onCreate: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("سوق الحي", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("عروض الأعضاء. تُعرض حالة البائع كما أعادتها الخدمة.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("أضف غرضًا للسوق")
            }
        }
        if (state.marketBusy && state.marketItems.isEmpty()) item { LoadingCard("نحمّل عروض الحي…") }
        else if (state.marketError != null && state.marketItems.isEmpty()) item { ErrorCard(state.marketError, onRefresh) }
        else if (state.marketItems.isEmpty()) item {
            EmptyState(
                title = "السوق فارغ حاليًا",
                detail = "أضف غرضًا للبيع أو أعطه مجانًا. لا نعرض منتجات تجريبية.",
                actionLabel = "أضف أول عرض",
                onAction = onCreate
            )
        } else {
            items(state.marketItems, key = { it.id }) { item -> MarketCard(item, onWithdraw = { onWithdraw(item) }) }
            state.marketError?.let { error -> item { ErrorCard(error, onRefresh) } }
        }
    }
}

@Composable
private fun NotificationsScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onMarkRead: (NotificationDto) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("النشاط والتنبيهات", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("إشعارات حسابك الفعلية من الخادم.", color = Muted, fontSize = 13.sp)
        }
        if (state.notificationsBusy && state.notifications.isEmpty()) item { LoadingCard("نحمّل التنبيهات…") }
        else if (state.notificationsError != null && state.notifications.isEmpty()) item {
            ErrorCard(state.notificationsError, onRefresh)
        } else if (state.notifications.isEmpty()) item {
            EmptyState(title = "لا توجد تنبيهات", detail = "ستظهر هنا التنبيهات التي تصل إلى حسابك.")
        } else {
            items(state.notifications, key = { it.id }) { notification ->
                NotificationCard(notification, onClick = { onMarkRead(notification) })
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    state: AppUiState,
    onRequestVerification: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("حسابك في ضَيف", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("هوية الدخول وعضوية الحي والتوثيق حالات منفصلة.", color = Muted, lineHeight = 21.sp)
        ElevatedCard(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = Sage, modifier = Modifier.size(48.dp)) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Forest, modifier = Modifier.size(25.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.scopeName ?: "الحي الحالي", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("عضوية الحي", fontSize = 12.sp, color = Muted)
                    }
                }
                Divider(color = Line)
                KeyValueRow("حالة العضوية", verificationLabel(state.membership?.verificationState))
                KeyValueRow("منذ", state.membership?.memberSince?.take(10).orEmpty().ifBlank { "غير متاح" })
                if (state.membership?.verificationState == "UNVERIFIED") {
                    Button(
                        onClick = onRequestVerification,
                        enabled = !state.profileBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (state.profileBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Person, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("طلب مراجعة التحقق")
                    }
                    Text(
                        "هذا الطلب يُسجّل للمراجعة فقط؛ لا يعني أن العضوية أصبحت موثّقة.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
        ElevatedCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الخصوصية والجلسة", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "يحتفظ التطبيق برمز الوصول في الذاكرة فقط. إزالة الجلسة من هذا الجهاز لا تدّعي إبطال جلسة الخادم.",
                    color = Muted,
                    lineHeight = 21.sp
                )
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("إزالة جلسة هذا الجهاز")
                }
            }
        }
    }
}
