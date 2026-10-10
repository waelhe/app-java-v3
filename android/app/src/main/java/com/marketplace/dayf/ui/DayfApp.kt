package com.marketplace.dayf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.marketplace.dayf.BuildConfig

@Composable
fun DayfApp(
    viewModel: PlatformViewModel,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state.stage) {
        AppStage.LOADING -> LoadingScreen()
        AppStage.SIGN_IN -> SignInScreen(
            error = state.errorMessage,
            clientConfigured = BuildConfig.OAUTH_CLIENT_ID.isNotBlank(),
            onSignIn = onSignIn
        )
        AppStage.ONBOARDING -> OnboardingScreen(state, viewModel)
        AppStage.ERROR -> ErrorScreen(
            message = state.errorMessage ?: "تعذّر تحميل المنصة.",
            onRetry = viewModel::refreshJourney,
            onSignOut = onSignOut
        )
        AppStage.HOME -> HomeShell(state, viewModel, onSignOut)
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark()
            CircularProgressIndicator()
            Text("نهيّئ مساحتك في ضَيف", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignInScreen(error: String?, clientConfigured: Boolean, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        BrandMark()
        Spacer(Modifier.height(28.dp))
        Text("حيّك أقرب، وأهله حولك.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "ضَيف مساحة محلية للتعرّف إلى الجيران، وطرح الأسئلة، وتبادل التوصيات، والوصول إلى الخدمات القريبة.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("تسجيل دخول آمن", fontWeight = FontWeight.SemiBold)
                    Text("يفتح متصفح النظام ويستخدم OAuth 2.0 مع PKCE.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSignIn, enabled = clientConfigured, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("تسجيل الدخول")
        }
        if (!clientConfigured) {
            Spacer(Modifier.height(12.dp))
            Text(
                "يلزم إعداد OAUTH_PUBLIC_CLIENT_ID قبل تشغيل تسجيل الدخول. لا يوجد سرّ عميل داخل التطبيق.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            ErrorMessage(error)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "لن نعرض منشورات أو منتجات تجريبية على أنها محتوى حقيقي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BrandMark()
        Spacer(Modifier.height(24.dp))
        Text("لم نتمكن من إكمال الاتصال", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(message)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("إعادة المحاولة") }
        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("العودة إلى تسجيل الدخول")
        }
    }
}

@Composable
private fun HomeShell(state: DayfUiState, viewModel: PlatformViewModel, onSignOut: () -> Unit) {
    val currentPost = state.selectedPost
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.activeTab == HomeTab.NEIGHBORHOOD,
                    onClick = { viewModel.setTab(HomeTab.NEIGHBORHOOD) },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    label = { Text("الحي") },
                    alwaysShowLabel = true
                )
                NavigationBarItem(
                    selected = state.activeTab == HomeTab.MARKET,
                    onClick = { viewModel.setTab(HomeTab.MARKET) },
                    icon = { Icon(Icons.Outlined.Storefront, contentDescription = null) },
                    label = { Text("السوق") },
                    alwaysShowLabel = true
                )
                NavigationBarItem(
                    selected = state.activeTab == HomeTab.CREATE,
                    onClick = { viewModel.setTab(HomeTab.CREATE) },
                    icon = { Icon(Icons.Outlined.AddCircle, contentDescription = null) },
                    label = { Text("نشر") },
                    alwaysShowLabel = true
                )
                NavigationBarItem(
                    selected = state.activeTab == HomeTab.REAL_ESTATE,
                    onClick = { viewModel.setTab(HomeTab.REAL_ESTATE) },
                    icon = { Icon(Icons.Outlined.Apartment, contentDescription = null) },
                    label = { Text("العقار") },
                    alwaysShowLabel = true
                )
                NavigationBarItem(
                    selected = state.activeTab == HomeTab.DIRECTORY,
                    onClick = { viewModel.setTab(HomeTab.DIRECTORY) },
                    icon = { Icon(Icons.Outlined.Business, contentDescription = null) },
                    label = { Text("الأعمال") },
                    alwaysShowLabel = true
                )
            }
        }
    ) { innerPadding ->
        when {
            currentPost != null -> PostDetailScreen(state, currentPost, viewModel, innerPadding)
            else -> when (state.activeTab) {
                HomeTab.NEIGHBORHOOD -> FeedScreen(state, viewModel, innerPadding, onSignOut)
                HomeTab.MARKET -> MarketScreen(innerPadding)
                HomeTab.CREATE -> CreatePostScreen(state, viewModel, innerPadding)
                HomeTab.REAL_ESTATE -> CapabilityScreen(
                    padding = innerPadding,
                    icon = { Icon(Icons.Outlined.Apartment, contentDescription = null) },
                    title = "العقار في محيطك",
                    body = "سنربط هذه الوجهة بقائمة العقارات الحقيقية وعقودها الحالية. لن نعرض إعلانات ثابتة على أنها منشورة من مستخدمين."
                )
                HomeTab.DIRECTORY -> CapabilityScreen(
                    padding = innerPadding,
                    icon = { Icon(Icons.Outlined.Business, contentDescription = null) },
                    title = "دليل الأعمال المحلية",
                    body = "ستعرض هذه الوجهة ملفات الأعمال ومصادرها وتقييماتها عند اكتمال عقد القراءة العام في الخادم."
                )
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary),
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
