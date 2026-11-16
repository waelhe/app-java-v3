package com.marketplace.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketplace.android.core.network.ListingSummaryDto
import com.marketplace.android.feature.AppUiState

@Composable
internal fun StorefrontScreen(onOpenNeighborhoodMarket: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("سوق الحي", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("متاجر الحي ومنتجاته المحلية", fontSize = 15.sp, color = Muted)
        }
        item {
            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("التسوّق من متاجر الحي", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("تصنيفات السوق المعتمدة تشمل المخابز، والخضار والفواكه، واللحوم، والقهوة، والأزياء، ومستلزمات الأطفال، والأسر المنتجة، والعطور والعناية.", color = Muted, lineHeight = 22.sp)
                    Text("قائمة المنتجات العامة والسلة والطلبات والتوصيل لم تُفتح بعد في واجهة الباك إند. لن نعرض منتجات وهمية أو ندّعي أن الشراء يعمل قبل ربطه بعقود حقيقية.", color = Warning, lineHeight = 22.sp)
                }
            }
        }
        item {
            Text("تصنيفات السوق", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("مخابز وحلويات", "خضار وفواكه", "لحوم ودواجن", "قهوة وتحميص", "أزياء وعبايات", "أطفال ومواليد", "أسر منتجة ومطابخ", "عطور وعناية").forEach { category ->
                    AssistChip(onClick = {}, enabled = false, label = { Text(category) })
                }
            }
        }
        item {
            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = Sage)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حراج الجيران", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("بيع المستعمل أو إهداء الأغراض مجانًا ضمن الحي. هذه خدمة فعلية منفصلة عن كتالوج المتاجر.", color = Ink, lineHeight = 22.sp)
                    Button(onClick = onOpenNeighborhoodMarket, modifier = Modifier.fillMaxWidth()) { Text("افتح حراج الحي") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PropertySearchScreen(
    state: AppUiState,
    onQuery: (String) -> Unit,
    onPurpose: (String) -> Unit,
    onType: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("العقار", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("بحث فعلي في العروض المنشورة. اختر نوع المعاملة والعقار ثم ابحث.", color = Muted)
        OutlinedTextField(value = state.propertyQuery, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), label = { Text("ماذا تبحث عنه؟") }, placeholder = { Text("شقة، منزل، أرض…") }, singleLine = true, shape = RoundedCornerShape(15.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("RENT" to "إيجار", "SALE" to "شراء").forEach { (value, label) ->
                AssistChip(onClick = { onPurpose(value) }, label = { Text(if (state.propertyPurpose == value) "✓ $label" else label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("APARTMENT" to "شقق", "VILLA" to "فلل", "LAND" to "أراضٍ", "SHOP" to "محلات", "OFFICE" to "مكاتب", "GARAGE" to "كراجات").forEach { (value, label) ->
                AssistChip(onClick = { onType(value) }, label = { Text(if (state.propertyType == value) "✓ $label" else label) })
            }
        }
        Button(onClick = onSearch, enabled = !state.propertyBusy, modifier = Modifier.fillMaxWidth()) { Text(if (state.propertyBusy) "يجري البحث…" else "ابحث عن عقار") }
        when {
            state.propertyBusy && state.propertyListings.isEmpty() -> LoadingCard("نبحث في العروض المنشورة…")
            state.propertyError != null && state.propertyListings.isEmpty() -> ErrorCard(state.propertyError, onRefresh)
            state.propertyListings.isEmpty() -> EmptyState(title = "لا توجد نتائج بعد", detail = "غيّر الكلمات أو نوع العقار والمعاملة. تُعرض هنا نتائج الخدمة الفعلية فقط.")
            else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.propertyListings, key = { it.id }) { listing -> ListingResultCard(listing) }
                state.propertyError?.let { error -> item { ErrorCard(error, onRefresh) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BusinessDirectoryScreen(
    state: AppUiState,
    onQuery: (String) -> Unit,
    onMinRating: (Double?) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("دليل الأعمال", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("اعثر على خدمات وعروض مقدمي الخدمة وتقييماتهم الفعلية.", color = Muted)
        OutlinedTextField(value = state.directoryQuery, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث في الخدمات والعروض") }, placeholder = { Text("صيانة، تنظيف، استشارة…") }, singleLine = true, shape = RoundedCornerShape(15.dp))
        Text("الحد الأدنى للتقييم", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(null to "الكل", 3.0 to "3+", 4.0 to "4+", 4.5 to "4.5+").forEach { (value, label) ->
                AssistChip(onClick = { onMinRating(value) }, label = { Text(if (state.directoryMinRating == value) "✓ $label" else label) })
            }
        }
        Button(onClick = onSearch, enabled = !state.directoryBusy, modifier = Modifier.fillMaxWidth()) { Text(if (state.directoryBusy) "يجري البحث…" else "ابحث في الدليل") }
        Text("المتاح حاليًا نتائج العروض والخدمات المنشورة مع اسم مقدمها وتقييمه. تصفح صفحات الأعمال الكاملة يحتاج نقطة استكشاف عامة لمقدمي الخدمة.", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        when {
            state.directoryBusy && state.directoryListings.isEmpty() -> LoadingCard("نحمّل الخدمات المنشورة…")
            state.directoryError != null && state.directoryListings.isEmpty() -> ErrorCard(state.directoryError, onRefresh)
            state.directoryListings.isEmpty() -> EmptyState(title = "لا توجد نتائج بعد", detail = "ابحث باسم الخدمة أو جرّب تخفيف فلتر التقييم. لا نولّد أعمالًا أو تقييمات تجريبية.")
            else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.directoryListings, key = { it.id }) { listing -> ListingResultCard(listing) }
                state.directoryError?.let { error -> item { ErrorCard(error, onRefresh) } }
            }
        }
    }
}

@Composable
private fun ListingResultCard(listing: ListingSummaryDto) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listing.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(listing.providerName?.takeIf { it.isNotBlank() }, listing.category.takeIf { it.isNotBlank() }).joinToString(" · "), color = Muted, fontSize = 13.sp)
            Text(if (listing.providerRating == null) "لا يوجد تقييم موثّق" else listing.providerRating.toString() + " ★ · " + listing.providerReviewCount + " مراجعة", color = Forest, fontSize = 13.sp)
            Text(if (listing.price == null) "السعر عند التواصل" else listing.price.toPlainString() + " " + (listing.currency ?: ""), fontWeight = FontWeight.SemiBold)
        }
    }
}
