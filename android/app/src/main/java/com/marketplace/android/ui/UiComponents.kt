package com.marketplace.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.core.network.CommentDto
import com.marketplace.android.core.network.MarketItemDto
import com.marketplace.android.core.network.NotificationDto
import com.marketplace.android.core.network.PostDto
import com.marketplace.android.core.network.UrgentAlertDto
import com.marketplace.android.feature.AppUiState
import com.marketplace.android.feature.ComposerKind
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun InfoBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isError) WarningCanvas else Sage,
        shape = RoundedCornerShape(13.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = if (isError) Warning else DeepForest,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, lineHeight = 21.sp)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun LoadingCard(message: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(message, color = Muted)
        }
    }
}

@Composable
internal fun LinearLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("نبحث في بيانات الحي…", color = Muted)
    }
}

@Composable
internal fun CenterState(title: String, detail: String, busy: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Muted, lineHeight = 22.sp)
        if (!busy) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("إعادة المحاولة") }
        }
    }
}

@Composable
internal fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(color = WarningCanvas, shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("تعذر تحميل البيانات", fontWeight = FontWeight.Bold, color = Warning)
            Text(message, color = Warning, lineHeight = 21.sp)
            OutlinedButton(onClick = onRetry) { Text("إعادة المحاولة") }
        }
    }
}

@Composable
internal fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = Forest, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White)
            }
        }
        Column {
            Text("المجتمع المحلي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("مجتمع محلي موثوق", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
internal fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun SourceChip(text: String) {
    Surface(color = Sage, contentColor = Forest, shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusChip(status: String) {
    val active = status.uppercase(Locale.ROOT) in listOf("VISIBLE", "ACTIVE")
    val text = when (status.uppercase(Locale.ROOT)) {
        "VISIBLE", "ACTIVE" -> "نشط"
        "SOLD" -> "مباع"
        "HIDDEN_BY_MODERATOR" -> "مخفي للمراجعة"
        "WITHDRAWN" -> "مسحوب"
        "ENDED", "EXPIRED" -> "منتهٍ"
        else -> status.ifBlank { "الحالة غير متاحة" }
    }
    Surface(color = if (active) Sage else Sand, shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = if (active) Forest else Warning, fontSize = 10.sp)
    }
}

@Composable
internal fun PostCard(
    post: PostDto,
    reacting: Boolean,
    isOwnPost: Boolean,
    lostFoundBusy: Boolean,
    reporting: Boolean,
    reported: Boolean,
    onUpdateLostFoundState: (String) -> Unit,
    onReact: () -> Unit,
    onComments: () -> Unit,
    onReport: (String) -> Unit
) {
    var showReportDialog by rememberSaveable(post.id) { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(color = Sage, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(categoryIcon(post.category), fontSize = 18.sp) }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("عضو من الحي", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(formatDate(post.createdAt), color = Muted, fontSize = 11.sp)
                }
                SourceChip(categoryLabel(post.category))
            }
            Text(post.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp)
            Text(post.body, color = Ink, fontSize = 14.sp, lineHeight = 22.sp)
            val photos = post.media.filter { it.url.isNotBlank() }.sortedBy { it.position }
            if (photos.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = photos,
                        key = { photo -> photo.mediaId.ifBlank { "${post.id}:${photo.position}" } }
                    ) { photo ->
                        AsyncImage(
                            model = photo.thumbUrl?.takeIf { it.isNotBlank() } ?: photo.url,
                            contentDescription = "صورة مرفقة بالمنشور: ${post.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(260.dp)
                                .height(184.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Sand)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(post.status)
                Text("المصدر: منشور حيّ", fontSize = 11.sp, color = Muted)
            }
            if (post.category.equals("LOST_FOUND", ignoreCase = true)) {
                StatusChip(lostFoundStateLabel(post.lostFoundState))
                if (isOwnPost && post.lostFoundState.equals("ACTIVE", ignoreCase = true)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onUpdateLostFoundState("FOUND") },
                            enabled = !lostFoundBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (lostFoundBusy) "جارٍ التحديث…" else "عُثر عليه")
                        }
                        OutlinedButton(
                            onClick = { onUpdateLostFoundState("RESOLVED") },
                            enabled = !lostFoundBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إغلاق البلاغ")
                        }
                    }
                }
            }
            Divider(color = Line)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onReact, enabled = !reacting) {
                    Icon(
                        if (post.reactedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (post.reactedByMe) "إزالة الشكر" else "شكر العضو",
                        tint = if (post.reactedByMe) Color(0xFFB44645) else Forest
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (reacting) "جارٍ..." else "شكر ${post.reactionsCount}", color = Ink)
                }
                TextButton(onClick = onComments) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = "التعليقات", tint = Forest)
                    Spacer(Modifier.width(5.dp))
                    Text("التعليقات", color = Ink)
                }
            }
            if (!isOwnPost) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { showReportDialog = true },
                        enabled = !reporting && !reported
                    ) {
                        Text(
                            when {
                                reporting -> "جارٍ إرسال البلاغ…"
                                reported -> "تم الإبلاغ"
                                else -> "إبلاغ عن المنشور"
                            },
                            color = Muted
                        )
                    }
                }
            }
        }
    }
    if (showReportDialog) {
        ContentReportDialog(
            targetLabel = "المنشور",
            onDismiss = { showReportDialog = false },
            onSubmit = { reason ->
                showReportDialog = false
                onReport(reason)
            }
        )
    }
}

