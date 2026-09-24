package com.example.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderManager(context: Context) {
    private val TAG = "AudioRecorderManager"
    private val appContext = context.applicationContext
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    @Volatile
    private var isRecording = false
    private var startTimeMillis: Long = 0L

    /**
     * NOTE: No-op by design. With MediaRecorder + VOICE_COMMUNICATION the
     * platform already applies AGC/NS. Manually creating AutomaticGainControl /
     * NoiseSuppressor here leaked native objects (never released) and required
     * a valid audioSessionId which MediaRecorder does not expose pre-start.
     * Kept for API compat; prefer VOICE_COMMUNICATION source instead.
     */
    fun applyAudioEffectsAndGain(audioSessionId: Int) {
        Log.w(TAG, "applyAudioEffectsAndGain is a no-op: system handles AGC/NS for VOICE_COMMUNICATION")
    }

    /**
     * Visible recordings folder.
     *
     * API 26–35, no runtime storage permission needed:
     * - Primary: app-specific external Music/Sajil
     *   (<storage>/Android/data/<pkg>/files/Music/Sajil) — visible over
     *   USB/file-manager, writable without permissions on all API levels.
     * - Fallback: internal filesDir/Sajil (always writable, e.g. no SD/shared
     *   storage mounted).
     *
     * Single-file model: MediaRecorder writes directly here, so DB filePath
     * always points at the playable file (no internal→public copy to orphan).
     */
    fun getRecordingsDir(): File {
        val ext = try {
            appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        } catch (_: Exception) { null }
        val base = if (ext != null) File(ext, "Sajil") else File(appContext.filesDir, "Sajil")
        try {
            if (!base.exists()) base.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create recordings dir, using fallback", e)
            return appContext.filesDir
        }
        return if (base.canWrite()) base else appContext.filesDir
    }

    /** Human-readable location for Settings UI / debugging. */
    fun getRecordingsDirDescription(): String = try {
        getRecordingsDir().absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "getRecordingsDir failed", e)
        appContext.filesDir.absolutePath
    }

    fun startRecording(fileNamePrefix: String, isCallRecording: Boolean = false): File? {
        if (isRecording) return currentFile

        try {
            val safePrefix = fileNamePrefix.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val audioFile = File(getRecordingsDir(), "${safePrefix}_${System.currentTimeMillis()}.m4a")
            currentFile = audioFile

            // VOICE_CALL requires CAPTURE_AUDIO_OUTPUT (system app only) and
            // always throws SecurityException for Play-store apps — keep it
            // last as a best-effort. VOICE_COMMUNICATION is the reliable
            // choice on Android 10+ (mic + system AGC/NS).
            val sourcesToTry = if (isCallRecording) {
                listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.VOICE_CALL
                )
            } else {
                listOf(MediaRecorder.AudioSource.MIC)
            }

            var activeRecorder: MediaRecorder? = null
            var lastException: Exception? = null

            for (source in sourcesToTry) {
                // Delete any partial header left by a previous failed attempt.
                try {
                    if (audioFile.exists()) audioFile.delete()
                } catch (_: Exception) { }
                @Suppress("DEPRECATION")
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(appContext)
                } else {
                    MediaRecorder()
                }

                try {
                    recorder.apply {
                        setAudioSource(source)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        // Best-effort quality boost: not all devices support
                        // 48kHz/192kbps with VOICE_* sources. If it throws,
                        // retry the same source without explicit rates.
                        try {
                            setAudioSamplingRate(44100)
                            setAudioEncodingBitRate(128000)
                        } catch (e: Exception) {
                            Log.w(TAG, "Sampling rate/bitrate not supported, using defaults", e)
                        }
                        setOutputFile(audioFile.absolutePath)
                        prepare()
                        start()
                    }
                    activeRecorder = recorder
                    Log.d(TAG, "Successfully initialized and started recorder with source: $source")
                    break
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to initialize/start recorder with source $source, trying next...", ex)
                    lastException = ex
                    try {
                        recorder.reset()
                    } catch (_: Exception) { }
                    try {
                        recorder.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing failed recorder", e)
                    }
                }
            }

            if (activeRecorder == null) {
                throw lastException ?: Exception("All audio sources failed to initialize")
            }

            mediaRecorder = activeRecorder
            isRecording = true
            startTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "Recording started successfully: ${audioFile.absolutePath}")
            return audioFile
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission or VOICE_CALL restricted", e)
            currentFile = null
            isRecording = false
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder — returning null (no fake file)", e)
            currentFile = null
            isRecording = false
            return null
        }
    }

    fun stopRecording(): RecordingResult {
        if (!isRecording) return RecordingResult(null, 0)

        val durationMs = System.currentTimeMillis() - startTimeMillis
        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

        var stopFailed = false
        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (e: RuntimeException) {
                // Thrown when recording is too short (<~1s) or already stopped.
                // File is corrupt — must be deleted, not returned.
                Log.e(TAG, "MediaRecorder.stop() failed, discarding corrupt file", e)
                stopFailed = true
            } catch (e: IllegalStateException) {
                Log.e(TAG, "MediaRecorder.stop() illegal state", e)
                stopFailed = true
            } finally {
                try {
                    recorder.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder", e)
                }
            }
        }
        mediaRecorder = null
        isRecording = false

        val resultFile = currentFile
        currentFile = null
        if (stopFailed || resultFile == null || !resultFile.exists() || resultFile.length() == 0L) {
            try {
                resultFile?.delete()
            } catch (_: Exception) { }
            return RecordingResult(null, 0)
        }
        return RecordingResult(resultFile, durationSec)
    }

    fun getAmplitude(): Int {
        if (!isRecording) return 0
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun checkIsRecording(): Boolean = isRecording

    data class RecordingResult(
        val file: File?,
        val durationSec: Int
    )
}
