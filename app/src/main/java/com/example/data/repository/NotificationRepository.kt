package com.example.data.repository

import android.os.Build
import android.util.Log
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.handleFirestoreError
import com.example.domain.model.AppNotification
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

class NotificationRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun observeNotifications(userId: String): Flow<List<AppNotification>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.NOTIFICATIONS
        db.collection(path)
            .whereEqualTo("recipientId", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(AppNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    suspend fun markNotificationRead(notificationId: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection(SnrConstants.Collection.NOTIFICATIONS).document(notificationId)
        return try {
            docRef.update("read", true).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun registerDeviceToken(token: String, appVersion: String = "1.0"): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        if (token.isBlank()) return Result.success(Unit)
        val deviceId = "${uid}_${Build.MODEL.replace(Regex("[^a-zA-Z0-9_]"), "_").take(32)}"
        val docRef = db.collection(SnrConstants.Collection.USER_DEVICES).document(deviceId)
        return try {
            val existing = db.collection(SnrConstants.Collection.USER_DEVICES)
                .whereEqualTo("userId", uid)
                .get()
                .await()
                .documents
                .any { it.id == deviceId }
            val now = FieldValue.serverTimestamp()
            if (existing) {
                docRef.update(
                    mapOf(
                        "fcmToken" to token.take(512),
                        "platform" to "android",
                        "appVersion" to appVersion.take(40),
                        "updatedAt" to now
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "fcmToken" to token.take(512),
                        "platform" to "android",
                        "appVersion" to appVersion.take(40),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NotificationRepo", "Device token registration skipped: ${e.message}")
            Result.failure(e)
        }
    }
}
