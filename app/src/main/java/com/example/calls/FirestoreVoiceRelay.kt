package com.example.calls

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class FirestoreVoiceRelay(
    private val callRepository: CallRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recordJob: Job? = null
    private var receiveJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @Volatile
    private var running = false

    @Volatile
    private var muted = false

    private var lastPlayedSeq = -1L

    @SuppressLint("MissingPermission")
    fun start(callId: String, currentUserId: String, participantUids: List<String>, initiallyMuted: Boolean) {
        if (running) return
        running = true
        muted = initiallyMuted
        lastPlayedSeq = -1L

        startAudioTrack()
        startRecordingLoop(callId, participantUids)
        startReceiveLoop(callId, currentUserId)
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun stop() {
        running = false
        recordJob?.cancel()
        recordJob = null
        receiveJob?.cancel()
        receiveJob = null

        runCatching { aec?.release() }
        aec = null
        runCatching { ns?.release() }
        ns = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    private fun startAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_SAMPLES * 2)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        runCatching {
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
        }
        audioTrack = track
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingLoop(callId: String, participantUids: List<String>) {
        recordJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 2)

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                return@launch
            }

            audioRecord = recorder
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                recorder.startRecording()
            }

            val pcmBuffer = ShortArray(CHUNK_SAMPLES)
            var seq = 0L
            val adpcmState = ImaAdpcmState()

            while (isActive && running) {
                var readTotal = 0
                while (readTotal < CHUNK_SAMPLES && isActive && running) {
                    val n = recorder.read(pcmBuffer, readTotal, CHUNK_SAMPLES - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                if (readTotal <= 0) continue

                if (muted) {
                    pcmBuffer.fill(0)
                }

                // Skip dead-silence frames when no energy to keep Firestore lean,
                // except periodic keepalive/start frames
                val peak = pcmBuffer.maxOfOrNull { abs(it.toInt()) } ?: 0
                if (muted && seq > 0L && seq % 6L != 0L) {
                    seq++
                    continue
                }
                if (!muted && peak < 90 && seq > 2L && seq % 4L != 0L) {
                    seq++
                    continue
                }

                val startPred = adpcmState.predSample
                val startStep = adpcmState.stepIndex
                val encoded = ImaAdpcmCodec.encode(pcmBuffer, readTotal, adpcmState)
                val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                val currentSeq = seq++

                launch {
                    callRepository.sendAudioChunk(
                        callId = callId,
                        participantUids = participantUids,
                        seq = currentSeq,
                        sampleRate = SAMPLE_RATE,
                        predSample = startPred,
                        stepIndex = startStep,
                        payload = b64
                    )
                }
            }
        }
    }

    private fun startReceiveLoop(callId: String, currentUserId: String) {
        receiveJob = scope.launch {
            callRepository.observeRemoteAudioChunks(callId, currentUserId).collect { chunks ->
                if (!running) return@collect
                val track = audioTrack ?: return@collect
                chunks.forEach { chunk ->
                    if (chunk.seq > lastPlayedSeq) {
                        lastPlayedSeq = chunk.seq
                        val rawBytes = runCatching { Base64.decode(chunk.payload, Base64.DEFAULT) }.getOrNull()
                        if (rawBytes != null && rawBytes.isNotEmpty()) {
                            val pcm = ImaAdpcmCodec.decode(
                                bytes = rawBytes,
                                initialPred = chunk.predSample,
                                initialStep = chunk.stepIndex
                            )
                            runCatching {
                                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            }
                        }
                        launch {
                            callRepository.deleteAudioChunk(callId, chunk.id)
                        }
                    }
                }
            }
        }
    }

    private class ImaAdpcmState(
        var predSample: Int = 0,
        var stepIndex: Int = 0
    )

    private object ImaAdpcmCodec {
        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )
        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )

        fun encode(samples: ShortArray, count: Int, state: ImaAdpcmState): ByteArray {
            val out = ByteArray((count + 1) / 2)
            var pred = state.predSample
            var index = state.stepIndex.coerceIn(0, 88)
            var outIdx = 0
            var highNibble = false
            var packed = 0

            for (i in 0 until count) {
                val sample = samples[i].toInt()
                var diff = sample - pred
                val sign = if (diff < 0) {
                    diff = -diff
                    8
                } else 0

                val step = STEP_TABLE[index]
                var delta = 0
                var vpdiff = step shr 3

                if (diff >= step) {
                    delta = 4
                    diff -= step
                    vpdiff += step
                }
                if (diff >= (step shr 1)) {
                    delta = delta or 2
                    diff -= (step shr 1)
                    vpdiff += (step shr 1)
                }
                if (diff >= (step shr 2)) {
                    delta = delta or 1
                    vpdiff += (step shr 2)
                }

                pred = if (sign != 0) pred - vpdiff else pred + vpdiff
                pred = pred.coerceIn(-32768, 32767)
                delta = delta or sign

                index = (index + INDEX_TABLE[delta]).coerceIn(0, 88)

                if (!highNibble) {
                    packed = delta and 0x0F
                    highNibble = true
                } else {
                    out[outIdx++] = (packed or ((delta and 0x0F) shl 4)).toByte()
                    highNibble = false
                }
            }
            if (highNibble && outIdx < out.size) {
                out[outIdx] = packed.toByte()
            }

            state.predSample = pred
            state.stepIndex = index
            return out
        }

        fun decode(bytes: ByteArray, initialPred: Int, initialStep: Int): ShortArray {
            val out = ShortArray(bytes.size * 2)
            var pred = initialPred.coerceIn(-32768, 32767)
            var index = initialStep.coerceIn(0, 88)
            var outIdx = 0

            for (b in bytes) {
                val raw = b.toInt() and 0xFF
                for (shift in intArrayOf(0, 4)) {
                    val delta = (raw ushr shift) and 0x0F
                    val step = STEP_TABLE[index]
                    var vpdiff = step shr 3
                    if ((delta and 4) != 0) vpdiff += step
                    if ((delta and 2) != 0) vpdiff += (step shr 1)
                    if ((delta and 1) != 0) vpdiff += (step shr 2)
                    pred = if ((delta and 8) != 0) pred - vpdiff else pred + vpdiff
                    pred = pred.coerceIn(-32768, 32767)
                    index = (index + INDEX_TABLE[delta]).coerceIn(0, 88)
                    out[outIdx++] = pred.toShort()
                }
            }
            return out
        }
    }

    companion object {
        private const val SAMPLE_RATE = 8000
        private const val CHUNK_SAMPLES = 2000 // 250ms per chunk
    }
}
