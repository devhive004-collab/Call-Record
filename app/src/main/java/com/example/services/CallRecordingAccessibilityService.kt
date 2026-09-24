package com.example.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CallRecordingAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CallRecordingA11y"
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No events processed. NOTE: merely being bound does NOT grant any
        // background-audio exemption — VOICE_RECOGNITION in background is
        // still subject to mic/FGS restrictions. This service exists only
        // for future call-UI detection; remove it if unused to avoid Play
        // "Accessibility API misuse" policy risk.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceEnabled = false
        Log.d(TAG, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }
}
