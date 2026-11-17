package com.example.notifications

import com.example.SnrChatApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnrFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = applicationContext as? SnrChatApplication ?: return
        serviceScope.launch {
            app.container.notificationRepository.registerDeviceToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val app = applicationContext as? SnrChatApplication ?: return
        val title = message.notification?.title ?: message.data["title"] ?: "DayunanChat"
        val body = message.notification?.body ?: message.data["body"] ?: "You have a new update"
        val targetType = message.data["type"]
        val targetId = message.data["targetId"]
        app.container.notificationHelper.showPushPayload(title, body, targetType, targetId)
    }
}
