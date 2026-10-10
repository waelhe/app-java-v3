package com.marketplace.dayf.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marketplace.dayf.data.GeoNode

@Composable
fun OnboardingScreen(state: DayfUiState, viewModel: PlatformViewModel) {
    val root = state.geoRoot
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BrandWordmark()
        Text("ابدأ من حيّك", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("اختر موقعك الإداري حتى نعرض مجتمعك المحلي الصحيح. الموقع لا يُستنتج من الجهاز.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (root == null) {
            CircularProgressIndicator()
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("البلد", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(root.nameAr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            LocationDropdown(
                label = "المحافظة",
                selected = state.selectedGovernorate,
                options = root.children,
                enabled = root.children.isNotEmpty(),
                onSelect = viewModel::selectGovernorate
            )
            LocationDropdown(
                label = "المدينة",
                selected = state.selectedCity,
                options = state.selectedGovernorate?.children.orEmpty(),
                enabled = state.selectedGovernorate != null && state.selectedGovernorate.children.isNotEmpty(),
                onSelect = viewModel::selectCity
            )
            LocationDropdown(
                label = "الحي",
                selected = state.selectedNeighborhood,
                options = state.selectedCity?.children.orEmpty(),
                enabled = state.selectedCity != null && state.selectedCity.children.isNotEmpty(),
                onSelect = viewModel::selectNeighborhood
            )
        }
        if (!state.errorMessage.isNullOrBlank()) ErrorMessage(state.errorMessage)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = viewModel::joinSelectedNeighborhood,
            enabled = state.selectedNeighborhood != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("دخول الحي")
        }
    }
}

@Composable
private fun LocationDropdown(
    label: String,
    selected: GeoNode?,
    options: List<GeoNode>,
    enabled: Boolean,
    onSelect: (GeoNode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.nameAr ?: "اختر $label", style = MaterialTheme.typography.bodyLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { node ->
                DropdownMenuItem(
                    text = { Text(node.nameAr) },
                    onClick = {
                        expanded = false
                        onSelect(node)
                    }
                )
            }
        }
    }
}

@Composable
private fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("ض", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        }
        Column {
            Text("ضَيف", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text("مجتمعك يبدأ من الحي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
