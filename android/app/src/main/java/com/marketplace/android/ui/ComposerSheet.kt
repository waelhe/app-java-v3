package com.marketplace.android.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.feature.AppUiState
import com.marketplace.android.feature.ComposerKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerSheet(
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
    onPublishPoll: () -> Unit
) {
    val context = LocalContext.current
    val chosenStart = remember(state.eventStartsAt) {
        runCatching { Instant.parse(state.eventStartsAt).atZone(ZoneId.systemDefault()) }
            .getOrElse {
                ZonedDateTime.now(ZoneId.systemDefault()).plusDays(1)
                    .withHour(18).withMinute(0).withSecond(0).withNano(0)
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WarmCanvas) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                when (state.composer) {
                    ComposerKind.POST -> "شارك مع حيّك"
                    ComposerKind.MARKET -> "أضف إلى سوق الحي"
                    ComposerKind.EVENT -> "نظّم فعالية في حيّك"
                    ComposerKind.POLL -> "أنشئ استطلاعًا"
                    null -> "إنشاء محتوى"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            when (state.composer) {
                ComposerKind.POST -> {
                    Text("نوع المنشور", fontWeight = FontWeight.SemiBold)
                    val categories = listOf(
                        "GENERAL" to "عام", "QUESTION" to "سؤال", "REQUEST" to "طلب مساعدة",
                        "RECOMMENDATION" to "توصية", "LOST_FOUND" to "مفقودات", "CLASSIFIED" to "إعلان"
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        value = state.postTitle, onValueChange = onPostTitle,
                        modifier = Modifier.fillMaxWidth(), label = { Text("عنوان المنشور") },
                        supportingText = { Text("${state.postTitle.length}/200") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = state.postBody, onValueChange = onPostBody,
                        modifier = Modifier.fillMaxWidth(), label = { Text("التفاصيل") },
                        supportingText = { Text("${state.postBody.length}/2000") },
                        minLines = 4, maxLines = 8, shape = RoundedCornerShape(14.dp)
                    )
                    Text("سيُنشر المنشور ضمن نطاق حيّك الحالي.", color = Muted, fontSize = 12.sp)
                }

                ComposerKind.MARKET -> {
                    Text("نوع العرض", fontWeight = FontWeight.SemiBold)
                    val categories = listOf(
                        "FREE" to "مجاني", "FURNITURE" to "أثاث", "ELECTRONICS" to "إلكترونيات",
                        "TOOLS" to "أدوات", "OTHER" to "أخرى"
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        value = state.marketTitle, onValueChange = onMarketTitle,
                        modifier = Modifier.fillMaxWidth(), label = { Text("اسم الغرض أو عنوانه") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    if (state.marketCategory != "FREE") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.marketPrice, onValueChange = onMarketPrice,
                                modifier = Modifier.weight(1f), label = { Text("السعر") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true, shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = state.marketCurrency, onValueChange = onMarketCurrency,
                                modifier = Modifier.width(100.dp), label = { Text("العملة") },
                                singleLine = true, shape = RoundedCornerShape(14.dp)
                            )
                        }
                    } else {
                        InfoBanner("العرض المجاني يُرسل دون سعر وفق عقد الخدمة.", isError = false)
                    }
                    Text("حالة السلعة", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onMarketCondition("GOOD") }, label = { Text("جيد") })
                        AssistChip(onClick = { onMarketCondition("LIKE_NEW") }, label = { Text("كالجديد") })
                    }
                    OutlinedTextField(
                        value = state.marketPickup, onValueChange = onMarketPickup,
                        modifier = Modifier.fillMaxWidth(), label = { Text("مكان الاستلام داخل الحي") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                }

                ComposerKind.EVENT -> {
                    Text("نوع الفعالية", fontWeight = FontWeight.SemiBold)
                    val categories = listOf(
                        "SOCIAL" to "اجتماعية", "VOLUNTEER" to "تطوع", "SPORTS_FAMILY" to "رياضة وعائلة",
                        "MARKET" to "سوق محلي", "WORKSHOP" to "ورشة عمل"
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { (code, label) ->
                            AssistChip(
                                onClick = { onEventCategory(code) }, label = { Text(label) },
                                leadingIcon = if (state.eventCategory == code) {
                                    { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.eventTitle, onValueChange = onEventTitle,
                        modifier = Modifier.fillMaxWidth(), label = { Text("عنوان الفعالية") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = state.eventDescription, onValueChange = onEventDescription,
                        modifier = Modifier.fillMaxWidth(), label = { Text("ماذا سيحدث؟") },
                        minLines = 3, maxLines = 6, shape = RoundedCornerShape(14.dp)
                    )
                    Text("موعد البدء", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    onEventStartsAt(
                                        chosenStart.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                                            .toInstant().toString()
                                    )
                                },
                                chosenStart.year, chosenStart.monthValue - 1, chosenStart.dayOfMonth
                            ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
                        }) {
                            Text(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ar")).format(chosenStart))
                        }
                        TextButton(onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    onEventStartsAt(chosenStart.withHour(hour).withMinute(minute)
                                        .withSecond(0).withNano(0).toInstant().toString())
                                },
                                chosenStart.hour, chosenStart.minute, true
                            ).show()
                        }) {
                            Text(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(chosenStart))
                        }
                    }
                    Text(
                        if (state.eventStartsAt.isBlank()) "اختر التاريخ والوقت لحفظ الموعد." else "الموعد المحلي المحدد: ${DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale("ar")).format(chosenStart)}",
                        color = Muted, fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = state.eventLocationLabel, onValueChange = onEventLocationLabel,
                        modifier = Modifier.fillMaxWidth(), label = { Text("مكان اللقاء داخل الحي") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = state.eventOrganizerLabel, onValueChange = onEventOrganizerLabel,
                        modifier = Modifier.fillMaxWidth(), label = { Text("اسم الجهة المنظمة") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    Text("طريقة الحضور", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("OPEN" to "مفتوح", "LIMITED_SEATS" to "مقاعد محدودة", "TABLE_RESERVATION" to "حجز طاولات")
                            .forEach { (code, label) ->
                                AssistChip(
                                    onClick = { onEventRegistration(code) }, label = { Text(label) },
                                    leadingIcon = if (state.eventRegistration == code) {
                                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                    }
                    if (state.eventRegistration != "OPEN") {
                        OutlinedTextField(
                            value = state.eventCapacity, onValueChange = onEventCapacity,
                            modifier = Modifier.fillMaxWidth(), label = { Text("عدد المقاعد أو الطاولات") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                ComposerKind.POLL -> {
                    Text("سؤال الاستطلاع", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.pollQuestion, onValueChange = onPollQuestion,
                        modifier = Modifier.fillMaxWidth(), label = { Text("ما القرار أو الرأي الذي تريد معرفته؟") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = state.pollAuthorLabel, onValueChange = onPollAuthorLabel,
                        modifier = Modifier.fillMaxWidth(), label = { Text("اسم الجهة الناشرة") },
                        singleLine = true, shape = RoundedCornerShape(14.dp)
                    )
                    Text("الخيارات (من 2 إلى 5)", fontWeight = FontWeight.SemiBold)
                    state.pollOptions.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = value, onValueChange = { onPollOption(index, it) },
                                modifier = Modifier.weight(1f),
                                label = { Text("الخيار ${index + 1}") },
                                singleLine = true, shape = RoundedCornerShape(14.dp)
                            )
                            if (state.pollOptions.size > 2) {
                                TextButton(onClick = { onRemovePollOption(index) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف الخيار")
                                }
                            }
                        }
                    }
                    TextButton(onClick = onAddPollOption, enabled = state.pollOptions.size < 5) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("إضافة خيار")
                    }
                    Text("بعد التصويت، ستظهر الأصوات الفعلية المحفوظة في الخادم.", color = Muted, fontSize = 12.sp)
                }

                null -> Unit
            }

            state.notice?.let { InfoBanner(it, isError = true) }
            Button(
                onClick = when (state.composer) {
                    ComposerKind.POST -> onPublishPost
                    ComposerKind.MARKET -> onPublishMarket
                    ComposerKind.EVENT -> onPublishEvent
                    ComposerKind.POLL -> onPublishPoll
                    null -> onDismiss
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.submitBusy,
                shape = RoundedCornerShape(15.dp)
            ) {
                if (state.submitBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(
                    when (state.composer) {
                        ComposerKind.POST -> "نشر المنشور"
                        ComposerKind.MARKET -> "نشر العرض"
                        ComposerKind.EVENT -> "إنشاء الفعالية"
                        ComposerKind.POLL -> "نشر الاستطلاع"
                        null -> "إغلاق"
                    }
                )
            }
        }
    }
}
