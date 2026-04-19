package com.meemaw.assist.guide

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.meemaw.assist.R

/**
 * Floating step-by-step guide that sits above system screens and helps the
 * user through manual actions (like toggling Wi-Fi). Adds/removes a view
 * directly via [WindowManager] on the main thread — no Service layer, so we
 * avoid Android 12+ background-start restrictions.
 */
object GuideController {

    private const val TAG = "GuideController"

    @Volatile private var currentFlow: GuideFlow? = null
    @Volatile private var currentStepIndex: Int = 0
    @Volatile private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private val dismissRunnable = Runnable { dismiss() }

    fun start(context: Context, flow: GuideFlow) {
        val ctx = context.applicationContext
        appContext = ctx

        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "Overlay permission missing — cannot start guide '${flow.id}'")
            return
        }
        if (flow.steps.isEmpty()) return

        currentFlow = flow
        currentStepIndex = 0

        Log.i(TAG, "start flow=${flow.id} steps=${flow.steps.size}")
        showStep(ctx, flow.steps.first())
    }

    fun dismiss() {
        currentFlow = null
        currentStepIndex = 0
        mainHandler.removeCallbacks(dismissRunnable)
        runOnMain { removeOverlay() }
    }

    /** Called by the accessibility service when window content changes. */
    fun onScreenTexts(texts: List<String>) {
        val flow = currentFlow ?: return
        val step = flow.steps.getOrNull(currentStepIndex) ?: return
        if (step.advanceKeywords.isEmpty()) return

        val haystack = texts.joinToString(" | ") { it.lowercase() }
        val matched = step.advanceKeywords.any { keyword ->
            haystack.contains(keyword.lowercase())
        }
        if (!matched) return

        advance()
    }

    private fun advance() {
        val flow = currentFlow ?: return
        val ctx = appContext ?: return
        val nextIndex = currentStepIndex + 1

        if (nextIndex >= flow.steps.size) {
            dismiss()
            return
        }

        currentStepIndex = nextIndex
        val nextStep = flow.steps[nextIndex]

        showStep(ctx, nextStep)

        if (nextStep.finalStep && nextStep.advanceKeywords.isEmpty()) {
            mainHandler.removeCallbacks(dismissRunnable)
            mainHandler.postDelayed(dismissRunnable, 2600L)
        }
    }

    private fun showStep(ctx: Context, step: GuideStep) {
        runOnMain {
            try {
                val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
                    windowManager = it
                }
                val view = overlayView ?: LayoutInflater.from(ctx)
                    .inflate(R.layout.view_guide_overlay, null, false).also { inflated ->
                        inflated.findViewById<View>(R.id.btnGuideClose).setOnClickListener { dismiss() }
                        wm.addView(inflated, buildLayoutParams(ctx))
                        overlayView = inflated
                        Log.i(TAG, "Overlay added to WindowManager")
                    }
                view.findViewById<TextView>(R.id.tvGuideTitle).text = step.title
                view.findViewById<TextView>(R.id.tvGuideBody).text = step.body
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay step", e)
            }
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // already detached
        }
        overlayView = null
    }

    private fun buildLayoutParams(ctx: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val density = ctx.resources.displayMetrics.density
        params.y = (24 * density + 0.5f).toInt()
        return params
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
