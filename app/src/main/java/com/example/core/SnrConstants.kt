package com.example.core

object SnrConstants {
    val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")
    const val MAX_DISPLAY_NAME_LEN = 50
    const val MAX_BIO_LEN = 200
    const val MAX_MESSAGE_LEN = 4000
    const val MAX_GROUP_NAME_LEN = 60
    const val MAX_GROUP_DESC_LEN = 300
    const val MAX_AVATAR_DATA_LEN = 240_000
    const val MAX_MEDIA_DATA_LEN = 620_000
    const val MAX_RAW_FILE_BYTES = 420_000L

    object Collection {
        const val USERS = "users"
        const val PRIVATE = "private"
        const val ACCOUNT_DOC = "account"
        const val USERNAMES = "usernames"
        const val FRIEND_REQUESTS = "friend_requests"
        const val FRIENDSHIPS = "friendships"
        const val CONVERSATIONS = "conversations"
        const val MESSAGES = "messages"
        const val GROUPS = "groups"
        const val GROUP_MEMBERS = "group_members"
        const val GROUP_INVITATIONS = "group_invitations"
        const val NOTIFICATIONS = "notifications"
        const val USER_DEVICES = "user_devices"
        const val BLOCKED_USERS = "blocked_users"
        const val USER_REPORTS = "user_reports"
        const val CALLS = "calls"
        const val CANDIDATES = "candidates"
        const val AUDIO_CHUNKS = "audio_chunks"
    }
}

enum class ConversationType {
    DIRECT, GROUP;

    companion object {
        fun fromRaw(raw: String): ConversationType =
            entries.firstOrNull { it.name == raw } ?: DIRECT
    }
}

enum class MessageType {
    TEXT, IMAGE, FILE, AUDIO, SYSTEM;

    companion object {
        fun fromRaw(raw: String): MessageType =
            entries.firstOrNull { it.name == raw } ?: TEXT
    }
}

enum class CallStatus {
    RINGING, CONNECTED, ENDED, DECLINED, MISSED;

    companion object {
        fun fromRaw(raw: String): CallStatus =
            entries.firstOrNull { it.name == raw } ?: ENDED
    }
}

enum class FriendRequestPolicy(val label: String) {
    EVERYONE("Everyone"),
    NO_ONE("Nobody");

    companion object {
        fun fromRaw(raw: String): FriendRequestPolicy =
            entries.firstOrNull { it.name == raw } ?: EVERYONE
    }
}

enum class FriendRequestStatus {
    PENDING, ACCEPTED, REJECTED;

    companion object {
        fun fromRaw(raw: String): FriendRequestStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class GroupRole(val label: String) {
    OWNER("Owner"),
    ADMIN("Admin"),
    MEMBER("Member");

    companion object {
        fun fromRaw(raw: String): GroupRole =
            entries.firstOrNull { it.name == raw } ?: MEMBER
    }
}

enum class GroupInvitationStatus {
    PENDING, ACCEPTED, DECLINED;

    companion object {
        fun fromRaw(raw: String): GroupInvitationStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class NotificationType {
    PERSONAL_MESSAGE, GROUP_MESSAGE, FRIEND_REQUEST, FRIEND_ACCEPTED, GROUP_INVITE;

    companion object {
        fun fromRaw(raw: String): NotificationType =
            entries.firstOrNull { it.name == raw } ?: PERSONAL_MESSAGE
    }
}

enum class DarkModePreference(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light Mode"),
    DARK("Dark Mode");

    companion object {
        fun fromRaw(raw: String): DarkModePreference =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}
