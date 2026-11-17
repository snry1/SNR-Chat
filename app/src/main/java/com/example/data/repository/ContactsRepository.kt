package com.example.data.repository

import com.example.core.FriendRequestPolicy
import com.example.core.FriendRequestStatus
import com.example.core.NotificationType
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.handleFirestoreError
import com.example.domain.model.FriendRequest
import com.example.domain.model.Friendship
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

class ContactsRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun friendshipIdFor(uid1: String, uid2: String): String {
        val sorted = listOf(uid1, uid2).sorted()
        return "${sorted[0]}_${sorted[1]}"
    }

    fun observeFriendships(userId: String): Flow<List<Friendship>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.FRIENDSHIPS
        db.collection(path)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(Friendship::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    fun observeFriendRequests(userId: String): Flow<List<FriendRequest>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.FRIEND_REQUESTS
        db.collection(path)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(FriendRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
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

    suspend fun sendFriendRequest(
        currentUser: UserProfile,
        targetUser: UserProfile
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        if (targetUser.userId == uid) {
            return Result.failure(IllegalArgumentException("You cannot send a friend request to yourself."))
        }
        if (targetUser.requestPolicyEnum() == FriendRequestPolicy.NO_ONE) {
            return Result.failure(IllegalStateException("${targetUser.displayName} is not accepting friend requests."))
        }

        val fromName = currentUser.displayName.trim().ifBlank { "SNR User" }.take(SnrConstants.MAX_DISPLAY_NAME_LEN)
        val toName = targetUser.displayName.trim().ifBlank { "SNR User" }.take(SnrConstants.MAX_DISPLAY_NAME_LEN)
        val fromUsername = currentUser.username.trim().lowercase().take(24)
        val toUsername = targetUser.username.trim().lowercase().take(24)
        if (fromUsername.length < 3) {
            return Result.failure(IllegalStateException("Please set a valid username in Profile before adding friends."))
        }
        if (toUsername.length < 3) {
            return Result.failure(IllegalStateException("Target user profile does not have a valid username."))
        }

        val reqId = "${uid}_${targetUser.userId}"
        val reqRef = db.collection(SnrConstants.Collection.FRIEND_REQUESTS).document(reqId)

        // 1. Check if current user blocked target user using scoped list query
        val blockedByMe = try {
            val blockedSnap = db.collection(SnrConstants.Collection.BLOCKED_USERS)
                .whereEqualTo("blockerId", uid)
                .get()
                .await()
            blockedSnap.documents.any { it.getString("blockedId") == targetUser.userId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.BLOCKED_USERS)
            return Result.failure(e)
        }
        if (blockedByMe) {
            return Result.failure(IllegalStateException("Unblock ${targetUser.displayName} before sending a friend request."))
        }

        // 2. Check existing friendships via scoped participantUids query
        val alreadyFriends = try {
            val friendSnap = db.collection(SnrConstants.Collection.FRIENDSHIPS)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
            friendSnap.documents.any { doc ->
                val participants = doc.get("participantUids") as? List<*>
                participants?.contains(targetUser.userId) == true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.FRIENDSHIPS)
            return Result.failure(e)
        }
        if (alreadyFriends) {
            return Result.failure(IllegalStateException("You are already friends with ${targetUser.displayName}."))
        }

        // 3. Check existing incoming/outgoing friend requests via scoped participantUids query
        val existingRequests = try {
            db.collection(SnrConstants.Collection.FRIEND_REQUESTS)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.FRIEND_REQUESTS)
            return Result.failure(e)
        }

        val reverseDoc = existingRequests.documents.firstOrNull {
            it.getString("fromUserId") == targetUser.userId && it.getString("toUserId") == uid
        }
        if (reverseDoc != null && reverseDoc.getString("status") == FriendRequestStatus.PENDING.name) {
            return Result.failure(
                IllegalStateException("${targetUser.displayName} already sent you a friend request. Accept it in Requests.")
            )
        }

        val existingDoc = existingRequests.documents.firstOrNull {
            it.getString("fromUserId") == uid && it.getString("toUserId") == targetUser.userId
        }
        if (existingDoc != null && existingDoc.getString("status") == FriendRequestStatus.PENDING.name) {
            return Result.failure(IllegalStateException("Friend request already pending."))
        }

        // 4. Create or re-open friend request
        val now = FieldValue.serverTimestamp()
        val opType = if (existingDoc != null) OperationType.UPDATE else OperationType.CREATE
        try {
            if (existingDoc != null) {
                reqRef.update(
                    mapOf(
                        "status" to FriendRequestStatus.PENDING.name,
                        "fromDisplayName" to fromName,
                        "fromUsername" to fromUsername,
                        "fromAvatarData" to currentUser.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "toDisplayName" to toName,
                        "toUsername" to toUsername,
                        "toAvatarData" to targetUser.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "updatedAt" to now
                    )
                ).await()
            } else {
                reqRef.set(
                    mapOf(
                        "fromUserId" to uid,
                        "toUserId" to targetUser.userId,
                        "fromDisplayName" to fromName,
                        "fromUsername" to fromUsername,
                        "fromAvatarData" to currentUser.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "toDisplayName" to toName,
                        "toUsername" to toUsername,
                        "toAvatarData" to targetUser.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                        "participantUids" to listOf(uid, targetUser.userId),
                        "status" to FriendRequestStatus.PENDING.name,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, opType, reqRef.path)
            return Result.failure(e)
        }

        // 5. Dispatch notification (best effort)
        val notifId = "fr_${uid}_${targetUser.userId}_${System.currentTimeMillis()}"
        runCatching {
            db.collection(SnrConstants.Collection.NOTIFICATIONS).document(notifId).set(
                mapOf(
                    "recipientId" to targetUser.userId,
                    "senderId" to uid,
                    "senderName" to fromName,
                    "type" to NotificationType.FRIEND_REQUEST.name,
                    "title" to "New Friend Request",
                    "body" to "$fromName (@$fromUsername) wants to connect.".take(300),
                    "targetId" to reqId,
                    "dedupeKey" to notifId,
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        return Result.success(Unit)
    }

    suspend fun acceptFriendRequest(
        request: FriendRequest,
        currentUser: UserProfile
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        if (request.toUserId != uid) {
            return Result.failure(IllegalStateException("Only the recipient can accept this request."))
        }
        val sorted = listOf(request.fromUserId, request.toUserId).sorted()
        val friendshipId = "${sorted[0]}_${sorted[1]}"
        val reqRef = db.collection(SnrConstants.Collection.FRIEND_REQUESTS).document(request.id)
        val friendshipRef = db.collection(SnrConstants.Collection.FRIENDSHIPS).document(friendshipId)

        return try {
            val alreadyFriends = db.collection(SnrConstants.Collection.FRIENDSHIPS)
                .whereArrayContains("participantUids", uid)
                .get()
                .await()
                .documents
                .any { it.id == friendshipId }

            db.runBatch { batch ->
                val now = FieldValue.serverTimestamp()
                batch.update(
                    reqRef,
                    mapOf(
                        "status" to FriendRequestStatus.ACCEPTED.name,
                        "updatedAt" to now
                    )
                )
                if (!alreadyFriends) {
                    batch.set(
                        friendshipRef,
                        mapOf(
                            "userAId" to sorted[0],
                            "userBId" to sorted[1],
                            "participantUids" to sorted,
                            "createdAt" to now
                        )
                    )
                }
            }.await()

            val notifId = "fa_${uid}_${request.fromUserId}_${System.currentTimeMillis()}"
            runCatching {
                db.collection(SnrConstants.Collection.NOTIFICATIONS).document(notifId).set(
                    mapOf(
                        "recipientId" to request.fromUserId,
                        "senderId" to uid,
                        "senderName" to currentUser.displayName.trim().ifBlank { "SNR User" }.take(50),
                        "type" to NotificationType.FRIEND_ACCEPTED.name,
                        "title" to "Friend Request Accepted",
                        "body" to "${currentUser.displayName.ifBlank { "Someone" }} accepted your friend request.".take(300),
                        "targetId" to uid,
                        "dedupeKey" to notifId,
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, reqRef.path)
            Result.failure(e)
        }
    }

    suspend fun rejectOrCancelRequest(requestId: String): Result<Unit> {
        requireUserId()
        val reqRef = db.collection(SnrConstants.Collection.FRIEND_REQUESTS).document(requestId)
        return try {
            reqRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, reqRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeFriend(peerUserId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val friendshipId = friendshipIdFor(uid, peerUserId)
        val friendshipRef = db.collection(SnrConstants.Collection.FRIENDSHIPS).document(friendshipId)
        return try {
            friendshipRef.delete().await()
            // Best-effort cleanup of related friend request records
            runCatching {
                val reqs = db.collection(SnrConstants.Collection.FRIEND_REQUESTS)
                    .whereArrayContains("participantUids", uid)
                    .get()
                    .await()
                val matching = reqs.documents.filter { doc ->
                    val participants = doc.get("participantUids") as? List<*>
                    participants?.contains(peerUserId) == true
                }
                if (matching.isNotEmpty()) {
                    db.runBatch { batch ->
                        matching.forEach { batch.delete(it.reference) }
                    }.await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, friendshipRef.path)
            Result.failure(e)
        }
    }
}
