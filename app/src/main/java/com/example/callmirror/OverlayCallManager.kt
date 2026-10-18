package com.example.callmirror

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayCallManager(
    private val context: Context,
    private val onDismissRequested: (() -> Unit)? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "OverlayCallManager"
    }

    fun showOverlay(name: String?, number: String?) {
        mainHandler.post {
            try {
                // If an overlay already exists, remove it first cleanly so new calls always pop up reliably
                dismissOverlayImmediate()

                val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutParamsType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 25 // Sleek heads-up distance from top status bar
                }

                val themedContext = android.view.ContextThemeWrapper(context, R.style.Theme_CallMirror)
                val inflater = LayoutInflater.from(themedContext)
                val view = inflater.inflate(R.layout.overlay_call_dialog, null)

                val tvName = view.findViewById<TextView>(R.id.overlayCallerName)
                val tvNumber = view.findViewById<TextView>(R.id.overlayCallerNumber)
                val btnDismiss = view.findViewById<View>(R.id.overlayBtnDismiss)

                tvName.text = if (!name.isNullOrBlank()) name else "Unknown via Hotspot"
                tvNumber.text = if (!number.isNullOrBlank()) number else "Incoming Call"

                btnDismiss?.setOnClickListener {
                    Log.d(TAG, "Dismiss button clicked on overlay")
                    dismissOverlay()
                    onDismissRequested?.invoke()
                }

                windowManager.addView(view, params)
                overlayView = view
                Log.d(TAG, "Floating call overlay displayed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display overlay: ${e.message}")
            }
        }
    }

    private fun dismissOverlayImmediate() {
        try {
            overlayView?.let { v ->
                if (v.isAttachedToWindow) {
                    windowManager.removeViewImmediate(v)
                } else {
                    windowManager.removeView(v)
                }
            }
        } catch (ignored: Exception) {}
        overlayView = null
    }

    fun dismissOverlay() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissOverlayImmediate()
        } else {
            mainHandler.post {
                dismissOverlayImmediate()
            }
        }
    }
}
