package com.example.utils

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AudioPlayerManager(context: Context) {
    private val TAG = "AudioPlayerManager"
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var desiredSpeed: Float = 1.0f
    private val handler = Handler(Looper.getMainLooper())

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val updateProgressAction = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    _playbackState.value = PlaybackState.Playing(currentFilePath ?: "", currentPos, duration)
                    handler.postDelayed(this, 250) // Update every 250ms
                }
            }
        }
    }

    fun playAudio(filePath: String, playbackSpeed: Float = 1.0f) {
        desiredSpeed = playbackSpeed.coerceIn(0.5f, 3.0f)
        // If playing the same file and paused, resume
        if (currentFilePath == filePath && _playbackState.value is PlaybackState.Paused) {
            mediaPlayer?.let { player ->
                try {
                    applySpeedLocked(player, desiredSpeed)
                    player.start()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Resume failed, restarting playback", e)
                    startNewPlayback(filePath)
                    return
                }
                _playbackState.value = PlaybackState.Playing(filePath, player.currentPosition, safeDuration(player))
                handler.post(updateProgressAction)
                return
            }
        }

        startNewPlayback(filePath)
    }

    private fun startNewPlayback(filePath: String) {
        // Otherwise stop any existing playback
        stopAudio()

        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "File missing or empty: $filePath")
            _playbackState.value = PlaybackState.Error("ملف الصوت غير موجود أو تالف")
            return
        }

        var player: MediaPlayer? = null
        try {
            player = MediaPlayer().apply {
                // Set listeners BEFORE prepare/start to avoid missing
                // completion on very short files.
                setOnCompletionListener {
                    _playbackState.value = PlaybackState.Completed
                    handler.removeCallbacks(updateProgressAction)
                    // Release codec/FD promptly; keep currentFilePath cleared.
                    try { release() } catch (_: Exception) { }
                    if (mediaPlayer === this) {
                        mediaPlayer = null
                    }
                    currentFilePath = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra path=$filePath")
                    _playbackState.value = PlaybackState.Error("فشل في تشغيل ملف الصوت")
                    handler.removeCallbacks(updateProgressAction)
                    try { release() } catch (_: Exception) { }
                    if (mediaPlayer === this) {
                        mediaPlayer = null
                    }
                    currentFilePath = null
                    true
                }
                setDataSource(filePath)
                // NOTE: blocking prepare() must not run on the main thread for
                // large files (ANR). Callers invoke playAudio from UI; use
                // prepareAsync and start in onPrepared.
                setOnPreparedListener { prepared ->
                    try {
                        applySpeedLocked(prepared, desiredSpeed)
                        prepared.start()
                        currentFilePath = filePath
                        _playbackState.value = PlaybackState.Playing(filePath, 0, safeDuration(prepared))
                        handler.post(updateProgressAction)
                        Log.d(TAG, "Playback started for: $filePath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start after prepare", e)
                        _playbackState.value = PlaybackState.Error("فشل في تشغيل ملف الصوت")
                        try { release() } catch (_: Exception) { }
                        if (mediaPlayer === this) {
                            mediaPlayer = null
                        }
                    }
                }
                prepareAsync()
            }
            // Publish only after prepareAsync succeeds; on failure clean up.
            mediaPlayer = player
            currentFilePath = filePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            try { player?.release() } catch (_: Exception) { }
            if (mediaPlayer === player) {
                mediaPlayer = null
            }
            currentFilePath = null
            _playbackState.value = PlaybackState.Error("فشل في تشغيل ملف الصوت")
        }
    }

    private fun safeDuration(player: MediaPlayer): Int = try {
        player.duration.coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    private fun applySpeedLocked(player: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                player.playbackParams = player.playbackParams.setSpeed(speed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set playback speed", e)
            }
        }
    }

    fun pauseAudio() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _playbackState.value = PlaybackState.Paused(currentFilePath ?: "", player.currentPosition, player.duration)
                handler.removeCallbacks(updateProgressAction)
                Log.d(TAG, "Playback paused")
            }
        }
    }

    fun resumeAudio() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _playbackState.value = PlaybackState.Playing(currentFilePath ?: "", player.currentPosition, player.duration)
                handler.post(updateProgressAction)
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    fun stopAudio() {
        handler.removeCallbacks(updateProgressAction)
        val player = mediaPlayer
        mediaPlayer = null
        currentFilePath = null
        if (player != null) {
            try {
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "stop() in illegal state, releasing anyway", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping MediaPlayer", e)
            } finally {
                try {
                    player.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception releasing MediaPlayer", e)
                }
            }
        }
        _playbackState.value = PlaybackState.Idle
    }

    /**
     * Must be called from ViewModel.onCleared / Activity.onDestroy to avoid
     * leaking the Handler loop + native player.
     */
    fun release() {
        stopAudio()
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        try {
            val duration = safeDuration(player)
            val clamped = positionMs.coerceIn(0, duration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(clamped.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                @Suppress("DEPRECATION")
                player.seekTo(clamped)
            }
            val currentState = _playbackState.value
            if (currentState is PlaybackState.Playing) {
                _playbackState.value = PlaybackState.Playing(currentState.path, clamped, duration)
            } else if (currentState is PlaybackState.Paused) {
                _playbackState.value = PlaybackState.Paused(currentState.path, clamped, duration)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "seekTo in illegal state", e)
        } catch (e: Exception) {
            Log.e(TAG, "seekTo failed", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        desiredSpeed = speed.coerceIn(0.5f, 3.0f)
        val player = mediaPlayer ?: return
        try {
            // Apply immediately if possible; otherwise it is applied on
            // next start/resume via desiredSpeed.
            applySpeedLocked(player, desiredSpeed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set playback speed", e)
        }
    }

    fun getCurrentPlayingPath(): String? = currentFilePath

    sealed interface PlaybackState {
        val filePath: String? get() = null
        object Idle : PlaybackState
        data class Playing(val path: String, val currentPositionMs: Int, val durationMs: Int) : PlaybackState {
            override val filePath: String get() = path
        }
        data class Paused(val path: String, val currentPositionMs: Int, val durationMs: Int) : PlaybackState {
            override val filePath: String get() = path
        }
        object Completed : PlaybackState
        data class Error(val message: String) : PlaybackState
    }
}
