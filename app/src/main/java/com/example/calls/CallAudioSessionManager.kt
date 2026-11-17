package com.example.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class CallAudioSessionManager(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var active = false
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerOn: Boolean = false
    private var savedMicMuted: Boolean = false

    fun recommendedDefaultSpeaker(): Boolean {
        if (isEmulator()) return true
        return !hasEarpiece()
    }

    fun start(speakerOn: Boolean) {
        if (!active) {
            savedMode = audioManager.mode
            @Suppress("DEPRECATION")
            savedSpeakerOn = audioManager.isSpeakerphoneOn
            savedMicMuted = audioManager.isMicrophoneMute
            active = true
        }

        requestCallAudioFocus()

        runCatching {
            audioManager.isMicrophoneMute = false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        setSpeakerphoneEnabled(speakerOn)
    }

    fun setSpeakerphoneEnabled(enabled: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                if (enabled) {
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                        return
                    }
                } else {
                    val preferredHeadset = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    val earpiece = if (isEmulator()) null else {
                        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    }
                    val fallbackSpeaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    val target = preferredHeadset ?: earpiece ?: fallbackSpeaker
                    if (target != null) {
                        audioManager.setCommunicationDevice(target)
                        return
                    }
                }
            }

            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = if (!enabled && !hasEarpiece()) true else enabled
        }
    }

    fun release() {
        if (!active) return
        active = false
        abandonCallAudioFocus()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = savedSpeakerOn
            }
            audioManager.isMicrophoneMute = savedMicMuted
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun requestCallAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener { }
                        .build()
                }
                focusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonCallAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    private fun hasEarpiece(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("unknown", ignoreCase = true) ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.PRODUCT.contains("emulator", ignoreCase = true)
    }
}
