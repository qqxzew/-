package com.meemaw.assist.agent

import android.util.Log
import com.meemaw.assist.accessibility.MeemawAccessibilityService

class ScreenReader {

    companion object {
        private const val TAG = "ScreenReader"
    }

    fun readCurrentScreen(): List<String> {
        val service = MeemawAccessibilityService.instance ?: run {
            Log.w(TAG, "AccessibilityService not active")
            return emptyList()
        }
        return try {
            val root = service.rootInActiveWindow ?: return emptyList()
            val elements = mutableListOf<String>()
            traverseNode(root, elements)
            root.recycle()
            elements
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read screen", e)
            emptyList()
        }
    }

    private fun traverseNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        elements: MutableList<String>
    ) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank()) elements.add(text)
        else if (!desc.isNullOrBlank()) elements.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements)
            child.recycle()
        }
    }
}