@Composable
internal fun OfficialAlertCard(
    alert: UrgentAlertDto,
    compact: Boolean = false
) {
    val highUrgency = alert.level.uppercase(Locale.ROOT) in setOf("CRITICAL", "SEVERE")
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (highUrgency) WarningCanvas else Sage
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("تنبيه رسمي", fontWeight = FontWeight.Bold, color = if (highUrgency) Warning else Forest)
                Text(
                    urgentAlertLevelLabel(alert.level),
                    fontWeight = FontWeight.SemiBold,
                    color = if (highUrgency) Warning else Forest,
                    fontSize = 12.sp
                )
            }
            Text(
                alert.title,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 17.sp else 20.sp,
                lineHeight = 25.sp
            )
            Text(
                "${alert.sourceName} · ${urgentAlertSourceTypeLabel(alert.sourceType)}",
                color = Muted,
                fontSize = 12.sp
            )
            Text(
                alert.body,
                color = Ink,
                lineHeight = 22.sp,
                maxLines = if (compact) 4 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
            val validity = buildList {
                if (alert.validFrom.isNotBlank()) add("بدأ السريان: ${formatAbsoluteTime(alert.validFrom)}")
                add(
                    alert.validUntil?.takeIf { it.isNotBlank() }
                        ?.let { "ساري حتى: ${formatAbsoluteTime(it)}" }
                        ?: "ساري حتى إشعار آخر"
                )
            }
            validity.forEach { line -> Text(line, color = Muted, fontSize = 11.sp) }
        }
    }
}

private fun urgentAlertLevelLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
    "CRITICAL" -> "حرج"
    "SEVERE" -> "شديد"
    "ADVISORY" -> "استرشادي"
    else -> "المستوى غير متاح"
}

private fun formatAbsoluteTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale("ar"))
        .withZone(java.time.ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault("الوقت غير متاح")

private fun urgentAlertSourceTypeLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
    "MUNICIPALITY" -> "بلدية"
    "CIVIL_DEFENSE" -> "الدفاع المدني"
    "UTILITIES" -> "المرافق العامة"
    "HEALTH_AUTHORITY" -> "جهة صحية"
    "EDUCATION_AUTHORITY" -> "جهة تعليمية"
    "OTHER_DELEGATED" -> "جهة رسمية مفوضة"
    else -> "نوع الجهة غير متاح"
}

@Composable
internal fun MarketCard(item: MarketItemDto, onWithdraw: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = Sand, modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Store, contentDescription = null, tint = Warning)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(marketCategoryLabel(item.category), fontSize = 12.sp, color = Muted)
                }
                SourceChip(if (item.mine) "عرضك" else "من الحي")
            }
            Text(
                if (item.category == "FREE") "مجاني" else formatPrice(item.priceCents, item.priceCurrency),
                fontSize = 20.sp,
                color = Forest,
                fontWeight = FontWeight.Bold
            )
            Text("مكان الاستلام: ${item.locationLabel}", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(item.status)
                if (item.sellerVerified) SourceChip("عضوية متحقق منها")
                else Text("حالة عضوية البائع غير موثّقة", color = Muted, fontSize = 11.sp)
            }
            if (item.mine) {
                OutlinedButton(onClick = onWithdraw, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text("سحب العرض من السوق")
                }
            }
        }
    }
}

