package com.example.calls

import com.example.core.CallStatus
import com.example.core.OperationType
import com.example.core.SnrConstants
import com.example.core.handleFirestoreError
import com.example.domain.model.CallAudioChunk
import com.example.domain.model.CallIceCandidate
import com.example.domain.model.CallSession
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
import java.util.UUID

class CallRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun observeActiveCallsForUser(userId: String): Flow<List<CallSession>> = flow {
        val uid = requireUserId()
        check(uid == userId)
        val path = SnrConstants.Collection.CALLS
        db.collection(path)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter {
                        val s = it.statusEnum()
                        s == CallStatus.RINGING || s == CallStatus.CONNECTED
                    }
                    .sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
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

    fun observeCall(callId: String): Flow<CallSession?> = flow {
        val uid = requireUserId()
        val path = "${SnrConstants.Collection.CALLS}/$callId"
        db.collection(SnrConstants.Collection.CALLS)
            .document(callId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) {
                    snap.toObject(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    suspend fun createOutgoingCall(
        conversationId: String,
        caller: UserProfile,
        callee: UserProfile,
        offerSdp: String
    ): Result<CallSession> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not signed in."))
        val callId = "call_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val participants = listOf(uid, callee.userId).sorted()
        val callerName = caller.displayName.trim().ifBlank { "Dayunan User" }.take(50)
        val calleeName = callee.displayName.trim().ifBlank { "Dayunan User" }.take(50)
        val docRef = db.collection(SnrConstants.Collection.CALLS).document(callId)

        return try {
            val now = FieldValue.serverTimestamp()
            docRef.set(
                mapOf(
                    "callerId" to uid,
                    "callerName" to callerName,
                    "callerAvatarData" to caller.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                    "calleeId" to callee.userId,
                    "calleeName" to calleeName,
                    "calleeAvatarData" to callee.avatarData.take(SnrConstants.MAX_AVATAR_DATA_LEN),
                    "conversationId" to conversationId,
                    "participantUids" to participants,
                    "status" to CallStatus.RINGING.name,
                    "offerSdp" to offerSdp.take(20_000),
                    "answerSdp" to "",
                    "endedBy" to "",
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()

            Result.success(
                CallSession(
                    id = callId,
                    callerId = uid,
                    callerName = callerName,
                    callerAvatarData = caller.avatarData,
                    calleeId = callee.userId,
                    calleeName = calleeName,
                    calleeAvatarData = callee.avatarData,
                    conversationId = conversationId,
                    participantUids = participants,
                    status = CallStatus.RINGING.name,
                    offerSdp = offerSdp
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun answerCall(callId: String, answerSdp: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection(SnrConstants.Collection.CALLS).document(callId)
        return try {
            val now = FieldValue.serverTimestamp()
            docRef.update(
                mapOf(
                    "status" to CallStatus.CONNECTED.name,
                    "answerSdp" to answerSdp.take(20_000),
                    "connectedAt" to now,
                    "updatedAt" to now
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun finishCall(callId: String, finalStatus: CallStatus): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val safeStatus = when (finalStatus) {
            CallStatus.DECLINED, CallStatus.MISSED -> finalStatus
            else -> CallStatus.ENDED
        }
        val docRef = db.collection(SnrConstants.Collection.CALLS).document(callId)
        return try {
            val now = FieldValue.serverTimestamp()
            docRef.update(
                mapOf(
                    "status" to safeStatus.name,
                    "endedBy" to uid,
                    "endedAt" to now,
                    "updatedAt" to now
                )
            ).await()
            deleteAllAudioChunks(callId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun addIceCandidate(
        callId: String,
        participantUids: List<String>,
        sdpMid: String,
        sdpMLineIndex: Int,
        sdp: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val candId = "cand_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val candRef = db.collection(SnrConstants.Collection.CALLS)
            .document(callId)
            .collection(SnrConstants.Collection.CANDIDATES)
            .document(candId)

        return try {
            candRef.set(
                mapOf(
                    "callId" to callId,
                    "senderId" to uid,
                    "participantUids" to participantUids,
                    "sdpMid" to sdpMid.take(64),
                    "sdpMLineIndex" to sdpMLineIndex.coerceIn(0, 64),
                    "sdp" to sdp.take(4000),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    fun observeRemoteCandidates(callId: String, currentUserId: String): Flow<List<CallIceCandidate>> = flow {
        val uid = requireUserId()
        check(uid == currentUserId)
        val path = "${SnrConstants.Collection.CALLS}/$callId/${SnrConstants.Collection.CANDIDATES}"
        db.collection(SnrConstants.Collection.CALLS)
            .document(callId)
            .collection(SnrConstants.Collection.CANDIDATES)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(CallIceCandidate::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.senderId != uid }
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

    suspend fun sendAudioChunk(
        callId: String,
        participantUids: List<String>,
        seq: Long,
        sampleRate: Int,
        predSample: Int,
        stepIndex: Int,
        payload: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        if (payload.isBlank() || payload.length > 16000) return
        val chunkId = "a_${uid.take(8)}_$seq"
        val chunkRef = db.collection(SnrConstants.Collection.CALLS)
            .document(callId)
            .collection(SnrConstants.Collection.AUDIO_CHUNKS)
            .document(chunkId)

        runCatching {
            chunkRef.set(
                mapOf(
                    "callId" to callId,
                    "senderId" to uid,
                    "participantUids" to participantUids,
                    "seq" to seq,
                    "sampleRate" to sampleRate,
                    "predSample" to predSample.coerceIn(-32768, 32767),
                    "stepIndex" to stepIndex.coerceIn(0, 88),
                    "payload" to payload,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    fun observeRemoteAudioChunks(callId: String, currentUserId: String): Flow<List<CallAudioChunk>> = flow {
        val uid = requireUserId()
        check(uid == currentUserId)
        db.collection(SnrConstants.Collection.CALLS)
            .document(callId)
            .collection(SnrConstants.Collection.AUDIO_CHUNKS)
            .whereArrayContains("participantUids", uid)
            .snapshots()
            .map { snap ->
                snap.toObjects(CallAudioChunk::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .filter { it.senderId != uid }
                    .sortedBy { it.seq }
            }
            .catch { error ->
                val fsError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsError == null && error is CancellationException) throw error
                emit(emptyList())
            }
            .collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    suspend fun deleteAudioChunk(callId: String, chunkId: String) {
        if (auth.currentUser == null) return
        runCatching {
            db.collection(SnrConstants.Collection.CALLS)
                .document(callId)
                .collection(SnrConstants.Collection.AUDIO_CHUNKS)
                .document(chunkId)
                .delete()
                .await()
        }
    }

    suspend fun deleteAllAudioChunks(callId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val snap = db.collection(SnrConstants.Collection.CALLS)
                .document(callId)
                .collection(SnrConstants.Collection.AUDIO_CHUNKS)
                .whereArrayContains("participantUids", uid)
                .limit(200)
                .get()
                .await()
            if (!snap.isEmpty) {
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
    }
}
