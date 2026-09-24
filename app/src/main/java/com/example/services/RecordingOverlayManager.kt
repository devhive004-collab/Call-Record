package com.example.services

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.formatDuration
import com.example.ui.theme.MicRecordingColor
import com.example.ui.theme.SoftGray
import com.example.ui.theme.WhiteIce

/**
 * Floating in-call recording pill, drawn over the dialer by
 * [CallRecordingService] while a call is being recorded.
 *
 * The full-screen [com.example.CallActiveOverlay] only exists inside the
 * app — during a real call the user is in the dialer, so without this
 * overlay there is no visible timer/stop UI at all. Requires the
 * "Display over other apps" permission (requested in the guide tab);
 * without it recording continues notification-only.
 *
 * Window: WRAP_CONTENT pill pinned top-center, NOT_FOCUSABLE so the dialer
 * stays fully interactive (touch still reaches our buttons). Works API 24+
 * (TYPE_PHONE pre-O, TYPE_APPLICATION_OVERLAY on O+).
 */
class RecordingOverlayManager(
    context: Context,
    private val onStopClicked: () -> Unit
) {
    private val TAG = "RecordingOverlay"
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ComposeView? = null

    /** Must run on the main thread (service callbacks already are). */
    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(appContext)) {
            Log.i(TAG, "Overlay permission missing — recording notification-only.")
            return
        }
        try {
            val view = ComposeView(appContext).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
            }
            // No Android LifecycleOwner in a Service: state is collected with
            // composition-scoped remember + LaunchedEffect, and everything is
            // cancelled automatically when the view detaches (see strategy).
            view.setContent {
                OverlayPill(
                    onStop = onStopClicked,
                    onHide = { hide() }
                )
            }
            windowManager.addView(view, overlayParams())
            overlayView = view
            Log.d(TAG, "In-call overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show in-call overlay", e)
            overlayView = null
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    fun isShowing(): Boolean = overlayView != null

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val density = appContext.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (16 * density).toInt()
        }
    }
}

@Composable
private fun OverlayPill(onStop: () -> Unit, onHide: () -> Unit) {
    // Composition-scoped collection (no Android lifecycle in a Service).
    var callerName by remember { mutableStateOf(CallStateTracker.callerName.value) }
    var durationSec by remember { mutableStateOf(CallStateTracker.durationSec.value) }
    LaunchedEffect(Unit) {
        launch { CallStateTracker.callerName.collect { callerName = it } }
        launch { CallStateTracker.durationSec.collect { durationSec = it } }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B1B1B).copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Recording indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MicRecordingColor)
                )
                // Caller (bounded so the pill never overflows narrow screens)
                Text(
                    text = callerName,
                    color = WhiteIce,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                // Live timer (fixed min width avoids jitter)
                Text(
                    text = formatDuration(durationSec),
                    color = WhiteIce,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
                // Stop & save
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MicRecordingColor)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إيقاف وحفظ",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Hide pill (recording continues; reopen via the app)
                IconButton(
                    onClick = onHide,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "إخفاء",
                        tint = SoftGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