@Composable
internal fun NotificationCard(notification: NotificationDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = if (notification.read) Color.White else Sage.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (notification.read) Icons.Filled.Notifications else Icons.Filled.CheckCircle,
                contentDescription = if (notification.read) "مقروء" else "غير مقروء",
                tint = Forest,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(notification.type.replace('_', ' '), fontWeight = FontWeight.SemiBold)
                Text(notification.message, lineHeight = 21.sp)
                Text(formatDate(notification.createdAt), color = Muted, fontSize = 11.sp)
                if (!notification.read) Text("اضغط لوضع علامة مقروء", color = Forest, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerSheetLegacy(
    state: AppUiState,
    onDismiss: () -> Unit,
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
    onPublishMarket: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WarmCanvas) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                if (state.composer == ComposerKind.POST) "شارك مع حيّك" else "أضف إلى سوق الحي",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (state.composer == ComposerKind.POST)
                    "اختر الغرض أولًا حتى يفهم الجيران نوع مساهمتك."
                else "سجّل العرض الأصلي؛ السعر وحالة السلعة سيظهران كما أدخلتهما.",
                color = Muted,
                lineHeight = 20.sp
            )
            if (state.composer == ComposerKind.POST) {
                Text("نوع المنشور", fontWeight = FontWeight.SemiBold)
                val categories = listOf(
                    "GENERAL" to "عام", "QUESTION" to "سؤال", "REQUEST" to "طلب مساعدة",
                    "RECOMMENDATION" to "توصية", "LOST_FOUND" to "مفقودات", "CLASSIFIED" to "إعلان"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { (code, label) ->
                        AssistChip(
                            onClick = { onPostCategory(code) },
                            label = { Text(label) },
                            leadingIcon = if (state.postCategory == code) {
                                { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
                OutlinedTextField(
                    value = state.postTitle,
                    onValueChange = onPostTitle,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("عنوان المنشور") },
                    supportingText = { Text("${state.postTitle.length}/200") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = state.postBody,
                    onValueChange = onPostBody,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("التفاصيل") },
                    supportingText = { Text("${state.postBody.length}/2000") },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp)
                )
                Text("النطاق: ${state.scopeName ?: "حيّك"}", color = Muted, fontSize = 12.sp)
            } else {
                Text("نوع العرض", fontWeight = FontWeight.SemiBold)
                val categories = listOf(
                    "FREE" to "مجاني", "FURNITURE" to "أثاث", "ELECTRONICS" to "إلكترونيات",
                    "TOOLS" to "أدوات", "OTHER" to "أخرى"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { (code, label) ->
                        AssistChip(
                            onClick = { onMarketCategory(code) },
                            label = { Text(label) },
                            leadingIcon = if (state.marketCategory == code) {
                                { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
                OutlinedTextField(
                    value = state.marketTitle,
                    onValueChange = onMarketTitle,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسم الغرض أو عنوانه") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (state.marketCategory != "FREE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.marketPrice,
                            onValueChange = onMarketPrice,
                            modifier = Modifier.weight(1f),
                            label = { Text("السعر") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = state.marketCurrency,
                            onValueChange = onMarketCurrency,
                            modifier = Modifier.width(100.dp),
                            label = { Text("العملة") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                } else {
                    InfoBanner("العرض المجاني يُرسل دون سعر، وفق عقد الخدمة.", isError = false)
                }
                Text("حالة السلعة", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onMarketCondition("GOOD") }, label = { Text("جيد") })
                    AssistChip(onClick = { onMarketCondition("LIKE_NEW") }, label = { Text("كالجديد") })
                }
                OutlinedTextField(
                    value = state.marketPickup,
                    onValueChange = onMarketPickup,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("مكان الاستلام داخل الحي") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
            state.notice?.let { InfoBanner(it, isError = true) }
            Button(
                onClick = if (state.composer == ComposerKind.POST) onPublishPost else onPublishMarket,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.submitBusy,
                shape = RoundedCornerShape(15.dp)
            ) {
                if (state.submitBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (state.composer == ComposerKind.POST) "نشر" else "نشر العرض")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsSheet(
    post: PostDto,
    comments: List<CommentDto>,
    loading: Boolean,
    draft: String,
    sending: Boolean,
    notice: String?,
    currentUserId: String,
    reportingContentKeys: Set<String>,
    reportedContentKeys: Set<String>,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onReportComment: (CommentDto, String) -> Unit,
    onDismiss: () -> Unit
) {
    var reportCommentTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WarmCanvas) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("النقاش حول المنشور", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(post.title, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Divider(color = Line)
            when {
                loading -> LoadingCard("نحمّل التعليقات…")
                comments.isEmpty() -> EmptyState(title = "لا توجد تعليقات بعد", detail = "ابدأ نقاشًا محترمًا حول هذا المنشور.")
                else -> LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(comments, key = { it.id }) { comment ->
                        ElevatedCard(shape = RoundedCornerShape(14.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("عضو من الحي · ${formatDate(comment.createdAt)}", color = Muted, fontSize = 11.sp)
                                Text(comment.body, lineHeight = 20.sp)
                                if (!comment.authorId.equals(currentUserId, ignoreCase = true)) {
                                    val key = "COMMENT:${comment.id}"
                                    TextButton(
                                        onClick = { reportCommentTargetId = comment.id },
                                        enabled = key !in reportingContentKeys && key !in reportedContentKeys
                                    ) {
                                        Text(
                                            when {
                                                key in reportingContentKeys -> "جارٍ إرسال البلاغ…"
                                                key in reportedContentKeys -> "تم الإبلاغ"
                                                else -> "إبلاغ عن التعليق"
                                            },
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("اكتب تعليقك") },
                maxLines = 4,
                shape = RoundedCornerShape(14.dp)
            )
            notice?.let { InfoBanner(it, isError = true) }
            Button(onClick = onSend, enabled = !sending, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("إرسال التعليق")
            }
        }
        val targetComment = reportCommentTargetId?.let { id -> comments.firstOrNull { it.id == id } }
        if (targetComment != null) {
            ContentReportDialog(
                targetLabel = "التعليق",
                onDismiss = { reportCommentTargetId = null },
                onSubmit = { reason ->
                    reportCommentTargetId = null
                    onReportComment(targetComment, reason)
                }
            )
        }
    }
}

@Composable
private fun ContentReportDialog(
    targetLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var selectedReason by rememberSaveable(targetLabel) { mutableStateOf("SPAM") }
    val reasons = listOf(
        "SPAM" to "محتوى مزعج أو متكرر",
        "HARASSMENT" to "مضايقة أو إساءة",
        "INAPPROPRIATE" to "محتوى غير مناسب",
        "OTHER" to "سبب آخر"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إبلاغ عن $targetLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("اختر السبب الأقرب. يُرسل البلاغ إلى مسار المراجعة ولا يضمن نتيجة محددة.")
                reasons.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReason = code },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedReason == code,
                            onClick = { selectedReason = code }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(selectedReason) }) { Text("إرسال البلاغ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun lostFoundStateLabel(code: String?): String = when (code?.uppercase(Locale.ROOT)) {
    "ACTIVE" -> "قيد البحث"
    "FOUND" -> "تم العثور على المفقود"
    "RESOLVED" -> "أُغلق البلاغ"
    null, "" -> "حالة البلاغ غير متاحة"
    else -> "حالة البلاغ: $code"
}

private fun categoryLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
    "GENERAL" -> "عام"
    "QUESTION" -> "سؤال"
    "REQUEST" -> "طلب مساعدة"
    "RECOMMENDATION" -> "توصية"
    "LOST_FOUND" -> "مفقودات"
    "CLASSIFIED" -> "إعلان"
    else -> "منشور"
}

private fun categoryIcon(code: String): String = when (code.uppercase(Locale.ROOT)) {
    "QUESTION" -> "؟"
    "REQUEST" -> "🤝"
    "RECOMMENDATION" -> "★"
    "LOST_FOUND" -> "⌕"
    "CLASSIFIED" -> "▣"
    else -> "◉"
}

private fun marketCategoryLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
    "FREE" -> "مجاني"
    "FURNITURE" -> "أثاث"
    "ELECTRONICS" -> "إلكترونيات"
    "TOOLS" -> "أدوات"
    "OTHER" -> "أخرى"
    else -> code
}

internal fun verificationLabel(code: String?): String = when (code?.uppercase(Locale.ROOT)) {
    "UNVERIFIED" -> "غير موثّق"
    "PENDING" -> "قيد المراجعة"
    "VERIFIED" -> "موثّق"
    "REJECTED" -> "رُفض التوثيق"
    null -> "غير متاح"
    else -> code
}

private fun formatDate(value: String): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return "وقت النشر غير متاح"
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل $minutes د"
        minutes < 1440 -> "قبل ${minutes / 60} س"
        minutes < 10080 -> "قبل ${minutes / 1440} يوم"
        else -> runCatching {
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale("ar"))
                .withZone(java.time.ZoneId.systemDefault()).format(instant)
        }.getOrDefault("وقت النشر غير متاح")
    }
}

private fun formatPrice(cents: Int?, currency: String?): String {
    if (cents == null || currency.isNullOrBlank()) return "السعر غير متاح"
    return String.format(Locale.ROOT, "%.2f %s", cents / 100.0, currency.uppercase(Locale.ROOT))
}
