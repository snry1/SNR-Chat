package com.example.calls

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import com.example.core.CallStatus
import com.example.core.MessageType
import com.example.core.TimeFormatters
import com.example.core.VoicePlayer
import com.example.core.toFriendlyMessage
import com.example.data.repository.ChatRepository
import com.example.data.repository.UserRepository
import com.example.domain.model.CallSession
import com.example.domain.model.Conversation
import com.example.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

data class ActiveCallUiState(
    val callId: String,
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val peerAvatarData: String,
    val isIncoming: Boolean,
    val status: CallStatus,
    val statusSubtitle: String,
    val elapsedSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val errorMessage: String? = null
)

class CallController(
    private val context: Context,
    private val callRepository: CallRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val voicePlayer: VoicePlayer
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activeCall = MutableStateFlow<ActiveCallUiState?>(null)
    val activeCall: StateFlow<ActiveCallUiState?> = _activeCall.asStateFlow()

    private val _callNotice = MutableStateFlow<String?>(null)
    val callNotice: StateFlow<String?> = _callNotice.asStateFlow()

    private var observedUserId: String? = null
    private var incomingCallsJob: Job? = null
    private var callSessionJob: Job? = null
    private var candidatesJob: Job? = null
    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null

    private var currentSession: CallSession? = null
    private var engine: WebRtcCallEngine? = null
    private var ringtone: Ringtone? = null
    private var answerAppliedForCallId: String? = null
    private val processedCandidateIds = mutableSetOf<String>()
    private var pendingConversation: Conversation? = null
    private var pendingSender: UserProfile? = null

    fun startObservingUser(userId: String) {
        if (observedUserId == userId && incomingCallsJob != null) return
        stopObservingUser()
        observedUserId = userId

        incomingCallsJob = scope.launch {
            callRepository.observeActiveCallsForUser(userId)
                .catch { /* handled safely when signed out */ }
                .collect { calls ->
                    val currentId = currentSession?.id
                    if (currentId != null) return@collect
                    val incoming = calls.firstOrNull {
                        it.calleeId == userId && it.statusEnum() == CallStatus.RINGING
                    }
                    if (incoming != null) {
                        presentIncomingCall(incoming)
                    }
                }
        }
    }

    fun stopObservingUser() {
        incomingCallsJob?.cancel()
        incomingCallsJob = null
        observedUserId = null
        teardownActiveCall()
    }

    fun clearNotice() {
        _callNotice.value = null
    }

    fun startOutgoingCall(
        conversation: Conversation,
        caller: UserProfile,
        callee: UserProfile
    ) {
        if (_activeCall.value != null) {
            _callNotice.value = "A call is already in progress."
            return
        }
        voicePlayer.stop()
        pendingConversation = conversation
        pendingSender = caller

        val tempUi = ActiveCallUiState(
            callId = "pending",
            conversationId = conversation.id,
            peerId = callee.userId,
            peerName = callee.displayName.ifBlank { "Dayunan User" },
            peerAvatarData = callee.avatarData,
            isIncoming = false,
            status = CallStatus.RINGING,
            statusSubtitle = "Connecting..."
        )
        _activeCall.value = tempUi

        scope.launch {
            try {
                val participants = listOf(caller.userId, callee.userId).sorted()
                var activeCallId: String? = null
                val bufferedLocalCandidates = mutableListOf<Triple<String, Int, String>>()

                val rtcEngine = WebRtcCallEngine(
                    context = appContext,
                    onLocalIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                        val cId = activeCallId
                        if (cId != null) {
                            scope.launch(Dispatchers.IO) {
                                callRepository.addIceCandidate(cId, participants, sdpMid, sdpMLineIndex, sdp)
                            }
                        } else {
                            synchronized(bufferedLocalCandidates) {
                                bufferedLocalCandidates.add(Triple(sdpMid, sdpMLineIndex, sdp))
                            }
                        }
                    },
                    onConnectionStateChanged = { iceState ->
                        scope.launch {
                            when (iceState) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    startElapsedTimerIfNeeded()
                                    _activeCall.value = _activeCall.value?.copy(
                                        status = CallStatus.CONNECTED,
                                        statusSubtitle = TimeFormatters.formatCallDuration(
                                            _activeCall.value?.elapsedSeconds ?: 0L
                                        )
                                    )
                                }
                                PeerConnection.IceConnectionState.FAILED -> {
                                    _callNotice.value = "Voice connection lost."
                                    hangUp()
                                }
                                else -> Unit
                            }
                        }
                    }
                )
                engine = rtcEngine
                val offerSdp = withContext(Dispatchers.Default) {
                    rtcEngine.initializeSession()
                    rtcEngine.createOfferSdp()
                }
                val created = callRepository.createOutgoingCall(
                    conversationId = conversation.id,
                    caller = caller,
                    callee = callee,
                    offerSdp = offerSdp
                )

                created.onSuccess { session ->
                    activeCallId = session.id
                    currentSession = session
                    _activeCall.value = tempUi.copy(
                        callId = session.id,
                        statusSubtitle = "Calling..."
                    )

                    synchronized(bufferedLocalCandidates) {
                        bufferedLocalCandidates.forEach { (mid, idx, sdp) ->
                            scope.launch(Dispatchers.IO) {
                                callRepository.addIceCandidate(session.id, participants, mid, idx, sdp)
                            }
                        }
                        bufferedLocalCandidates.clear()
                    }

                    observeCallAndCandidates(session.id, caller.userId)
                    startUnansweredRingTimeout(session.id)
                }.onFailure { err ->
                    teardownActiveCall()
                    _callNotice.value = err.toFriendlyMessage("Could not place call.")
                }
            } catch (e: Exception) {
                teardownActiveCall()
                _callNotice.value = e.toFriendlyMessage("Could not initialize voice call.")
            }
        }
    }

    private fun presentIncomingCall(session: CallSession) {
        voicePlayer.stop()
        currentSession = session
        answerAppliedForCallId = null
        _activeCall.value = ActiveCallUiState(
            callId = session.id,
            conversationId = session.conversationId,
            peerId = session.callerId,
            peerName = session.callerName.ifBlank { "Dayunan User" },
            peerAvatarData = session.callerAvatarData,
            isIncoming = true,
            status = CallStatus.RINGING,
            statusSubtitle = "Incoming voice call"
        )
        startRingtone()
        observeCallAndCandidates(session.id, session.calleeId)
    }

    fun acceptIncomingCall() {
        val session = currentSession ?: return
        val uid = observedUserId ?: return
        if (session.statusEnum() != CallStatus.RINGING) return

        stopRingtone()
        _activeCall.value = _activeCall.value?.copy(
            status = CallStatus.CONNECTED,
            statusSubtitle = "Connecting..."
        )

        scope.launch {
            try {
                val rtcEngine = WebRtcCallEngine(
                    context = appContext,
                    onLocalIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                        scope.launch(Dispatchers.IO) {
                            callRepository.addIceCandidate(
                                session.id,
                                session.participantUids,
                                sdpMid,
                                sdpMLineIndex,
                                sdp
                            )
                        }
                    },
                    onConnectionStateChanged = { iceState ->
                        scope.launch {
                            when (iceState) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    startElapsedTimerIfNeeded()
                                }
                                PeerConnection.IceConnectionState.FAILED -> {
                                    _callNotice.value = "Voice connection lost."
                                    hangUp()
                                }
                                else -> Unit
                            }
                        }
                    }
                )
                engine = rtcEngine
                val answerSdp = withContext(Dispatchers.Default) {
                    rtcEngine.initializeSession()
                    rtcEngine.applyRemoteDescription(SessionDescription.Type.OFFER, session.offerSdp)
                    rtcEngine.createAnswerSdp()
                }

                callRepository.answerCall(session.id, answerSdp)
                    .onSuccess {
                        startElapsedTimerIfNeeded()
                    }
                    .onFailure { err ->
                        _callNotice.value = err.toFriendlyMessage("Failed to answer call.")
                        hangUp()
                    }
            } catch (e: Exception) {
                _callNotice.value = e.toFriendlyMessage("Unable to connect voice call.")
                hangUp()
            }
        }
    }

    fun declineIncomingCall() {
        val session = currentSession ?: return
        stopRingtone()
        scope.launch {
            callRepository.finishCall(session.id, CallStatus.DECLINED)
            teardownActiveCall()
        }
    }

    fun hangUp() {
        val session = currentSession
        val ui = _activeCall.value
        val elapsed = ui?.elapsedSeconds ?: 0L
        val wasConnected = ui?.status == CallStatus.CONNECTED || elapsed > 0L
        val isCaller = session?.callerId == observedUserId
        val conv = pendingConversation
        val sender = pendingSender

        stopRingtone()
        teardownActiveCall()

        if (session != null) {
            val endStatus = if (!wasConnected && isCaller && ui?.status == CallStatus.RINGING) {
                CallStatus.MISSED
            } else {
                CallStatus.ENDED
            }
            scope.launch(Dispatchers.IO) {
                callRepository.finishCall(session.id, endStatus)
                if (isCaller && conv != null && sender != null) {
                    val summary = if (wasConnected && elapsed > 0L) {
                        "Voice call • ${TimeFormatters.formatCallDuration(elapsed)}"
                    } else if (endStatus == CallStatus.MISSED) {
                        "Missed voice call"
                    } else {
                        "Voice call ended"
                    }
                    runCatching {
                        chatRepository.sendMessage(
                            conversation = conv,
                            sender = sender,
                            type = MessageType.SYSTEM,
                            text = summary
                        )
                    }
                }
            }
        }
    }

    fun toggleMute() {
        val current = _activeCall.value ?: return
        val nextMuted = !current.isMuted
        engine?.setMuted(nextMuted)
        _activeCall.value = current.copy(isMuted = nextMuted)
    }

    fun toggleSpeaker() {
        val current = _activeCall.value ?: return
        val nextSpeaker = !current.isSpeakerOn
        engine?.setSpeakerphoneEnabled(nextSpeaker)
        _activeCall.value = current.copy(isSpeakerOn = nextSpeaker)
    }

    private fun observeCallAndCandidates(callId: String, currentUserId: String) {
        callSessionJob?.cancel()
        callSessionJob = scope.launch {
            callRepository.observeCall(callId)
                .catch { /* handled */ }
                .collect { updated ->
                    if (updated == null) {
                        teardownActiveCall()
                        return@collect
                    }
                    currentSession = updated
                    val status = updated.statusEnum()
                    when (status) {
                        CallStatus.CONNECTED -> {
                            ringTimeoutJob?.cancel()
                            stopRingtone()
                            if (!updated.answerSdp.isBlank() &&
                                updated.callerId == currentUserId &&
                                answerAppliedForCallId != callId
                            ) {
                                answerAppliedForCallId = callId
                                runCatching {
                                    engine?.applyRemoteDescription(
                                        SessionDescription.Type.ANSWER,
                                        updated.answerSdp
                                    )
                                }
                                startElapsedTimerIfNeeded()
                            }
                            _activeCall.value = _activeCall.value?.copy(
                                status = CallStatus.CONNECTED
                            )
                        }
                        CallStatus.DECLINED, CallStatus.ENDED, CallStatus.MISSED -> {
                            if (updated.endedBy != currentUserId) {
                                _callNotice.value = when (status) {
                                    CallStatus.DECLINED -> "${updated.peerName(currentUserId)} declined the call."
                                    CallStatus.MISSED -> "Missed call."
                                    else -> "Call ended."
                                }
                            }
                            teardownActiveCall()
                        }
                        CallStatus.RINGING -> Unit
                    }
                }
        }

        candidatesJob?.cancel()
        candidatesJob = scope.launch {
            callRepository.observeRemoteCandidates(callId, currentUserId)
                .catch { /* handled */ }
                .collect { remoteCandidates ->
                    remoteCandidates.forEach { cand ->
                        if (processedCandidateIds.add(cand.id)) {
                            engine?.addRemoteIceCandidate(
                                sdpMid = cand.sdpMid,
                                sdpMLineIndex = cand.sdpMLineIndex,
                                sdp = cand.sdp
                            )
                        }
                    }
                }
        }
    }

    private fun startUnansweredRingTimeout(callId: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(45_000L)
            val current = currentSession
            if (current != null && current.id == callId && current.statusEnum() == CallStatus.RINGING) {
                _callNotice.value = "No answer."
                hangUp()
            }
        }
    }

    private fun startElapsedTimerIfNeeded() {
        if (timerJob != null) return
        val startedAt = System.currentTimeMillis()
        timerJob = scope.launch {
            while (isActive) {
                val secs = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                _activeCall.value = _activeCall.value?.copy(
                    status = CallStatus.CONNECTED,
                    elapsedSeconds = secs,
                    statusSubtitle = TimeFormatters.formatCallDuration(secs)
                )
                delay(1000L)
            }
        }
    }

    private fun startRingtone() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(appContext, uri)?.apply {
                play()
            }
        }
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun teardownActiveCall() {
        stopRingtone()
        callSessionJob?.cancel()
        callSessionJob = null
        candidatesJob?.cancel()
        candidatesJob = null
        timerJob?.cancel()
        timerJob = null
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        processedCandidateIds.clear()
        answerAppliedForCallId = null
        currentSession = null
        pendingConversation = null
        pendingSender = null
        engine?.release()
        engine = null
        _activeCall.value = null
    }
}
