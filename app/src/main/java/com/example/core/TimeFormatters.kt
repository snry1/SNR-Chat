package com.example.core

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormatters {
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayTimeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun formatConversationTime(ts: Timestamp?): String {
        if (ts == null) return ""
        val date = ts.toDate()
        val now = Date()
        val diffMillis = now.time - date.time
        val diffMins = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
        return when {
            diffMins < 1 -> "Now"
            isSameDay(date, now) -> timeFormat.format(date)
            isYesterday(date, now) -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
        }
    }

    fun formatMessageTime(ts: Timestamp?): String {
        val date = (ts ?: Timestamp.now()).toDate()
        return timeFormat.format(date)
    }

    fun formatLastSeen(ts: Timestamp?): String {
        if (ts == null) return "Last seen recently"
        val date = ts.toDate()
        val now = Date()
        val diffMins = TimeUnit.MILLISECONDS.toMinutes(now.time - date.time)
        return when {
            diffMins < 1 -> "Last seen just now"
            diffMins < 60 -> "Last seen ${diffMins}m ago"
            isSameDay(date, now) -> "Last seen today at ${timeFormat.format(date)}"
            isYesterday(date, now) -> "Last seen yesterday at ${timeFormat.format(date)}"
            else -> "Last seen ${dayTimeFormat.format(date)}"
        }
    }

    fun formatMemberSince(ts: Timestamp?): String {
        if (ts == null) return "Joined recently"
        return "Member since ${dateFormat.format(ts.toDate())}"
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun formatAudioDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    }

    fun formatCallDuration(totalSeconds: Long): String {
        val clamped = totalSeconds.coerceAtLeast(0L)
        val hours = clamped / 3600L
        val mins = (clamped % 3600L) / 60L
        val secs = clamped % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        }
    }

    private fun isSameDay(a: Date, b: Date): Boolean {
        val ca = Calendar.getInstance().apply { time = a }
        val cb = Calendar.getInstance().apply { time = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(date: Date, now: Date): Boolean {
        val yesterday = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val target = Calendar.getInstance().apply { time = date }
        return yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }
}
