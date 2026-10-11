package com.marketplace.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.marketplace.android.core.auth.OidcAuthManager
import com.marketplace.android.core.ui.PlatformViewModel
import com.marketplace.android.core.ui.MarketplaceApp
import com.marketplace.android.core.ui.theme.DayfTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlatformViewModel by viewModels()
    private lateinit var authManager: OidcAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = OidcAuthManager(
            activity = this,
            onAccessToken = viewModel::onAccessToken,
            onMessage = viewModel::setNotice
        )
        authManager.handleAuthorizationIntent(intent)

        setContent {
            DayfTheme {
                MarketplaceApp(
                    viewModel = viewModel,
                    onSignIn = { authManager.beginSignIn() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authManager.handleAuthorizationIntent(intent)
    }
}
