package com.marketplace.dayf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MarketScreen(padding: PaddingValues) {
    val categories = listOf(
        "مخابز وحلويات", "خضار وفواكه", "لحوم ودواجن", "قهوة وتحميص",
        "أزياء وعبايات", "أطفال ومواليد", "أسر منتجة ومطابخ", "عطور وعناية"
    )
    Column(
        Modifier.fillMaxSize().padding(padding).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("سوق الحي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("منتجات وخدمات من المتاجر والأسر المحلية.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = "",
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ابحث عن متجر أو منتج") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
        )
        Text("أقسام السوق", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        categories.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { category ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.ShoppingBasket, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Text(category, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        CapabilityNotice(
            title = "تصفح المنتجات الحقيقية",
            body = "عقد القراءة العام لمتاجر الحي غير متاح في الباك إند الحالي؛ لهذا لا نعرض بطاقات منتجات أو أسعارًا مختلقة. عند اكتمال API المتجر سنربط البحث والفئات والعروض والمخزون والسلة بعقودها الفعلية."
        )
    }
}

@Composable
fun CapabilityScreen(padding: PaddingValues, title: String, body: String, icon: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f))
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(64.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) { icon() }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        CapabilityNotice(title = "الربط بالخادم", body = body)
    }
}
