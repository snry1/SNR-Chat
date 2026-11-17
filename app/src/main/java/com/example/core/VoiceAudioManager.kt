package com.example.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class RecordedVoiceNote(
    val dataUri: String,
    val durationMs: Long,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class VoicePlaybackState(
    val playingMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(): Result<Unit> = runCatching {
        cancelRecording()
        val dir = File(context.cacheDir, "voice_recordings").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(24_000)
            setAudioChannels(1)
            setMaxDuration(120_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = mr
        outputFile = file
        startTimeMs = System.currentTimeMillis()
    }.onFailure {
        cancelRecording()
    }

    fun currentAmplitude(): Int {
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    suspend fun stopAndEncode(): Result<RecordedVoiceNote> = withContext(Dispatchers.IO) {
        val mr = recorder
        val file = outputFile
        val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        recorder = null
        outputFile = null

        if (mr == null || file == null) {
            return@withContext Result.failure(IllegalStateException("No active voice recording."))
        }

        val stoppedOk = try {
            mr.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }

        if (!stoppedOk || elapsedMs < 400L || !file.exists() || file.length() < 200L) {
            runCatching { file.delete() }
            return@withContext Result.failure(
                IllegalArgumentException("Recording too short. Hold or record a bit longer.")
            )
        }

        return@withContext runCatching {
            val bytes = file.readBytes()
            runCatching { file.delete() }
            if (bytes.size > SnrConstants.MAX_RAW_FILE_BYTES) {
                error("Voice note exceeds size limit. Please keep messages under 2 minutes.")
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:audio/mp4;base64,$b64"
            if (dataUri.length > SnrConstants.MAX_MEDIA_DATA_LEN) {
                error("Voice note is too large to send.")
            }
            RecordedVoiceNote(
                dataUri = dataUri,
                durationMs = elapsedMs,
                fileName = "voice_${System.currentTimeMillis()}.m4a",
                mimeType = "audio/mp4",
                sizeBytes = bytes.size.toLong()
            )
        }
    }

    fun cancelRecording() {
        val mr = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }
        if (file != null) {
            runCatching { file.delete() }
        }
    }
}

class VoicePlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var loadJob: Job? = null
    private var playSessionToken: Long = 0L

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    fun togglePlayPause(messageId: String, mediaData: String, fallbackDurationMs: Long) {
        val current = _playbackState.value
        val activeMp = mediaPlayer
        if (current.playingMessageId == messageId && activeMp != null) {
            val isCurrentlyPlaying = runCatching { activeMp.isPlaying }.getOrDefault(false)
            if (isCurrentlyPlaying) {
                runCatching { activeMp.pause() }
                progressJob?.cancel()
                progressJob = null
                val pos = runCatching { activeMp.currentPosition.toLong() }.getOrDefault(current.positionMs)
                _playbackState.value = current.copy(
                    isPlaying = false,
                    positionMs = pos
                )
            } else {
                val resumed = runCatching {
                    val dur = activeMp.duration
                    val pos = activeMp.currentPosition
                    if (dur > 0 && pos >= dur - 120) {
                        activeMp.seekTo(0)
                    }
                    activeMp.start()
                    true
                }.getOrDefault(false)

                if (resumed) {
                    val pos = runCatching { activeMp.currentPosition.toLong() }.getOrDefault(0L)
                    val dur = runCatching { activeMp.duration.takeIf { it > 0 }?.toLong() }
                        .getOrNull() ?: current.durationMs
                    _playbackState.value = current.copy(
                        isPlaying = true,
                        positionMs = pos,
                        durationMs = dur
                    )
                    startProgressTicker(activeMp, messageId)
                } else {
                    playNew(messageId, mediaData, fallbackDurationMs)
                }
            }
            return
        }

        playNew(messageId, mediaData, fallbackDurationMs)
    }

    private fun playNew(messageId: String, mediaData: String, fallbackDurationMs: Long) {
        stop()
        val token = ++playSessionToken
        loadJob = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val audioFile = decodeToCacheFile(messageId, mediaData) ?: return@withContext null
                runCatching {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    java.io.FileInputStream(audioFile).use { fis ->
                        mp.setDataSource(fis.fd)
                    }
                    mp.prepare()
                    mp
                }.getOrNull()
            }

            if (prepared == null) {
                if (token == playSessionToken) {
                    _playbackState.value = VoicePlaybackState()
                }
                return@launch
            }

            if (token != playSessionToken) {
                releaseSafely(prepared)
                return@launch
            }

            prepared.setOnCompletionListener { completedMp ->
                scope.launch {
                    if (mediaPlayer === completedMp) {
                        finishPlayback(completedMp)
                    } else {
                        releaseSafely(completedMp)
                    }
                }
            }
            prepared.setOnErrorListener { errorMp, _, _ ->
                scope.launch {
                    if (mediaPlayer === errorMp) {
                        finishPlayback(errorMp)
                    } else {
                        releaseSafely(errorMp)
                    }
                }
                true
            }

            val started = runCatching {
                prepared.start()
                true
            }.getOrDefault(false)

            if (!started) {
                releaseSafely(prepared)
                _playbackState.value = VoicePlaybackState()
                return@launch
            }

            mediaPlayer = prepared
            val totalMs = runCatching { prepared.duration.takeIf { it > 0 }?.toLong() }
                .getOrNull() ?: fallbackDurationMs
            _playbackState.value = VoicePlaybackState(
                playingMessageId = messageId,
                isPlaying = true,
                positionMs = 0L,
                durationMs = totalMs
            )
            startProgressTicker(prepared, messageId)
        }
    }

    private fun startProgressTicker(targetMp: MediaPlayer, messageId: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (mediaPlayer !== targetMp) break
                val isPlaying = runCatching { targetMp.isPlaying }.getOrDefault(false)
                val pos = runCatching { targetMp.currentPosition.toLong() }.getOrDefault(0L)
                val dur = runCatching { targetMp.duration.takeIf { it > 0 }?.toLong() }.getOrNull()
                    ?: _playbackState.value.durationMs
                if (isPlaying) {
                    _playbackState.value = VoicePlaybackState(
                        playingMessageId = messageId,
                        isPlaying = true,
                        positionMs = pos,
                        durationMs = dur
                    )
                } else if (dur > 0L && pos >= dur - 80L) {
                    finishPlayback(targetMp)
                    break
                }
                delay(80L)
            }
        }
    }

    private fun finishPlayback(targetMp: MediaPlayer) {
        if (mediaPlayer === targetMp) {
            mediaPlayer = null
        }
        progressJob?.cancel()
        progressJob = null
        releaseSafely(targetMp)
        _playbackState.value = VoicePlaybackState()
    }

    fun stop() {
        playSessionToken++
        loadJob?.cancel()
        loadJob = null
        progressJob?.cancel()
        progressJob = null
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            releaseSafely(mp)
        }
        _playbackState.value = VoicePlaybackState()
    }

    private fun releaseSafely(mp: MediaPlayer) {
        runCatching { mp.setOnCompletionListener(null) }
        runCatching { mp.setOnErrorListener(null) }
        runCatching {
            if (mp.isPlaying) mp.stop()
        }
        runCatching { mp.reset() }
        runCatching { mp.release() }
    }

    private fun decodeToCacheFile(messageId: String, mediaData: String): File? {
        val commaIdx = mediaData.indexOf(',')
        if (commaIdx < 0) return null
        return try {
            val dir = File(context.cacheDir, "voice_cache").apply { mkdirs() }
            val safeId = messageId.ifBlank { mediaData.hashCode().toString() }
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(dir, "$safeId.m4a")
            if (!file.exists() || file.length() < 32L) {
                val bytes = Base64.decode(mediaData.substring(commaIdx + 1), Base64.DEFAULT)
                if (bytes.isEmpty()) return null
                val temp = File(dir, "$safeId.tmp")
                FileOutputStream(temp).use {
                    it.write(bytes)
                    it.flush()
                }
                if (file.exists()) file.delete()
                if (!temp.renameTo(file)) {
                    FileOutputStream(file).use { it.write(bytes) }
                    temp.delete()
                }
            }
            file
        } catch (_: Exception) {
            null
        }
    }
}

