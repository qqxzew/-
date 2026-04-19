package com.meemaw.assist.agent

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.meemaw.assist.accessibility.MeemawAccessibilityService

class ScreenActions {

    companion object {
        private const val TAG = "ScreenActions"
    }

    fun tap(x: Float, y: Float) {
        val service = MeemawAccessibilityService.instance ?: return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Tap at ($x, $y)")
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300) {
        val service = MeemawAccessibilityService.instance ?: return
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe ($startX,$startY) -> ($endX,$endY)")
    }

    fun typeText(text: String) {
        val service = MeemawAccessibilityService.instance ?: return
        val root = service.rootInActiveWindow ?: return
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
        }
        root.recycle()
        Log.d(TAG, "Typed text: ${text.take(20)}...")
    }
}
