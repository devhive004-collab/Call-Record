package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.services.CallRecordingService

class PhoneCallReceiver : BroadcastReceiver() {
    private val TAG = "PhoneCallReceiver"

    companion object {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
        private var isIncoming = false
        private var savedNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Capture outgoing number via NEW_OUTGOING_CALL (requires PROCESS_OUTGOING_CALLS).
        // PHONE_STATE OFFHOOK does not carry the outgoing number on many devices.
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val outgoing = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            if (outgoing != null) {
                savedNumber = outgoing
            }
            isIncoming = false
            Log.d(TAG, "New outgoing call to $savedNumber")
            return
        }

        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "onReceive: stateStr = $stateStr, number = $number")

        if (number != null) {
            savedNumber = number
        }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncoming = true
                lastState = TelephonyManager.EXTRA_STATE_RINGING
                Log.d(TAG, "Ringing... Incoming call detected.")
                // NOTE: Do NOT launch MainActivity from background here.
                // Background activity starts are blocked on Android 10+ and
                // interrupt the dialer. The recording service posts a
                // notification instead; user taps it to open the app.
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered or outgoing call placed
                val direction = if (isIncoming) "INBOUND" else "OUTBOUND"
                Log.d(TAG, "Off-hook: starting CallRecordingService. Direction = $direction, Number = $savedNumber")
                
                val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
                    action = CallRecordingService.ACTION_START_RECORDING
                    putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, savedNumber)
                    putExtra(CallRecordingService.EXTRA_CALL_DIRECTION, direction)
                }
                
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    // NOTE: Do NOT start MainActivity from background (Android 10+
                    // background-start restriction). See RINGING branch above.
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start call recording service (bg FGS restriction?)", e)
                }

                lastState = TelephonyManager.EXTRA_STATE_OFFHOOK
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Idle: stopping CallRecordingService.")
                val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
                    action = CallRecordingService.ACTION_STOP_RECORDING
                }
                try {
                    // Must use startForegroundService on O+ even for STOP, because
                    // the target is a foreground service. startService() throws
                    // IllegalStateException on API 26+.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recording service", e)
                }
                
                isIncoming = false
                savedNumber = null
                lastState = TelephonyManager.EXTRA_STATE_IDLE
            }
        }
    }
}
