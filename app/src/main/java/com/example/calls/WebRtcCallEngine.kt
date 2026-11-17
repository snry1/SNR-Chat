package com.example.calls

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcCallEngine(
    private val context: Context,
    private val onLocalIceCandidate: (sdpMid: String, sdpMLineIndex: Int, sdp: String) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var remoteDescriptionApplied = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var savedAudioMode: Int = audioManager.mode
    private var savedSpeakerOn: Boolean = false

    fun initializeSession() {
        release()
        configureCallAudioMode(speakerOn = false)

        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }

        val pcFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = pcFactory

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = pcFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state != null) {
                        onConnectionStateChanged(state)
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onLocalIceCandidate(
                            candidate.sdpMid ?: "0",
                            candidate.sdpMLineIndex,
                            candidate.sdp
                        )
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                override fun onAddStream(stream: MediaStream?) {}

                override fun onRemoveStream(stream: MediaStream?) {}

                override fun onDataChannel(channel: DataChannel?) {}

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    receiver?.track()?.setEnabled(true)
                }
            }
        ) ?: error("Unable to create WebRTC PeerConnection.")

        peerConnection = pc

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = pcFactory.createAudioSource(audioConstraints)
        val track = pcFactory.createAudioTrack("DAYUNAN_AUDIO_TRACK", source).apply {
            setEnabled(true)
        }
        audioSource = source
        localAudioTrack = track
        pc.addTrack(track, listOf("DAYUNAN_STREAM"))
    }

    suspend fun createOfferSdp(): String {
        val pc = peerConnection ?: error("PeerConnection not initialized.")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createOffer(
                object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp != null) cont.resume(sdp)
                        else cont.resumeWithException(IllegalStateException("Empty offer SDP"))
                    }

                    override fun onCreateFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException(error ?: "Failed to create offer"))
                    }
                },
                constraints
            )
        }
        setLocalDescription(pc, offer)
        return offer.description
    }

    suspend fun createAnswerSdp(): String {
        val pc = peerConnection ?: error("PeerConnection not initialized.")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        val answer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createAnswer(
                object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp != null) cont.resume(sdp)
                        else cont.resumeWithException(IllegalStateException("Empty answer SDP"))
                    }

                    override fun onCreateFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException(error ?: "Failed to create answer"))
                    }
                },
                constraints
            )
        }
        setLocalDescription(pc, answer)
        return answer.description
    }

    suspend fun applyRemoteDescription(type: SessionDescription.Type, sdpText: String) {
        val pc = peerConnection ?: error("PeerConnection not initialized.")
        val desc = SessionDescription(type, sdpText)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        cont.resume(Unit)
                    }

                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException(error ?: "Failed to apply remote SDP"))
                    }
                },
                desc
            )
        }
        remoteDescriptionApplied = true
        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.forEach { cand ->
                runCatching { pc.addIceCandidate(cand) }
            }
            pendingRemoteCandidates.clear()
        }
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val cand = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        val pc = peerConnection ?: return
        if (!remoteDescriptionApplied) {
            synchronized(pendingRemoteCandidates) {
                pendingRemoteCandidates.add(cand)
            }
        } else {
            runCatching { pc.addIceCandidate(cand) }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setSpeakerphoneEnabled(enabled: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (enabled) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == targetType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                    return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun configureCallAudioMode(speakerOn: Boolean) {
        runCatching {
            savedAudioMode = audioManager.mode
            @Suppress("DEPRECATION")
            savedSpeakerOn = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphoneEnabled(speakerOn)
        }
    }

    fun release() {
        remoteDescriptionApplied = false
        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.clear()
        }
        runCatching {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
        }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { factory?.dispose() }
        factory = null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = savedSpeakerOn
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private suspend fun setLocalDescription(pc: PeerConnection, sdp: SessionDescription) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        cont.resume(Unit)
                    }

                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(IllegalStateException(error ?: "Failed to set local SDP"))
                    }
                },
                sdp
            )
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile
        private var factoryInitialized = false
    }
}
