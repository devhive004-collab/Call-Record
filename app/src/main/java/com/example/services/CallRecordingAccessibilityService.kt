package com.example.services

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

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
 * - In-call UI appears (dialer/telecom package + in-call class hints) ->
 *   start [CallRecordingService] with the receiver's pending
 *   number/direction when fresh, honoring the user's auto-record toggle
 *   and RECORD_AUDIO.
 * - If the system still blocks the start, a high-priority "tap to
 *   record" notification is posted: tapping brings the app to the
 *   foreground (where recording is allowed) and starts it immediately.
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
        private const val CHANNEL_FALLBACK = "sajil_tap_to_record"
        private const val NOTIF_ID_FALLBACK = 102

        /**
         * Packages that ONLY host call UI: any window from these means a
         * call screen (no class-name check needed).
         */
        private val PURE_INCALL_PACKAGES = setOf(
            "com.android.server.telecom", // AOSP in-call UI host
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.lge.incallui"
        )

        /** Dialer/phone apps per OEM. Class name must ALSO look like an
         * active-call screen (see [INCALL_CLASS_HINTS]) so merely opening
         * contacts/recents never triggers a recording. */
        private val DIALER_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.samsung.android.dialer",
            "com.huawei.contacts",
            "com.huawei.android.dialer",
            "com.coloros.dialer", // OPPO / Realme
            "com.oneplus.dialer",
            "com.vivo.dialer",
            "com.sonymobile.dialer",
            "com.motorola.dialer",
            "com.asus.dialer",
            "com.tct.dialer", // TCL / Alcatel
            "com.zte.dialer"
        )

        private val INCALL_CLASS_HINTS = listOf(
            "incall", "in_call", "ongoingcall", "callscreen", "activecall"
        )

        var isServiceEnabled = false
            private set

        /** Last window package seen — on-device diagnostics (guide tab). */
        @Volatile
        var lastWindowPkg: String? = null
            private set

        /** Human-readable outcome of the last auto-start attempt. */
        @Volatile
        var lastTriggerResult: String = "لم يتم رصد أي مكالمة بعد"
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
    private var fallbackNotified = false
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
        lastWindowPkg = pkg
        val inCallUi = isCallUi(pkg, cls)
        if (inCallUi == inCallUiVisible) return
        inCallUiVisible = inCallUi
        if (inCallUi) {
            Log.d(TAG, "In-call UI visible ($pkg/$cls)")
            cancelPendingStop()
            maybeStartRecording()
        } else {
            Log.d(TAG, "In-call UI gone ($pkg/$cls)")
            fallbackNotified = false
            cancelFallbackNotification()
            scheduleStop()
        }
    }

    private fun isCallUi(pkg: String, cls: String): Boolean {
        if (pkg in PURE_INCALL_PACKAGES) return true
        if (pkg !in DIALER_PACKAGES) return false
        val lc = cls.lowercase()
        return INCALL_CLASS_HINTS.any { it in lc }
    }

    private fun maybeStartRecording() {
        if (CallStateTracker.isRecording.value) {
            lastTriggerResult = "يعمل بالفعل — تم تجاهل المحاولة"
            Log.d(TAG, "Already recording, a11y trigger ignored.")
            return
        }
        if (!AutoRecordPrefs.isEnabled(this)) {
            lastTriggerResult = "التسجيل التلقائي مغلق من الإعدادات"
            Log.d(TAG, "Auto-record disabled by user, a11y trigger ignored.")
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            lastTriggerResult = "مرفوض: لا توجد صلاحية الميكروفون"
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
            lastTriggerResult = "بدأ التسجيل تلقائياً"
            cancelFallbackNotification()
            Log.i(TAG, "Auto-start recording (direction=$direction, number known=${number != null})")
        } catch (e: Exception) {
            lastTriggerResult = "محظور من النظام — تحقق من صلاحية الظهور فوق التطبيقات"
            Log.e(TAG, "Auto-start blocked, posting tap-to-record fallback", e)
            notifyTapToRecord()
        }
    }

    /**
     * Last-resort path: when the OS blocks even this service from starting
     * the recorder, one tap brings the app to the foreground (where
     * recording is always allowed) and starts it immediately.
     */
    private fun notifyTapToRecord() {
        if (fallbackNotified) return
        fallbackNotified = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "No notification permission for fallback prompt")
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_FALLBACK,
                        "التسجيل التلقائي",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val tapIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_TAP_TO_RECORD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_FALLBACK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("مكالمة جارية")
                .setContentText("تعذّر بدء التسجيل تلقائياً — اضغط لبدء التسجيل الآن")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_FALLBACK, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post tap-to-record fallback", e)
        }
    }

    private fun cancelFallbackNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_FALLBACK)
        } catch (_: Exception) {
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
        fallbackNotified = false
        cancelPendingStop()
        cancelFallbackNotification()
        Log.d(TAG, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }
}
