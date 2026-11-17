package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.NotificationType
import com.example.domain.model.AppNotification
import java.util.concurrent.ConcurrentHashMap

class SnrNotificationHelper(private val context: Context) {

    private val displayedDedupeKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var channelsCreated = false

    fun ensureChannelsCreated() {
        if (channelsCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages & Groups",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Personal and group chat message notifications from DayunanChat"
            }
            val socialChannel = NotificationChannel(
                CHANNEL_SOCIAL,
                "Friend Requests & Invites",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Friend requests and group invitations on DayunanChat"
            }
            manager.createNotificationChannel(messagesChannel)
            manager.createNotificationChannel(socialChannel)
        }
        channelsCreated = true
    }

    fun showNotification(
        notification: AppNotification,
        notificationsEnabled: Boolean,
        showMessagePreview: Boolean
    ) {
        ensureChannelsCreated()
        if (!notificationsEnabled || notification.read) return
        val key = notification.dedupeKey.ifBlank { notification.id }
        if (!displayedDedupeKeys.add(key)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val isMessage = notification.typeEnum() == NotificationType.PERSONAL_MESSAGE ||
            notification.typeEnum() == NotificationType.GROUP_MESSAGE
        val channelId = if (isMessage) CHANNEL_MESSAGES else CHANNEL_SOCIAL

        val bodyText = if (isMessage && !showMessagePreview) {
            "New message on DayunanChat"
        } else {
            notification.body
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_TYPE, notification.type)
            putExtra(EXTRA_TARGET_ID, notification.targetId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_snr_notification)
            .setColor(ContextCompat.getColor(context, R.color.snr_primary_blue))
            .setContentTitle(notification.title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (isMessage) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(key.hashCode(), builder.build())
        }
    }

    fun showPushPayload(title: String, body: String, targetType: String?, targetId: String?) {
        ensureChannelsCreated()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_TYPE, targetType)
            putExtra(EXTRA_TARGET_ID, targetId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (title + body).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_snr_notification)
            .setColor(ContextCompat.getColor(context, R.color.snr_primary_blue))
            .setContentTitle(title.ifBlank { "DayunanChat" })
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        runCatching {
            NotificationManagerCompat.from(context).notify((title + body).hashCode(), builder.build())
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "snr_chat_messages"
        const val CHANNEL_SOCIAL = "snr_chat_social"
        const val EXTRA_TARGET_TYPE = "snr_target_type"
        const val EXTRA_TARGET_ID = "snr_target_id"
    }
}
