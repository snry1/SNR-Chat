package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.core.DarkModePreference
import com.example.navigation.SnrAppNavigation
import com.example.notifications.SnrNotificationHelper
import com.example.ui.theme.SnrChatTheme
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SnrChatApplication).container

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            container.userRepository.updatePresence(true)
                            delay(1200)
                            runCatching {
                                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                                    if (!token.isNullOrBlank()) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            container.notificationRepository.registerDeviceToken(token)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            container.userRepository.updatePresence(false)
                        }
                    }
                    else -> Unit
                }
            }
        )

        val targetType = intent?.getStringExtra(SnrNotificationHelper.EXTRA_TARGET_TYPE)
        val targetId = intent?.getStringExtra(SnrNotificationHelper.EXTRA_TARGET_ID)

        setContent {
            val darkPref by container.themePreferenceStore.darkModePreference
                .collectAsStateWithLifecycle(initialValue = container.themePreferenceStore.cachedPreference)
            val isDark = when (darkPref) {
                DarkModePreference.SYSTEM -> isSystemInDarkTheme()
                DarkModePreference.LIGHT -> false
                DarkModePreference.DARK -> true
            }

            SnrChatTheme(darkTheme = isDark) {
                SnrAppNavigation(
                    container = container,
                    isDarkTheme = isDark,
                    initialTargetType = targetType,
                    initialTargetId = targetId
                )
            }
        }
    }
}
