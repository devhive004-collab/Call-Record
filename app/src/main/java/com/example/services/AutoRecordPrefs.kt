package com.example.services

import android.content.Context

/**
 * User toggle for automatic call recording (default ON).
 *
 * Both auto-start triggers — [com.example.receivers.PhoneCallReceiver]
 * (PHONE_STATE broadcast) and [CallRecordingAccessibilityService]
 * (in-call UI detection) — honor this flag. Manual recording from the
 * app UI is unaffected.
 */
object AutoRecordPrefs {
    private const val PREFS_NAME = "sajil_prefs"
    private const val KEY_AUTO_RECORD = "auto_record_calls"

    fun isEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RECORD, true)
        } catch (_: Exception) {
            true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
        } catch (_: Exception) {
            // Ignore persistence failure; default (enabled) still applies.
        }
    }
}
