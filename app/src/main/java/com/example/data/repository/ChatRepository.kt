package com.example.data.repository

import com.example.core.ConversationType
import com.example.core.MessageType
import com.example.core.NotificationType
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.TimeFormatters
import com.example.core.handleFirestoreError
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun directConversationId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA, uidB).sorted()
        return "dm_${sorted[0]}_${sorted[1]}"
    }

    fun observeConversations(userId: String): Flow<List<Conversation>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.CONVERSATIONS
        db.collection(path)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending {
                        it.lastMessageAt?.seconds ?: it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
                    }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    fun observeConversation(conversationId: String): Flow<Conversation?> = flow {
        val uid = requireUserId()
        val path = "${SnrConstants.Collection.CONVERSATIONS}/$conversationId"
        db.collection(SnrConstants.Collection.CONVERSATIONS)
            .document(conversationId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.GET, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    suspend fun getOrCreateDirectConversation(peerUser: UserProfile): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val convId = directConversationId(uid, peerUser.userId)
        val docRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(convId)

        val exists = try {
            db.collection(SnrConstants.Collection.CONVERSATIONS)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
                .documents
                .any { it.id == convId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.CONVERSATIONS)
            return Result.failure(e)
        }

        if (!exists) {
            try {
                val participants = listOf(uid, peerUser.userId).sorted()
                val now = FieldValue.serverTimestamp()
                docRef.set(
                    mapOf(
                        "type" to ConversationType.DIRECT.name,
                        "participantUids" to participants,
                        "title" to "",
                        "description" to "",
                        "avatarData" to "",
                        "ownerId" to uid,
                        "adminUids" to emptyList<String>(),
                        "lastMessageText" to "",
                        "lastMessageType" to MessageType.TEXT.name,
                        "lastMessageSenderId" to "",
                        "typingUids" to emptyList<String>(),
                        "unreadCounts" to mapOf(uid to 0L, peerUser.userId to 0L),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path)
                return Result.failure(e)
            }
        }
        return Result.success(convId)
    }

    fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = "${SnrConstants.Collection.CONVERSATIONS}/$conversationId/${SnrConstants.Collection.MESSAGES}"
        db.collection(SnrConstants.Collection.CONVERSATIONS)
            .document(conversationId)
            .collection(SnrConstants.Collection.MESSAGES)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedWith(
                        compareBy<ChatMessage> { it.createdAt?.seconds ?: Long.MAX_VALUE }
                            .thenBy { it.createdAt?.nanoseconds ?: Int.MAX_VALUE }
                    )
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(fsError ?: error, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    suspend fun sendMessage(
        conversation: Conversation,
        sender: UserProfile,
        type: MessageType,
        text: String,
        mediaData: String = "",
        fileName: String = "",
        fileMimeType: String = "",
        fileSizeBytes: Long = 0L,
        audioDurationMs: Long = 0L,
        replyTo: ChatMessage? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val cleanText = text.trim().take(SnrConstants.MAX_MESSAGE_LEN)
        if (type == MessageType.TEXT && cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(conversation.id)
        val msgRef = convRef.collection(SnrConstants.Collection.MESSAGES).document()

        val preview = when (type) {
            MessageType.IMAGE -> if (cleanText.isNotBlank()) "Photo: $cleanText" else "Sent a photo"
            MessageType.FILE -> if (fileName.isNotBlank()) "File: $fileName" else "Sent a file"
            MessageType.AUDIO -> "Voice message (${TimeFormatters.formatAudioDuration(audioDurationMs)})"
            MessageType.SYSTEM -> cleanText
            MessageType.TEXT -> cleanText
        }.take(500)

        val replySnippet = replyTo?.let {
            when {
                it.deleted -> "Message deleted"
                it.typeEnum() == MessageType.IMAGE -> "Photo"
                it.typeEnum() == MessageType.FILE -> it.fileName.ifBlank { "File" }
                it.typeEnum() == MessageType.AUDIO -> "Voice message (${TimeFormatters.formatAudioDuration(it.audioDurationMs)})"
                else -> it.text
            }.take(190)
        }.orEmpty()

        val newUnread = conversation.unreadCounts.toMutableMap()
        conversation.participantUids.forEach { participantId ->
            if (participantId == uid) {
                newUnread[participantId] = 0L
            } else {
                newUnread[participantId] = (newUnread[participantId] ?: 0L) + 1L
            }
        }

        return try {
            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.set(
                    msgRef,
                    mapOf(
                        "conversationId" to conversation.id,
                        "senderId" to uid,
                        "senderName" to sender.displayName.take(50).ifBlank { "Dayunan User" },
                        "senderAvatarData" to sender.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "participantUids" to conversation.participantUids,
                        "type" to type.name,
                        "text" to cleanText,
                        "mediaData" to mediaData.take(SnrConstants.MAX_MEDIA_DATA_LEN),
                        "fileName" to fileName.take(160),
                        "fileMimeType" to fileMimeType.take(100),
                        "fileSizeBytes" to fileSizeBytes.coerceIn(0L, 10_485_760L),
                        "audioDurationMs" to audioDurationMs.coerceIn(0L, 600_000L),
                        "replyToMessageId" to (replyTo?.id ?: "").take(128),
                        "replyToSenderName" to (replyTo?.senderName ?: "").take(50),
                        "replyToSnippet" to replySnippet,
                        "deliveredTo" to listOf(uid),
                        "readBy" to listOf(uid),
                        "deleted" to false,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
                batch.update(
                    convRef,
                    mapOf(
                        "lastMessageText" to preview,
                        "lastMessageType" to type.name,
                        "lastMessageSenderId" to uid,
                        "lastMessageAt" to now,
                        "unreadCounts" to newUnread,
                        "updatedAt" to now
                    )
                )
            }.await()

            // Dispatch notifications to other participants
            val notifType = if (conversation.typeEnum() == ConversationType.GROUP) {
                NotificationType.GROUP_MESSAGE
            } else {
                NotificationType.PERSONAL_MESSAGE
            }
            val notifTitle = if (conversation.typeEnum() == ConversationType.GROUP) {
                "${sender.displayName} in ${conversation.title.ifBlank { "Group" }}"
            } else {
                sender.displayName
            }.take(100)

            conversation.participantUids
                .filter { it != uid }
                .take(20)
                .forEach { recipientId ->
                    val notifId = "msg_${msgRef.id}_${recipientId}"
                    runCatching {
                        db.collection(SnrConstants.Collection.NOTIFICATIONS).document(notifId).set(
                            mapOf(
                                "recipientId" to recipientId,
                                "senderId" to uid,
                                "senderName" to sender.displayName.take(50),
                                "type" to notifType.name,
                                "title" to notifTitle,
                                "body" to preview.take(280),
                                "targetId" to conversation.id,
                                "dedupeKey" to notifId,
                                "read" to false,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun markConversationRead(
        conversation: Conversation,
        messages: List<ChatMessage>,
        currentUser: UserProfile
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(conversation.id)
        return try {
            val currentUnread = conversation.unreadFor(uid)
            val unreadMessages = messages.filter {
                it.senderId != uid && (!it.deliveredTo.contains(uid) || (currentUser.showReadReceipts && !it.readBy.contains(uid)))
            }
            if (currentUnread == 0L && unreadMessages.isEmpty()) {
                return Result.success(Unit)
            }

            db.runBatch { batch ->
                if (currentUnread > 0L) {
                    val updatedMap = conversation.unreadCounts.toMutableMap()
                    updatedMap[uid] = 0L
                    batch.update(
                        convRef,
                        mapOf(
                            "unreadCounts" to updatedMap,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                unreadMessages.take(50).forEach { msg ->
                    val msgRef = convRef.collection(SnrConstants.Collection.MESSAGES).document(msg.id)
                    val nextDelivered = (msg.deliveredTo + uid).distinct().take(100)
                    val nextRead = if (currentUser.showReadReceipts) {
                        (msg.readBy + uid).distinct().take(100)
                    } else {
                        msg.readBy
                    }
                    batch.update(
                        msgRef,
                        mapOf(
                            "deliveredTo" to nextDelivered,
                            "readBy" to nextRead,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path)
            Result.failure(e)
        }
    }

    suspend fun setTyping(conversation: Conversation, isTyping: Boolean): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val currentlyTyping = conversation.typingUids.contains(uid)
        if (currentlyTyping == isTyping) return Result.success(Unit)
        val nextTyping = if (isTyping) {
            (conversation.typingUids + uid).distinct().take(20)
        } else {
            conversation.typingUids.filter { it != uid }
        }
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(conversation.id)
        return try {
            convRef.update(
                mapOf(
                    "typingUids" to nextTyping,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun softDeleteMessage(conversationId: String, messageId: String): Result<Unit> {
        requireUserId()
        val msgRef = db.collection(SnrConstants.Collection.CONVERSATIONS)
            .document(conversationId)
            .collection(SnrConstants.Collection.MESSAGES)
            .document(messageId)
        return try {
            msgRef.update(
                mapOf(
                    "deleted" to true,
                    "text" to "",
                    "mediaData" to "",
                    "fileName" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteDirectConversation(conversationId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val convRef = db.collection(SnrConstants.Collection.CONVERSATIONS).document(conversationId)
        return try {
            val messagesSnap = convRef.collection(SnrConstants.Collection.MESSAGES)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
            messagesSnap.documents.chunked(400).forEach { chunk ->
                db.runBatch { batch ->
                    chunk.forEach { doc -> batch.delete(doc.reference) }
                }.await()
            }
            convRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, convRef.path)
            Result.failure(e)
        }
    }
}
