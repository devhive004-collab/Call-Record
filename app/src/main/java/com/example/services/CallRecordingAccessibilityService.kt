package com.example.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Auto-record trigger based on in-call UI detection.
 *
 * Why this exists alongside [com.example.receivers.PhoneCallReceiver]:
 * on Android 12+ the system blocks `startForegroundService()` from the
 * background (which is exactly where [PhoneCallReceiver] runs during a
 * call), so the receiver alone can no longer start recording. A bound
 * accessibility service runs in a system-bound context and observes the
 * dialer's in-call UI coming to the foreground, giving us a second,
 * working trigger path on modern Android.
 *
 * Behavior:
 * - In-call UI appears (dialer/telecom package) -> start
 *   [CallRecordingService] with the receiver's pending number/direction
 *   when fresh, honoring the user's auto-record toggle and RECORD_AUDIO.
 * - In-call UI disappears AND telephony is IDLE -> stop recording.
 *   The telephony-IDLE guard prevents cutting the recording when the user
 *   briefly opens another app mid-call. Without READ_PHONE_STATE we never
 *   auto-stop from here (the receiver owns the stop on older APIs).
 */
class CallRecordingAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CallRecordingA11y"
        private const val PENDING_FRESH_MS = 60_000L
        private const val STOP_DEBOUNCE_MS = 3_000L

        /** Packages that host the system cellular in-call UI. */
        private val DIALER_PACKAGES = setOf(
            "com.android.server.telecom", // AOSP/ Pixel in-call UI
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.huawei.contacts",
            "com.huawei.android.dialer"
        )

        var isServiceEnabled = false
            private set

        /**
         * Static [isServiceEnabled] resets on process death. Always prefer
         * this system query for UI state.
         */
        fun isEnabledInSystem(context: android.content.Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return isServiceEnabled
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabled)
                while (colonSplitter.hasNext()) {
                    val component = android.content.ComponentName.unflattenFromString(colonSplitter.next())
                    if (component != null && component.packageName == context.packageName &&
                        component.className.endsWith("CallRecordingAccessibilityService")
                    ) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                isServiceEnabled
            }
        }
    }

    private var inCallUiVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Config delivers window-state changes only; anything else is ignored.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        val inCallUi = pkg in DIALER_PACKAGES ||
            "incall" in cls.lowercase() ||
            "in_call" in cls.lowercase()
        if (inCallUi == inCallUiVisible) return
        inCallUiVisible = inCallUi
        if (inCallUi) {
            Log.d(TAG, "In-call UI visible ($pkg/$cls)")
            cancelPendingStop()
            maybeStartRecording()
        } else {
            Log.d(TAG, "In-call UI gone ($pkg/$cls)")
            scheduleStop()
        }
    }

    private fun maybeStartRecording() {
        if (CallStateTracker.isRecording.value) {
            Log.d(TAG, "Already recording, a11y trigger ignored.")
            return
        }
        if (!AutoRecordPrefs.isEnabled(this)) {
            Log.d(TAG, "Auto-record disabled by user, a11y trigger ignored.")
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted, cannot auto-record.")
            return
        }
        val pendingFresh = System.currentTimeMillis() - CallStateTracker.pendingAtMillis < PENDING_FRESH_MS
        val number = if (pendingFresh) CallStateTracker.pendingNumber else null
        val direction = if (pendingFresh) CallStateTracker.pendingDirection else guessDirection()

        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START_RECORDING
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, number)
            putExtra(CallRecordingService.EXTRA_CALL_DIRECTION, direction)
        }
        try {
            // A system-bound accessibility service may start the foreground
            // service where a background broadcast receiver can no longer
            // do so (Android 12+). "Display over other apps" also grants
            // this exemption — see the permissions guide tab.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "Auto-start recording (direction=$direction, number known=${number != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-start blocked: grant 'Display over other apps' + disable battery optimization, then retry a call.", e)
        }
    }

    private fun scheduleStop() {
        cancelPendingStop()
        val stop = Runnable {
            pendingStop = null
            if (!CallStateTracker.isRecording.value) return@Runnable
            // Never cut an active call: without a readable IDLE telephony
            // state we leave stopping to the receiver/app.
            if (!isTelephonyIdle()) {
                Log.d(TAG, "Telephony not IDLE, keeping recording.")
                return@Runnable
            }
            val intent = Intent(this, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_STOP_RECORDING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.i(TAG, "Auto-stop recording after call UI dismissed.")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-stop blocked", e)
            }
        }
        pendingStop = stop
        handler.postDelayed(stop, STOP_DEBOUNCE_MS)
    }

    private fun cancelPendingStop() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    /**
     * True only when we can positively read IDLE. Missing READ_PHONE_STATE
     * -> false (fail safe: never auto-stop blindly).
     */
    private fun isTelephonyIdle(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.callState == TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            Log.w(TAG, "Could not read telephony state", e)
            false
        }
    }

    private fun guessDirection(): String {
        return try {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return "INBOUND"
            }
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            if (tm.callState == TelephonyManager.CALL_STATE_RINGING) "INBOUND" else "OUTBOUND"
        } catch (_: Exception) {
            "INBOUND"
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceEnabled = false
        inCallUiVisible = false
        cancelPendingStop()
        Log.d(TAG, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }
}
