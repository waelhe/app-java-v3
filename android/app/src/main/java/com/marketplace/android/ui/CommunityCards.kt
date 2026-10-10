package com.marketplace.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.core.network.EventDto
import com.marketplace.android.core.network.GroupDto
import com.marketplace.android.core.network.PollDto
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun EventCard(event: EventDto, busy: Boolean, onRsvp: () -> Unit) {
    val full = event.capacity != null && event.attending >= event.capacity && !event.rsvpedByMe
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(eventCategoryLabel(event.category), color = Forest, fontSize = 12.sp)
            Text(event.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(event.description, lineHeight = 21.sp, maxLines = 5)
            Text("الموعد: ${formatEventTime(event.startsAt)}", color = Muted, fontSize = 13.sp)
            Text("المكان: ${event.locationLabel}", color = Muted, fontSize = 13.sp)
            Text("المنظّم: ${event.organizerLabel}", color = Muted, fontSize = 13.sp)
            Text(if (event.capacity == null) "${event.attending} مشارك" else "${event.attending} / ${event.capacity}",
                color = Muted, fontSize = 12.sp)
            Button(onClick = onRsvp, enabled = !busy && (event.rsvpedByMe || !full),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(when { event.rsvpedByMe -> "إلغاء تسجيل الحضور"; full -> "اكتملت السعة"; else -> "سأحضر" })
            }
        }
    }
}

@Composable
internal fun GroupCard(group: GroupDto, busy: Boolean, onToggle: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (group.description.isNotBlank()) Text(group.description, lineHeight = 21.sp)
            Text("${group.members} عضو", color = Muted, fontSize = 12.sp)
            Button(onClick = onToggle, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (group.joinedByMe) "مغادرة المجموعة" else "انضم إلى المجموعة")
            }
        }
    }
}

@Composable
internal fun PollCard(poll: PollDto, busy: Boolean, onVote: (String) -> Unit, onWithdraw: () -> Unit) {
    val total = poll.options.sumOf { it.votes }.coerceAtLeast(0L)
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(poll.question, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
            Text("ناشر الاستطلاع: ${poll.author}", color = Muted, fontSize = 12.sp)
            poll.options.sortedBy { it.position }.forEach { option ->
                val mine = poll.votedByMe == option.id
                val fraction = if (total == 0L) 0f else (option.votes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                Text("${option.label} · ${option.votes}", fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
                if (poll.votedByMe == null) {
                    OutlinedButton(onClick = { onVote(option.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("صوّت لهذا الخيار")
                    }
                } else if (mine) Text("هذا اختيارك", color = Forest, fontSize = 12.sp)
            }
            Text("عدد الأصوات: ${total}", color = Muted, fontSize = 12.sp)
            if (poll.votedByMe != null) TextButton(onClick = onWithdraw, enabled = !busy) {
                Text(if (busy) "جارٍ..." else "سحب صوتي")
            }
        }
    }
}

private fun eventCategoryLabel(value: String) = when (value.uppercase(Locale.ROOT)) {
    "SPORTS_FAMILY" -> "رياضة وعائلة"
    "VOLUNTEER" -> "تطوع ومبادرات"
    "SOCIAL" -> "لقاء اجتماعي"
    "MARKET" -> "سوق محلي"
    "WORKSHOP" -> "ورشة عمل"
    else -> value
}

private fun formatEventTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale("ar"))
        .withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("الموعد غير متاح")
