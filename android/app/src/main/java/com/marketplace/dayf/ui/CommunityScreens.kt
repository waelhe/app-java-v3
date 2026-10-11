package com.marketplace.dayf.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marketplace.dayf.data.InstitutionEntry
import com.marketplace.dayf.data.NeighborhoodEvent
import com.marketplace.dayf.data.NeighborhoodGroup
import com.marketplace.dayf.data.NeighborhoodPoll
import com.marketplace.dayf.data.NotificationItem

@Composable
fun NeighborhoodScreen(
    state: DayfUiState,
    viewModel: PlatformViewModel,
    padding: PaddingValues,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            item {
                FilterChip(
                    selected = state.communitySection == CommunitySection.FEED,
                    onClick = { viewModel.selectCommunitySection(CommunitySection.FEED) },
                    label = { Text("المنشورات") },
                    leadingIcon = { Icon(Icons.Outlined.Campaign, contentDescription = null) }
                )
            }
            item {
                FilterChip(
                    selected = state.communitySection == CommunitySection.EVENTS,
                    onClick = { viewModel.selectCommunitySection(CommunitySection.EVENTS) },
                    label = { Text("الفعاليات") },
                    leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null) }
                )
            }
            item {
                FilterChip(
                    selected = state.communitySection == CommunitySection.GROUPS,
                    onClick = { viewModel.selectCommunitySection(CommunitySection.GROUPS) },
                    label = { Text("المجموعات") },
                    leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) }
                )
            }
            item {
                FilterChip(
                    selected = state.communitySection == CommunitySection.POLLS,
                    onClick = { viewModel.selectCommunitySection(CommunitySection.POLLS) },
                    label = { Text("الاستطلاعات") },
                    leadingIcon = { Icon(Icons.Outlined.HowToVote, contentDescription = null) }
                )
            }
            item {
                FilterChip(
                    selected = state.communitySection == CommunitySection.NOTIFICATIONS,
                    onClick = { viewModel.selectCommunitySection(CommunitySection.NOTIFICATIONS) },
                    label = { Text(if (state.unreadNotificationCount > 0) "الإشعارات (${state.unreadNotificationCount})" else "الإشعارات") },
                    leadingIcon = { Icon(Icons.Outlined.Notifications, contentDescription = null) }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.communitySection) {
                CommunitySection.FEED -> FeedScreen(state, viewModel, PaddingValues(0.dp), onSignOut)
                CommunitySection.EVENTS -> EventsScreen(state, viewModel)
                CommunitySection.GROUPS -> GroupsScreen(state, viewModel)
                CommunitySection.POLLS -> PollsScreen(state, viewModel)
                CommunitySection.NOTIFICATIONS -> NotificationsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun EventsScreen(state: DayfUiState, viewModel: PlatformViewModel) {
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(state.eventCreateCompleted) {
        if (state.eventCreateCompleted) {
            creating = false
            viewModel.acknowledgeEventCreate()
        }
    }
    if (creating) {
        EventComposer(
            state = state,
            onBack = { creating = false },
            onSubmit = viewModel::submitEvent
        )
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("فعاليات قادمة في حيّك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("موعد ومكان واضحان، وعدد حضور من الخادم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { creating = true }) { Text("أنشئ فعالية") }
        }
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        when {
            state.isLoadingEvents -> LoadingBlock()
            state.events.isEmpty() -> EmptyNotice("لا توجد فعاليات قادمة", "أنشئ أول فعالية في الحي أو عُد لاحقًا لمتابعة ما يشاركه الجيران.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.events, key = { it.id }) { event ->
                    EventCard(event) { viewModel.toggleRsvp(event) }
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: NeighborhoodEvent, onRsvp: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Event, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(eventCategoryLabel(event.category), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (event.featured) Text("مميزة", style = MaterialTheme.typography.labelMedium)
            }
            Text(event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(event.description, style = MaterialTheme.typography.bodyMedium)
            Text("الموعد: " + event.startsAt.replace("T", " ").take(16), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("المكان: " + event.locationLabel)
            Text("الجهة المنظمة: " + event.organizerLabel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الحضور: " + event.attending)
                event.capacity?.let { Text(" / $it") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRsvp) {
                    Text(if (event.rsvpedByMe) "إلغاء حضوري" else "سأحضر")
                }
            }
        }
    }
}

@Composable
private fun GroupsScreen(state: DayfUiState, viewModel: PlatformViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("مجموعات الجيران", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("انضم إلى مجموعة لها اهتمام محلي مشترك.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        when {
            state.isLoadingGroups -> LoadingBlock()
            state.groups.isEmpty() -> EmptyNotice("لا توجد مجموعات متاحة", "تُعرض هنا المجموعات التي أنشأها الخادم لهذا الحي.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.groups, key = { it.id }) { group ->
                    GroupCard(group) { viewModel.toggleGroupMembership(group) }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: NeighborhoodGroup, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(group.description)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الأعضاء: " + group.members, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (group.joinedByMe) {
                    OutlinedButton(onClick = onToggle) { Text("مغادرة المجموعة") }
                } else {
                    Button(onClick = onToggle) { Text("انضمام") }
                }
            }
        }
    }
}

@Composable
private fun PollsScreen(state: DayfUiState, viewModel: PlatformViewModel) {
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(state.pollCreateCompleted) {
        if (state.pollCreateCompleted) {
            creating = false
            viewModel.acknowledgePollCreate()
        }
    }
    if (creating) {
        PollComposer(
            state = state,
            onBack = { creating = false },
            onSubmit = viewModel::submitPoll
        )
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("رأي الحي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("كل خيار وعدد أصواته يأتيان من الخادم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { creating = true }) { Text("أنشئ استطلاعًا") }
        }
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        when {
            state.isLoadingPolls -> LoadingBlock()
            state.polls.isEmpty() -> EmptyNotice("لا توجد استطلاعات", "ستظهر الاستطلاعات المنشورة فعليًا لأعضاء هذا الحي.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.polls, key = { it.id }) { poll ->
                    PollCard(
                        poll = poll,
                        onVote = { optionId -> viewModel.voteOnPoll(poll, optionId) },
                        onWithdraw = { viewModel.withdrawPollVote(poll) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PollCard(poll: NeighborhoodPoll, onVote: (String) -> Unit, onWithdraw: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("استطلاع رأي", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Text(poll.question, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("نُشر بواسطة: " + poll.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val totalVotes = poll.options.sumOf { it.votes }.coerceAtLeast(1L)
            poll.options.forEach { option ->
                val chosen = poll.votedByMe == option.id
                val share = (option.votes * 100 / totalVotes).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, modifier = Modifier.weight(1f), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                        Text("$share% · ${option.votes}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { option.votes.toFloat() / totalVotes.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (poll.votedByMe == null) {
                        OutlinedButton(onClick = { onVote(option.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("التصويت لهذا الخيار")
                        }
                    } else if (chosen) {
                        Text("اختيارك الحالي", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (poll.votedByMe != null) {
                TextButton(onClick = onWithdraw, modifier = Modifier.align(Alignment.End)) { Text("سحب صوتي") }
            }
        }
    }
}

@Composable
private fun NotificationsScreen(state: DayfUiState, viewModel: PlatformViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("إشعاراتك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("غير المقروء: " + state.unreadNotificationCount, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = viewModel::refreshNotifications) { Text("تحديث") }
        }
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        when {
            state.isLoadingNotifications -> LoadingBlock()
            state.notifications.isEmpty() -> EmptyNotice("لا توجد إشعارات", "ستظهر هنا التنبيهات التي أنشأتها المنصة لحسابك.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.notifications, key = { it.id }) { notification ->
                    NotificationCard(notification) { viewModel.markNotificationRead(notification) }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationItem, onRead: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (notification.read) Icons.Outlined.Notifications else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(notification.type.replace("_", " "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Text(notification.message)
                Text(notification.createdAt.replace("T", " ").take(16), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!notification.read) {
                TextButton(onClick = onRead) { Text("مقروء") }
            }
        }
    }
}

@Composable
fun DirectoryScreen(state: DayfUiState, viewModel: PlatformViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("دليل الجهات والمؤسسات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("السجل العام للمدارس والجامعات والعيادات والجهات المحلية وحالة توثيق كل جهة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        when {
            state.isLoadingInstitutions -> LoadingBlock()
            state.institutions.isEmpty() -> EmptyNotice("لا توجد جهات مسجلة", "سيظهر هنا السجل العام الفعلي. هذا ليس دليلًا افتراضيًا لأعمال غير مسجلة.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.institutions, key = { it.id }) { institution ->
                    InstitutionCard(institution)
                }
            }
        }
    }
}

@Composable
private fun InstitutionCard(institution: InstitutionEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Domain, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(institution.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(institutionTypeLabel(institution.type), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (institution.verificationState == "VERIFIED") Icons.Outlined.VerifiedUser else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (institution.verificationState == "VERIFIED") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(institutionVerificationLabel(institution.verificationState), style = MaterialTheme.typography.labelMedium)
            }
            institution.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
            institution.address?.takeIf { it.isNotBlank() }?.let { Text("العنوان: " + it) }
            institution.phone?.takeIf { it.isNotBlank() }?.let { Text("الهاتف: " + it) }
            institution.website?.takeIf { it.isNotBlank() }?.let { Text("الموقع: " + it) }
        }
    }
}

@Composable
private fun EventComposer(
    state: DayfUiState,
    onBack: () -> Unit,
    onSubmit: (String, String, String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf("SOCIAL") }
    var categoryMenu by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var organizer by remember { mutableStateOf("مجتمع الحي") }
    var startDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var startTime by remember { mutableStateOf(LocalTime.of(18, 0)) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val categories = listOf(
        "SPORTS_FAMILY" to "نشاط عائلي ورياضي",
        "VOLUNTEER" to "تطوع ومبادرات",
        "SOCIAL" to "اجتماعي",
        "MARKET" to "سوق محلي",
        "WORKSHOP" to "ورشة عمل"
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text("تنظيم فعالية في الحي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text("سيتم نشرها في حيّك، ويمكن للجيران تسجيل الحضور.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(categories.first { it.first == category }.second)
            }
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                categories.forEach { (code, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { category = code; categoryMenu = false })
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= 200) title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان الفعالية") },
            supportingText = { Text("${title.length}/200") },
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { if (it.length <= 2000) description = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("وصف الفعالية وما ينبغي إحضاره") },
            supportingText = { Text("${description.length}/2000") },
            minLines = 3,
            maxLines = 6
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> startDate = LocalDate.of(year, month + 1, day) },
                        startDate.year,
                        startDate.monthValue - 1,
                        startDate.dayOfMonth
                    ).show()
                }
            ) { Text(startDate.format(dateFormatter)) }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> startTime = LocalTime.of(hour, minute) },
                        startTime.hour,
                        startTime.minute,
                        true
                    ).show()
                }
            ) { Text(startTime.format(timeFormatter)) }
        }
        Text(
            "الوقت حسب منطقتك الزمنية: " + startDate.format(dateFormatter) + " " + startTime.format(timeFormatter),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = location,
            onValueChange = { if (it.length <= 200) location = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("مكان اللقاء داخل الحي") },
            supportingText = { Text("${location.length}/200") },
            singleLine = true
        )
        OutlinedTextField(
            value = organizer,
            onValueChange = { if (it.length <= 200) organizer = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم الجهة المنظمة") },
            supportingText = { Text("${organizer.length}/200") },
            singleLine = true
        )
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        Button(
            onClick = {
                val instant = startDate.atTime(startTime).atZone(ZoneId.systemDefault()).toInstant().toString()
                onSubmit(category, title, description, instant, location, organizer)
            },
            enabled = !state.isSubmittingEvent && title.isNotBlank() && description.isNotBlank() &&
                location.isNotBlank() && organizer.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.isSubmittingEvent) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("نشر الفعالية")
        }
    }
}

@Composable
private fun PollComposer(
    state: DayfUiState,
    onBack: () -> Unit,
    onSubmit: (String, List<String>) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text("إنشاء استطلاع رأي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text("سؤال واحد وخياران إلى خمسة؛ لا يمكن التصويت أكثر من مرة في الاستطلاع نفسه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = question,
            onValueChange = { if (it.length <= 200) question = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("السؤال") },
            supportingText = { Text("${question.length}/200") },
            minLines = 2,
            maxLines = 4
        )
        options.forEachIndexed { index, option ->
            OutlinedTextField(
                value = option,
                onValueChange = { value -> if (value.length <= 200) options[index] = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("الخيار ${index + 1}") },
                supportingText = { Text("${option.length}/200") },
                singleLine = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (options.size < 5) options.add("") },
                enabled = options.size < 5,
                modifier = Modifier.weight(1f)
            ) { Text("إضافة خيار") }
            OutlinedButton(
                onClick = { if (options.size > 2) options.removeAt(options.lastIndex) },
                enabled = options.size > 2,
                modifier = Modifier.weight(1f)
            ) { Text("حذف آخر خيار") }
        }
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        Button(
            onClick = { onSubmit(question, options.toList()) },
            enabled = !state.isSubmittingPoll && question.isNotBlank() &&
                options.count { it.isNotBlank() } >= 2,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.isSubmittingPoll) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("نشر الاستطلاع")
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun EmptyNotice(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun eventCategoryLabel(category: String): String = when (category) {
    "SPORTS_FAMILY" -> "نشاط عائلي / رياضي"
    "VOLUNTEER" -> "تطوع ومبادرات"
    "SOCIAL" -> "اجتماعي"
    "MARKET" -> "سوق محلي"
    "WORKSHOP" -> "ورشة"
    else -> category
}

private fun institutionTypeLabel(type: String): String = when (type) {
    "SCHOOL" -> "مدرسة"
    "UNIVERSITY" -> "جامعة"
    "CLINIC" -> "عيادة"
    "MOSQUE" -> "مسجد"
    "CHARITY" -> "جمعية خيرية"
    "GOVERNMENT" -> "جهة حكومية"
    "COMPANY" -> "شركة"
    "NGO" -> "منظمة أهلية"
    else -> type
}

private fun institutionVerificationLabel(state: String): String = when (state) {
    "VERIFIED" -> "جهة موثقة"
    "PENDING" -> "طلب التوثيق قيد المراجعة"
    "REJECTED" -> "لم تجتز التوثيق"
    else -> "غير موثقة"
}
