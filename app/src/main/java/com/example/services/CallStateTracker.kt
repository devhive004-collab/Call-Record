package com.example.services

import kotlinx.coroutines.flow.MutableStateFlow

object CallStateTracker {
    val isRecording = MutableStateFlow(false)
    val callerName = MutableStateFlow("مكالمة جارية")
    val durationSec = MutableStateFlow(0)
    val amplitudeList = MutableStateFlow<List<Float>>(emptyList())
    val platform = MutableStateFlow("CELLULAR")
    val direction = MutableStateFlow("INBOUND") // INBOUND or OUTBOUND
    val activeFilePath = MutableStateFlow<String?>(null)

    var initialSpeakerState: Boolean = false

    /**
     * Previous STREAM_VOICE_CALL volume saved in startRecordingCall and
     * restored in stopRecordingCall. Misnamed historically as "audio mode"
     * (MODE_NORMAL=2); it is a volume index, NOT an AudioManager mode.
     * New code should use [initialVoiceCallVolume].
     */
    var initialAudioMode: Int = 0
    var initialVoiceCallVolume: Int
        get() = initialAudioMode
        set(value) { initialAudioMode = value }

    /**
     * Last telephony signal seen by [com.example.receivers.PhoneCallReceiver].
     * Same process as the accessibility service, so the a11y trigger can
     * start recording with the right number/direction when the receiver's
     * own startForegroundService is blocked (Android 12+ background-start
     * rules only throw for the FGS start — the broadcast is still received).
     */
    @Volatile var pendingNumber: String? = null
    @Volatile var pendingDirection: String = "INBOUND"
    @Volatile var pendingAtMillis: Long = 0L

    fun reset() {
        isRecording.value = false
        callerName.value = "مكالمة جارية"
        durationSec.value = 0
        activeFilePath.value = null
        amplitudeList.value = emptyList()
        platform.value = "CELLULAR"
        direction.value = "INBOUND"
    }
}
