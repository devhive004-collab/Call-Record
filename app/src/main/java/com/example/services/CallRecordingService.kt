package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.database.Recording
import com.example.data.database.RecordingDatabase
import com.example.utils.AudioRecorderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class CallRecordingService : Service() {
    private val TAG = "CallRecordingService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "call_recording_channel"

    private lateinit var recorderManager: AudioRecorderManager
    private lateinit var overlayManager: RecordingOverlayManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null
    private var amplitudeJob: Job? = null

    companion object {
        const val ACTION_START_RECORDING = "com.example.services.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.services.ACTION_STOP_RECORDING"
        const val EXTRA_PHONE_NUMBER = "com.example.services.EXTRA_PHONE_NUMBER"
        const val EXTRA_CALL_DIRECTION = "com.example.services.EXTRA_CALL_DIRECTION" // INBOUND or OUTBOUND
    }

    override fun onCreate() {
        super.onCreate()
        recorderManager = AudioRecorderManager(this)
        overlayManager = RecordingOverlayManager(this) {
            // Overlay Stop tap runs on the main thread in our own
            // foreground process — stopping directly is always allowed.
            stopRecordingCall()
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action = $action, startId = $startId")

        if (action == ACTION_START_RECORDING) {
            val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
            val direction = intent.getStringExtra(EXTRA_CALL_DIRECTION) ?: "INBOUND"
            startRecordingCall(phoneNumber, direction, startId)
        } else if (action == ACTION_STOP_RECORDING) {
            stopRecordingCall(stopStartId = startId)
        }

        return START_NOT_STICKY
    }

    private fun startRecordingCall(phoneNumber: String?, direction: String, startId: Int) {
        if (CallStateTracker.isRecording.value) {
            Log.d(TAG, "Recording is already active.")
            return
        }

        // Fail fast if mic permission was revoked — otherwise startForeground +
        // MediaRecorder throw SecurityException and crash the service.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted, aborting recording.")
            stopSelf(startId)
            return
        }

        // Get Contact Name or Fallback
        val resolvedName = if (!phoneNumber.isNullOrEmpty()) {
            getContactName(this, phoneNumber)
        } else {
            if (direction == "INBOUND") "مكالمة واردة" else "مكالمة صادرة"
        }

        // Setup notification
        val notification = buildNotification(resolvedName)

        // Start Foreground Service with type Microphone for Android 14 compatibility.
        // This can throw on Android 12+ when started from background without
        // an exemption, or when permissions are missing — must not crash.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (bg restriction or missing permission)", e)
            stopSelf(startId)
            return
        }

        val prefix = if (direction == "INBOUND") "call_in" else "call_out"

        // Floating in-call pill (timer/stop) over the dialer. No-op without
        // the overlay permission — recording continues notification-only.
        overlayManager.show()

        serviceScope.launch {
            // Delay to allow the dialer's audio routing to settle
            delay(1500)
            
            // Boost call volume to maximum to help the microphone catch the earpiece bleed
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                CallStateTracker.initialAudioMode = audioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
                
                // تفعيل وضع الاتصال عبر البلوتوث لالتقاط الصوت من السماعات (Headset) إن وجدت
                try {
                    val hasBtPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this@CallRecordingService, android.Manifest.permission.BLUETOOTH_CONNECT
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasBtPermission && audioManager.isBluetoothScoAvailableOffCall) {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No BLUETOOTH_CONNECT, skipping SCO setup", e)
                }
                Log.d(TAG, "Call volume boosted to max ($maxVolume) to improve recording.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to boost volume", e)
            }
            
            val file = recorderManager.startRecording(prefix, isCallRecording = true)

            if (file != null) {
                // Update Tracker
                CallStateTracker.isRecording.value = true
                CallStateTracker.callerName.value = resolvedName
                CallStateTracker.durationSec.value = 0
                CallStateTracker.platform.value = "CELLULAR"
                CallStateTracker.direction.value = direction
                CallStateTracker.activeFilePath.value = file.absolutePath
                CallStateTracker.amplitudeList.value = emptyList()

                // Start active monitoring timers
                startTimers()
                Log.d(TAG, "Call recording started for $resolvedName")
            } else {
                Log.e(TAG, "Failed to start call recording.")
                stopSelf(startId)
            }
        }
    }

    private fun stopRecordingCall(stopStartId: Int? = null) {
        fun stopSelfSafe() {
            if (stopStartId != null) stopSelf(stopStartId) else stopSelf()
        }
        if (!CallStateTracker.isRecording.value) {
            stopSelfSafe()
            return
        }

        stopTimers()
        val result = recorderManager.stopRecording()
        
        // Restore previous call volume. initialAudioMode actually stores the
        // previous STREAM_VOICE_CALL volume (see startRecordingCall). Guard
        // range and Bluetooth permission (API 31+ needs BLUETOOTH_CONNECT).
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val prevVolume = CallStateTracker.initialAudioMode
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
            if (prevVolume in 0..maxVolume) {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, prevVolume, 0)
            }
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No BLUETOOTH_CONNECT, skipping SCO teardown", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore volume", e)
        }
        
        val filePath = CallStateTracker.activeFilePath.value
        val callerName = CallStateTracker.callerName.value
        val direction = CallStateTracker.direction.value
        val duration = result.durationSec

        // Reset Tracker
        CallStateTracker.isRecording.value = false
        CallStateTracker.callerName.value = "مكالمة جارية"
        CallStateTracker.durationSec.value = 0
        CallStateTracker.activeFilePath.value = null
        CallStateTracker.amplitudeList.value = emptyList()
        CallStateTracker.direction.value = direction

        // Recording ended — drop the floating pill immediately (the DB
        // insert below is async and must not keep UI on screen).
        overlayManager.hide()

        if (result.file != null && result.file.exists() && result.file.length() > 0L && duration > 0) {
            serviceScope.launch {
                try {
                    // Delay slightly to allow the OS to write the final call log entry
                    delay(1200)
                    
                    var finalName = callerName
                    try {
                        val callDetails = getLastCallDetails(applicationContext)
                        if (callDetails != null) {
                            finalName = if (!callDetails.name.isNullOrEmpty()) {
                                callDetails.name
                            } else if (!callDetails.number.isNullOrEmpty()) {
                                callDetails.number
                            } else {
                                finalName
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to fetch name from call log", ex)
                    }

                    val database = RecordingDatabase.getDatabase(applicationContext)
                    val newRecording = Recording(
                        title = finalName,
                        source = "CELLULAR",
                        direction = direction,
                        durationSec = duration,
                        filePath = result.file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        notes = "مكالمة مسجلة تلقائياً بفضل كاشف الإشارات والاتصال المباشر."
                    )
                    database.recordingDao().insertRecording(newRecording)
                    Log.d(TAG, "Successfully saved call recording to database as: $finalName")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving recording to Room", e)
                } finally {
                    stopSelfSafe()
                }
            }
        } else {
            Log.e(TAG, "No recording file found after stopping recording.")
            stopSelfSafe()
        }
    }

    private fun getLastCallDetails(context: Context): CallDetails? {
        // Guard: without READ_CALL_LOG this always throws SecurityException.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CALL_LOG
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE
                ),
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val number = if (numberIdx >= 0) it.getString(numberIdx) else null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                    return CallDetails(number, name)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG revoked, skipping call-log lookup", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call log details", e)
        }
        return null
    }

    private data class CallDetails(val number: String?, val name: String?)

    private fun startTimers() {
        timerJob?.cancel()
        timerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000)
                CallStateTracker.durationSec.value += 1
            }
        }

        amplitudeJob?.cancel()
        amplitudeJob = serviceScope.launch(Dispatchers.Main) {
            val window = ArrayDeque<Float>(51)
            while (isActive) {
                delay(200)
                val amp = recorderManager.getAmplitude()
                val normalized = (amp.toFloat() / 32767f).coerceIn(0f, 1f)
                if (window.size >= 50) window.removeFirst()
                window.addLast(normalized)
                CallStateTracker.amplitudeList.value = window.toList()
            }
        }
    }

    private fun stopTimers() {
        timerJob?.cancel()
        timerJob = null
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    private fun buildNotification(callerName: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action must open an Activity, not the service directly:
        // Android 12+ bans notification trampolines to services/receivers.
        // MainActivity consumes the action while foregrounded (always
        // allowed to stop the service from there).
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_STOP_SERVICE_RECORDING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stopPendingIntent = android.app.PendingIntent.getActivity(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مسجل المكالمات الذكي نشط")
            .setContentText("جاري تسجيل المكالمة مع: $callerName تلقائياً...")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إيقاف وحفظ",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies user of background call recording services."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getContactName(context: Context, phoneNumber: String?): String {
        if (phoneNumber.isNullOrEmpty()) return "مكالمة مجهولة"
        // Without READ_CONTACTS this always throws SecurityException.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return phoneNumber
        }
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val name = if (idx >= 0) cursor.getString(idx) else null
                    return name ?: phoneNumber
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS revoked, using raw number", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name", e)
        }
        return phoneNumber
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTimers()
        overlayManager.hide()
        try {
            // Best-effort: never leak a running MediaRecorder if the OS kills us.
            if (CallStateTracker.isRecording.value) {
                recorderManager.stopRecording()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder in onDestroy", e)
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
