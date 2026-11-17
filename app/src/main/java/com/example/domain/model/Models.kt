package com.example.domain.model

import androidx.annotation.Keep
import com.example.core.CallStatus
import com.example.core.ConversationType
import com.example.core.DarkModePreference
import com.example.core.FriendRequestPolicy
import com.example.core.FriendRequestStatus
import com.example.core.GroupInvitationStatus
import com.example.core.GroupRole
import com.example.core.MessageType
import com.example.core.NotificationType
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val displayNameLower: String = "",
    val bio: String = "",
    val avatarData: String = "",
    val online: Boolean = false,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    val friendRequestPolicy: String = FriendRequestPolicy.EVERYONE.name,
    val showOnlineStatus: Boolean = true,
    val showLastSeen: Boolean = true,
    val showReadReceipts: Boolean = true,
    val showMessagePreview: Boolean = true,
    val notificationsEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun requestPolicyEnum(): FriendRequestPolicy = FriendRequestPolicy.fromRaw(friendRequestPolicy)
}

@Keep
@IgnoreExtraProperties
data class PrivateAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val darkModePreference: String = DarkModePreference.SYSTEM.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun darkModeEnum(): DarkModePreference = DarkModePreference.fromRaw(darkModePreference)
}

@Keep
@IgnoreExtraProperties
data class FriendRequest(
    @DocumentId val id: String = "",
    val fromUserId: String = "",
    val toUserId: String = "",
    val fromDisplayName: String = "",
    val fromUsername: String = "",
    val fromAvatarData: String = "",
    val toDisplayName: String = "",
    val toUsername: String = "",
    val toAvatarData: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = FriendRequestStatus.PENDING.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): FriendRequestStatus = FriendRequestStatus.fromRaw(status)
}

@Keep
@IgnoreExtraProperties
data class Friendship(
    @DocumentId val id: String = "",
    val userAId: String = "",
    val userBId: String = "",
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun peerId(currentUserId: String): String =
        if (userAId == currentUserId) userBId else userAId
}

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val type: String = ConversationType.DIRECT.name,
    val participantUids: List<String> = emptyList(),
    val title: String = "",
    val description: String = "",
    val avatarData: String = "",
    val ownerId: String = "",
    val adminUids: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageType: String = MessageType.TEXT.name,
    val lastMessageSenderId: String = "",
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    val typingUids: List<String> = emptyList(),
    val unreadCounts: Map<String, Long> = emptyMap(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun typeEnum(): ConversationType = ConversationType.fromRaw(type)
    fun lastTypeEnum(): MessageType = MessageType.fromRaw(lastMessageType)
    fun unreadFor(uid: String): Long = (unreadCounts[uid] as? Number)?.toLong() ?: 0L
    fun peerIdForDirect(currentUserId: String): String =
        participantUids.firstOrNull { it != currentUserId } ?: currentUserId
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val senderAvatarData: String = "",
    val participantUids: List<String> = emptyList(),
    val type: String = MessageType.TEXT.name,
    val text: String = "",
    val mediaData: String = "",
    val fileName: String = "",
    val fileMimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val audioDurationMs: Long = 0L,
    val replyToMessageId: String = "",
    val replyToSenderName: String = "",
    val replyToSnippet: String = "",
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val deleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun typeEnum(): MessageType = MessageType.fromRaw(type)
}

@Keep
@IgnoreExtraProperties
data class GroupInfo(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val avatarData: String = "",
    val ownerId: String = "",
    val adminUids: List<String> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val conversationId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GroupMember(
    @DocumentId val id: String = "",
    val groupId: String = "",
    val userId: String = "",
    val displayName: String = "",
    val username: String = "",
    val avatarData: String = "",
    val role: String = GroupRole.MEMBER.name,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun roleEnum(): GroupRole = GroupRole.fromRaw(role)
}

@Keep
@IgnoreExtraProperties
data class GroupInvitation(
    @DocumentId val id: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val groupAvatarData: String = "",
    val fromUserId: String = "",
    val fromDisplayName: String = "",
    val toUserId: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = GroupInvitationStatus.PENDING.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): GroupInvitationStatus = GroupInvitationStatus.fromRaw(status)
}

@Keep
@IgnoreExtraProperties
data class AppNotification(
    @DocumentId val id: String = "",
    val recipientId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val type: String = NotificationType.PERSONAL_MESSAGE.name,
    val title: String = "",
    val body: String = "",
    val targetId: String = "",
    val dedupeKey: String = "",
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun typeEnum(): NotificationType = NotificationType.fromRaw(type)
}

@Keep
@IgnoreExtraProperties
data class BlockedUser(
    @DocumentId val id: String = "",
    val blockerId: String = "",
    val blockedId: String = "",
    val blockedDisplayName: String = "",
    val blockedUsername: String = "",
    val blockedAvatarData: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerAvatarData: String = "",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeAvatarData: String = "",
    val conversationId: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = CallStatus.RINGING.name,
    val offerSdp: String = "",
    val answerSdp: String = "",
    val endedBy: String = "",
    @ServerTimestamp val connectedAt: Timestamp? = null,
    @ServerTimestamp val endedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): CallStatus = CallStatus.fromRaw(status)
    fun peerId(currentUserId: String): String =
        if (callerId == currentUserId) calleeId else callerId
    fun peerName(currentUserId: String): String =
        if (callerId == currentUserId) calleeName else callerName
    fun peerAvatar(currentUserId: String): String =
        if (callerId == currentUserId) calleeAvatarData else callerAvatarData
}

@Keep
@IgnoreExtraProperties
data class CallIceCandidate(
    @DocumentId val id: String = "",
    val callId: String = "",
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val sdp: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallAudioChunk(
    @DocumentId val id: String = "",
    val callId: String = "",
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    val seq: Long = 0L,
    val sampleRate: Int = 8000,
    val predSample: Int = 0,
    val stepIndex: Int = 0,
    val payload: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)


