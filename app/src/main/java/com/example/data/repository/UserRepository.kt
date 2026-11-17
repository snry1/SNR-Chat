package com.example.data.repository

import android.util.Log
import com.example.core.DarkModePreference
import com.example.core.FriendRequestPolicy
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.handleFirestoreError
import com.example.domain.model.BlockedUser
import com.example.domain.model.PrivateAccount
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
import kotlinx.coroutines.withContext

class UserRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in with Google.")

    fun normalizeUsername(raw: String): String =
        raw.trim().removePrefix("@").lowercase()

    fun observeUser(userId: String): Flow<UserProfile?> = flow {
        requireUserId()
        val path = "${SnrConstants.Collection.USERS}/$userId"
        db.collection(SnrConstants.Collection.USERS)
            .document(userId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                if (auth.currentUser != null) {
                    handleFirestoreError(fsError ?: error, OperationType.GET, path)
                }
                throw error
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    fun observePrivateAccount(userId: String): Flow<PrivateAccount?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Cannot read another user's private account." }
        val path = "${SnrConstants.Collection.USERS}/$userId/${SnrConstants.Collection.PRIVATE}/${SnrConstants.Collection.ACCOUNT_DOC}"
        db.collection(SnrConstants.Collection.USERS)
            .document(userId)
            .collection(SnrConstants.Collection.PRIVATE)
            .document(SnrConstants.Collection.ACCOUNT_DOC)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(PrivateAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
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

    suspend fun getUser(userId: String): Result<UserProfile?> = withContext(Dispatchers.IO) {
        try {
            requireUserId()
            val snap = db.collection(SnrConstants.Collection.USERS)
                .document(userId)
                .get()
                .await()
            val profile = if (snap.exists()) {
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "${SnrConstants.Collection.USERS}/$userId")
            Result.failure(e)
        }
    }

    suspend fun isUsernameAvailable(rawUsername: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val username = normalizeUsername(rawUsername)
        if (!SnrConstants.USERNAME_REGEX.matches(username)) {
            return@withContext Result.success(false)
        }
        try {
            val uid = requireUserId()
            val snap = db.collection(SnrConstants.Collection.USERNAMES)
                .document(username)
                .get()
                .await()
            val available = !snap.exists() || snap.getString("userId") == uid
            Result.success(available)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "${SnrConstants.Collection.USERNAMES}/$username")
            Result.failure(e)
        }
    }

    suspend fun createInitialProfile(
        displayName: String,
        rawUsername: String,
        bio: String,
        avatarData: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val email = auth.currentUser?.email.orEmpty().take(254)
        val cleanName = displayName.trim().take(SnrConstants.MAX_DISPLAY_NAME_LEN)
        val cleanUsername = normalizeUsername(rawUsername)
        val cleanBio = bio.trim().take(SnrConstants.MAX_BIO_LEN)
        val cleanAvatar = avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN)

        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Display name is required."))
        }
        if (!SnrConstants.USERNAME_REGEX.matches(cleanUsername)) {
            return Result.failure(
                IllegalArgumentException("Username must be 3-24 characters using a-z, 0-9, or _.")
            )
        }

        val userRef = db.collection(SnrConstants.Collection.USERS).document(uid)
        val usernameRef = db.collection(SnrConstants.Collection.USERNAMES).document(cleanUsername)
        val privateRef = userRef
            .collection(SnrConstants.Collection.PRIVATE)
            .document(SnrConstants.Collection.ACCOUNT_DOC)

        return try {
            db.runTransaction { tx ->
                val existingClaim = tx.get(usernameRef)
                if (existingClaim.exists() && existingClaim.getString("userId") != uid) {
                    throw IllegalStateException("Username @$cleanUsername is already taken.")
                }

                val now = FieldValue.serverTimestamp()
                tx.set(
                    usernameRef,
                    mapOf(
                        "userId" to uid,
                        "createdAt" to now
                    )
                )
                tx.set(
                    userRef,
                    mapOf(
                        "userId" to uid,
                        "username" to cleanUsername,
                        "displayName" to cleanName,
                        "displayNameLower" to cleanName.lowercase(),
                        "bio" to cleanBio,
                        "avatarData" to cleanAvatar,
                        "online" to true,
                        "lastSeenAt" to now,
                        "friendRequestPolicy" to FriendRequestPolicy.EVERYONE.name,
                        "showOnlineStatus" to true,
                        "showLastSeen" to true,
                        "showReadReceipts" to true,
                        "showMessagePreview" to true,
                        "notificationsEnabled" to true,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
                tx.set(
                    privateRef,
                    mapOf(
                        "userId" to uid,
                        "email" to email,
                        "darkModePreference" to DarkModePreference.DARK.name,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateProfile(
        currentUsername: String,
        displayName: String,
        rawNewUsername: String,
        bio: String,
        avatarData: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val cleanName = displayName.trim().take(SnrConstants.MAX_DISPLAY_NAME_LEN)
        val cleanNewUsername = normalizeUsername(rawNewUsername)
        val cleanBio = bio.trim().take(SnrConstants.MAX_BIO_LEN)
        val cleanAvatar = avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN)

        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Display name cannot be empty."))
        }
        if (!SnrConstants.USERNAME_REGEX.matches(cleanNewUsername)) {
            return Result.failure(
                IllegalArgumentException("Username must be 3-24 characters (a-z, 0-9, _).")
            )
        }

        val userRef = db.collection(SnrConstants.Collection.USERS).document(uid)
        val newUsernameRef = db.collection(SnrConstants.Collection.USERNAMES).document(cleanNewUsername)
        val oldUsernameRef = db.collection(SnrConstants.Collection.USERNAMES).document(currentUsername)

        return try {
            db.runTransaction { tx ->
                if (cleanNewUsername != currentUsername) {
                    val claim = tx.get(newUsernameRef)
                    if (claim.exists() && claim.getString("userId") != uid) {
                        throw IllegalStateException("Username @$cleanNewUsername is already taken.")
                    }
                    if (currentUsername.isNotBlank()) {
                        tx.delete(oldUsernameRef)
                    }
                    tx.set(
                        newUsernameRef,
                        mapOf(
                            "userId" to uid,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }

                tx.update(
                    userRef,
                    mapOf(
                        "username" to cleanNewUsername,
                        "displayName" to cleanName,
                        "displayNameLower" to cleanName.lowercase(),
                        "bio" to cleanBio,
                        "avatarData" to cleanAvatar,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updatePresence(online: Boolean): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val userRef = db.collection(SnrConstants.Collection.USERS).document(uid)
        return try {
            val snap = userRef.get().await()
            if (!snap.exists()) return Result.success(Unit)
            userRef.update(
                mapOf(
                    "online" to online,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("UserRepository", "Presence update skipped: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updatePrivacyAndNotifications(
        friendRequestPolicy: FriendRequestPolicy,
        showOnlineStatus: Boolean,
        showLastSeen: Boolean,
        showReadReceipts: Boolean,
        showMessagePreview: Boolean,
        notificationsEnabled: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val userRef = db.collection(SnrConstants.Collection.USERS).document(uid)
        return try {
            userRef.update(
                mapOf(
                    "friendRequestPolicy" to friendRequestPolicy.name,
                    "showOnlineStatus" to showOnlineStatus,
                    "showLastSeen" to showLastSeen,
                    "showReadReceipts" to showReadReceipts,
                    "showMessagePreview" to showMessagePreview,
                    "notificationsEnabled" to notificationsEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateDarkModePreference(pref: DarkModePreference): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val privateRef = db.collection(SnrConstants.Collection.USERS)
            .document(uid)
            .collection(SnrConstants.Collection.PRIVATE)
            .document(SnrConstants.Collection.ACCOUNT_DOC)
        return try {
            privateRef.update(
                mapOf(
                    "darkModePreference" to pref.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privateRef.path)
            Result.failure(e)
        }
    }

    suspend fun searchUsers(query: String, blockedUserIds: Set<String> = emptySet()): Result<List<UserProfile>> =
        withContext(Dispatchers.IO) {
            val uid = auth.currentUser?.uid
                ?: return@withContext Result.failure(IllegalStateException("User not signed in."))
            val cleaned = query.trim().removePrefix("@").lowercase()
            try {
                val usersCol = db.collection(SnrConstants.Collection.USERS)
                val results = mutableMapOf<String, UserProfile>()

                if (cleaned.isEmpty()) {
                    val snap = usersCol.limit(30).get().await()
                    snap.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .forEach { results[it.userId] = it }
                } else {
                    val exactUsername = usersCol.whereEqualTo("username", cleaned).limit(10).get().await()
                    exactUsername.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .forEach { results[it.userId] = it }

                    val prefixUsername = usersCol
                        .orderBy("username")
                        .startAt(cleaned)
                        .endAt(cleaned + "\uf8ff")
                        .limit(15)
                        .get()
                        .await()
                    prefixUsername.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .forEach { results[it.userId] = it }

                    val prefixName = usersCol
                        .orderBy("displayNameLower")
                        .startAt(cleaned)
                        .endAt(cleaned + "\uf8ff")
                        .limit(15)
                        .get()
                        .await()
                    prefixName.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .forEach { results[it.userId] = it }
                }

                val filtered = results.values
                    .filter { it.userId != uid && it.userId !in blockedUserIds }
                    .sortedBy { it.displayNameLower }
                Result.success(filtered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, SnrConstants.Collection.USERS)
                Result.failure(e)
            }
        }

    fun observeBlockedUsers(userId: String): Flow<List<BlockedUser>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.BLOCKED_USERS
        db.collection(path)
            .whereEqualTo("blockerId", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(BlockedUser::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
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

    suspend fun blockUser(target: UserProfile): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        if (target.userId == uid) {
            return Result.failure(IllegalArgumentException("Cannot block yourself."))
        }
        val blockId = "${uid}_${target.userId}"
        val docRef = db.collection(SnrConstants.Collection.BLOCKED_USERS).document(blockId)
        return try {
            docRef.set(
                mapOf(
                    "blockerId" to uid,
                    "blockedId" to target.userId,
                    "blockedDisplayName" to target.displayName.take(SnrConstants.MAX_DISPLAY_NAME_LEN),
                    "blockedUsername" to target.username.take(24),
                    "blockedAvatarData" to target.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun unblockUser(blockedUserId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val blockId = "${uid}_${blockedUserId}"
        val docRef = db.collection(SnrConstants.Collection.BLOCKED_USERS).document(blockId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun reportUser(
        reportedUserId: String,
        reason: String,
        details: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        if (reportedUserId == uid) {
            return Result.failure(IllegalArgumentException("Cannot report yourself."))
        }
        val docRef = db.collection(SnrConstants.Collection.USER_REPORTS).document()
        return try {
            docRef.set(
                mapOf(
                    "reporterId" to uid,
                    "reportedUserId" to reportedUserId,
                    "reason" to reason.trim().ifBlank { "Inappropriate behavior" }.take(80),
                    "details" to details.trim().take(500),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }
}
