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
import com.example.domain.model.CallIceCandidate
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
    private val audioSessionManager = CallAudioSessionManager(appContext)
    private val voiceRelay = FirestoreVoiceRelay(callRepository)

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
    private var iceFallbackWatchdogJob: Job? = null

    private var currentSession: CallSession? = null
    private var engine: WebRtcCallEngine? = null
    private var ringtone: Ringtone? = null
    private var answerAppliedForCallId: String? = null
    private val processedCandidateIds = mutableSetOf<String>()
    private val queuedRemoteCandidatesBeforeEngine = mutableListOf<CallIceCandidate>()
    private var pendingConversation: Conversation? = null
    private var pendingSender: UserProfile? = null
    private var webrtcAudioConnected = false
    private var usingRelayFallback = false

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
        webrtcAudioConnected = false
        usingRelayFallback = false

        val defaultSpeaker = audioSessionManager.recommendedDefaultSpeaker()
        val tempUi = ActiveCallUiState(
            callId = "pending",
            conversationId = conversation.id,
            peerId = callee.userId,
            peerName = callee.displayName.ifBlank { "Dayunan User" },
            peerAvatarData = callee.avatarData,
            isIncoming = false,
            status = CallStatus.RINGING,
            statusSubtitle = "Connecting...",
            isSpeakerOn = defaultSpeaker
        )
        _activeCall.value = tempUi
        audioSessionManager.start(speakerOn = defaultSpeaker)

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
                        handleIceStateChange(iceState)
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
                    _activeCall.value = _activeCall.value?.copy(
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
        webrtcAudioConnected = false
        usingRelayFallback = false
        queuedRemoteCandidatesBeforeEngine.clear()
        processedCandidateIds.clear()

        val defaultSpeaker = audioSessionManager.recommendedDefaultSpeaker()
        _activeCall.value = ActiveCallUiState(
            callId = session.id,
            conversationId = session.conversationId,
            peerId = session.callerId,
            peerName = session.callerName.ifBlank { "Dayunan User" },
            peerAvatarData = session.callerAvatarData,
            isIncoming = true,
            status = CallStatus.RINGING,
            statusSubtitle = "Incoming voice call",
            isSpeakerOn = defaultSpeaker
        )
        startRingtone()
        observeCallAndCandidates(session.id, session.calleeId)
    }

    fun acceptIncomingCall() {
        val session = currentSession ?: return
        val uid = observedUserId ?: return
        if (session.statusEnum() != CallStatus.RINGING) return

        stopRingtone()
        val speakerOn = _activeCall.value?.isSpeakerOn ?: audioSessionManager.recommendedDefaultSpeaker()
        audioSessionManager.start(speakerOn = speakerOn)

        _activeCall.value = _activeCall.value?.copy(
            status = CallStatus.CONNECTED,
            statusSubtitle = "Connecting audio..."
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
                        handleIceStateChange(iceState)
                    }
                )
                engine = rtcEngine
                val answerSdp = withContext(Dispatchers.Default) {
                    rtcEngine.initializeSession()
                    rtcEngine.applyRemoteDescription(SessionDescription.Type.OFFER, session.offerSdp)
                    flushQueuedCandidates(rtcEngine)
                    rtcEngine.createAnswerSdp()
                }

                callRepository.answerCall(session.id, answerSdp)
                    .onSuccess {
                        scheduleIceFallbackWatchdog(session, uid)
                    }
                    .onFailure { err ->
                        _callNotice.value = err.toFriendlyMessage("Failed to answer call.")
                        hangUp()
                    }
            } catch (e: Exception) {
                // If WebRTC SDP/engine initialization fails, still answer & use Firestore voice relay
                if (currentSession?.id == session.id) {
                    activateRelayFallback(session, uid)
                } else {
                    _callNotice.value = e.toFriendlyMessage("Unable to connect voice call.")
                    hangUp()
                }
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
        voiceRelay.setMuted(nextMuted)
        _activeCall.value = current.copy(isMuted = nextMuted)
    }

    fun toggleSpeaker() {
        val current = _activeCall.value ?: return
        val nextSpeaker = !current.isSpeakerOn
        audioSessionManager.setSpeakerphoneEnabled(nextSpeaker)
        _activeCall.value = current.copy(isSpeakerOn = nextSpeaker)
    }

    private fun handleIceStateChange(iceState: PeerConnection.IceConnectionState) {
        scope.launch {
            when (iceState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (!usingRelayFallback) {
                        webrtcAudioConnected = true
                        iceFallbackWatchdogJob?.cancel()
                        iceFallbackWatchdogJob = null
                        val speakerOn = _activeCall.value?.isSpeakerOn
                            ?: audioSessionManager.recommendedDefaultSpeaker()
                        audioSessionManager.setSpeakerphoneEnabled(speakerOn)
                        startElapsedTimerIfNeeded()
                    }
                }
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    val session = currentSession
                    val uid = observedUserId
                    if (!usingRelayFallback && session != null && uid != null &&
                        session.statusEnum() == CallStatus.CONNECTED
                    ) {
                        activateRelayFallback(session, uid)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun scheduleIceFallbackWatchdog(session: CallSession, currentUserId: String) {
        if (iceFallbackWatchdogJob != null || webrtcAudioConnected || usingRelayFallback) return
        iceFallbackWatchdogJob = scope.launch {
            delay(3500L)
            val latestSession = currentSession
            if (!webrtcAudioConnected && !usingRelayFallback &&
                latestSession != null && latestSession.id == session.id &&
                latestSession.statusEnum() == CallStatus.CONNECTED
            ) {
                activateRelayFallback(latestSession, currentUserId)
            }
        }
    }

    private fun activateRelayFallback(session: CallSession, currentUserId: String) {
        if (usingRelayFallback) return
        usingRelayFallback = true
        iceFallbackWatchdogJob?.cancel()
        iceFallbackWatchdogJob = null

        // Release WebRTC so AudioRecord isn't contended by two engines
        runCatching { engine?.release() }
        engine = null

        val ui = _activeCall.value
        val speakerOn = ui?.isSpeakerOn ?: audioSessionManager.recommendedDefaultSpeaker()
        val muted = ui?.isMuted ?: false
        audioSessionManager.start(speakerOn = speakerOn)
        voiceRelay.start(
            callId = session.id,
            currentUserId = currentUserId,
            participantUids = session.participantUids,
            initiallyMuted = muted
        )
        startElapsedTimerIfNeeded()
    }

    private fun flushQueuedCandidates(rtcEngine: WebRtcCallEngine) {
        synchronized(queuedRemoteCandidatesBeforeEngine) {
            queuedRemoteCandidatesBeforeEngine.forEach { cand ->
                if (processedCandidateIds.add(cand.id)) {
                    rtcEngine.addRemoteIceCandidate(
                        sdpMid = cand.sdpMid,
                        sdpMLineIndex = cand.sdpMLineIndex,
                        sdp = cand.sdp
                    )
                }
            }
            queuedRemoteCandidatesBeforeEngine.clear()
        }
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
                                val speakerOn = _activeCall.value?.isSpeakerOn
                                    ?: audioSessionManager.recommendedDefaultSpeaker()
                                audioSessionManager.setSpeakerphoneEnabled(speakerOn)
                                _activeCall.value = _activeCall.value?.copy(
                                    status = CallStatus.CONNECTED,
                                    statusSubtitle = if (webrtcAudioConnected || usingRelayFallback) {
                                        TimeFormatters.formatCallDuration(_activeCall.value?.elapsedSeconds ?: 0L)
                                    } else {
                                        "Connecting audio..."
                                    }
                                )
                                val rtcEngine = engine
                                if (rtcEngine != null) {
                                    val sdpResult = runCatching {
                                        withContext(Dispatchers.Default) {
                                            rtcEngine.applyRemoteDescription(
                                                SessionDescription.Type.ANSWER,
                                                updated.answerSdp
                                            )
                                            flushQueuedCandidates(rtcEngine)
                                        }
                                    }
                                    if (sdpResult.isFailure) {
                                        activateRelayFallback(updated, currentUserId)
                                    } else {
                                        scheduleIceFallbackWatchdog(updated, currentUserId)
                                    }
                                } else {
                                    activateRelayFallback(updated, currentUserId)
                                }
                            }
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
                    val currentEngine = engine
                    if (currentEngine == null) {
                        synchronized(queuedRemoteCandidatesBeforeEngine) {
                            remoteCandidates.forEach { cand ->
                                if (cand.id !in processedCandidateIds &&
                                    queuedRemoteCandidatesBeforeEngine.none { it.id == cand.id }
                                ) {
                                    queuedRemoteCandidatesBeforeEngine.add(cand)
                                }
                            }
                        }
                    } else {
                        remoteCandidates.forEach { cand ->
                            if (processedCandidateIds.add(cand.id)) {
                                currentEngine.addRemoteIceCandidate(
                                    sdpMid = cand.sdpMid,
                                    sdpMLineIndex = cand.sdpMLineIndex,
                                    sdp = cand.sdp
                                )
                            }
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
        iceFallbackWatchdogJob?.cancel()
        iceFallbackWatchdogJob = null
        synchronized(queuedRemoteCandidatesBeforeEngine) {
            queuedRemoteCandidatesBeforeEngine.clear()
        }
        processedCandidateIds.clear()
        answerAppliedForCallId = null
        webrtcAudioConnected = false
        usingRelayFallback = false
        currentSession = null
        pendingConversation = null
        pendingSender = null
        voiceRelay.stop()
        engine?.release()
        engine = null
        audioSessionManager.release()
        _activeCall.value = null
    }
}
