package com.marketplace.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.core.network.DiscoveryCardDto
import com.marketplace.android.core.network.DiscoveryRowDto
import com.marketplace.android.feature.AppUiState

@Composable
internal fun DiscoveryRail(
    row: DiscoveryRowDto,
    onOpen: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(discoveryRowLabel(row.row), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${row.totalEligible} نتيجة في هذا المسار", fontSize = 12.sp, color = Muted)
            }
            if (row.totalEligible > row.cards.size) {
                Button(onClick = { onOpen(row.row) }, shape = RoundedCornerShape(12.dp)) {
                    Text("عرض الكل")
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(row.cards, key = { "${it.sourceType}:${it.sourceId}" }) { card ->
                DiscoveryCard(card, modifier = Modifier.fillParentMaxWidth(0.82f))
            }
        }
    }
}

@Composable
internal fun DiscoveryRowScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    val row = state.selectedDiscoveryRow ?: return
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Text("العودة إلى الحي") }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(discoveryRowLabel(row), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${state.discoveryPageTotal} نتيجة", color = Muted, fontSize = 12.sp)
            }
        }
        when {
            state.discoveryPageBusy && state.discoveryPage.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("نحمّل محتوى هذا المسار…", modifier = Modifier.padding(start = 12.dp), color = Muted)
                }
            }
            state.discoveryPageError != null && state.discoveryPage.isEmpty() -> {
                DiscoveryMessage(
                    title = "تعذر تحميل المحتوى",
                    detail = state.discoveryPageError,
                    action = "إعادة المحاولة",
                    onAction = onRefresh
                )
            }
            state.discoveryPage.isEmpty() -> {
                DiscoveryMessage(
                    title = "لا يوجد محتوى متاح",
                    detail = "لم يُرجع الخادم عناصر مؤهلة لهذا المسار ضمن نطاق عضويتك.",
                    action = null,
                    onAction = {}
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.discoveryPage, key = { "${it.sourceType}:${it.sourceId}" }) { card ->
                        DiscoveryCard(card, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.discoveryPageError != null) {
                        item {
                            DiscoveryMessage(
                                title = "تعذر تحميل المزيد",
                                detail = state.discoveryPageError,
                                action = "إعادة المحاولة",
                                onAction = onLoadMore
                            )
                        }
                    } else if (!state.discoveryPageLast) {
                        item {
                            OutlinedButton(
                                onClick = onLoadMore,
                                enabled = !state.discoveryPageBusy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(if (state.discoveryPageBusy) "نحمّل المزيد…" else "عرض المزيد")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryCard(card: DiscoveryCardDto, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sourceTypeLabel(card.sourceType), color = Forest, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (card.paid == true) {
                    Text("إعلان مدفوع", color = Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                card.title?.takeIf { it.isNotBlank() } ?: "محتوى من الحي",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            card.snippet?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Ink, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            card.state?.takeIf { it.isNotBlank() }?.let {
                Text("الحالة: ${sourceStateLabel(it)}", color = Muted, fontSize = 12.sp)
            }
            card.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DiscoveryMessage(
    title: String,
    detail: String,
    action: String?,
    onAction: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(detail, color = Muted, fontSize = 13.sp)
            if (action != null) {
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}

private fun discoveryRowLabel(row: String): String = when (row) {
    "URGENT_ALERTS" -> "تنبيهات رسمية عاجلة"
    "FOLLOWED_SOURCES" -> "من مصادر تتابعها"
    "LOST_FOUND" -> "مفقودات وموجودات"
    "NEIGHBORHOOD_RECOMMENDATIONS" -> "توصيات الجيران"
    "EVENTS_AND_OPPORTUNITIES" -> "فعاليات وفرص"
    "FOR_YOU" -> "اكتشف لك"
    else -> "اكتشاف الحي"
}

private fun sourceTypeLabel(type: String): String = when (type) {
    "NEIGHBORHOOD_POST" -> "منشور مجتمعي"
    "NEIGHBORHOOD_EVENT" -> "فعالية"
    "JOB" -> "فرصة عمل"
    "URGENT_ALERT" -> "تنبيه رسمي"
    "PROVIDER_LISTING" -> "عرض خدمة"
    else -> "محتوى من الحي"
}

private fun sourceStateLabel(state: String): String = when (state.uppercase()) {
    "ACTIVE" -> "نشط"
    "FOUND" -> "تم العثور عليه"
    "RESOLVED" -> "تم الحل"
    "CANCELLED" -> "ملغاة"
    "POSTPONED" -> "مؤجلة"
    "OPEN" -> "مفتوحة"
    "CLOSED" -> "مغلقة"
    else -> state
}
