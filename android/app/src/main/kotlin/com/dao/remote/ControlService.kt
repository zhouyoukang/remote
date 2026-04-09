package com.dao.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.JsonObject

class ControlService : AccessibilityService() {

    companion object {
        const val TAG = "ControlService"
        private var instance: ControlService? = null
        private val gson = Gson()

        fun isRunning(): Boolean = instance != null

        fun handleCommand(json: String) {
            val svc = instance ?: run {
                Log.w(TAG, "Service not running, ignoring command")
                return
            }

            try {
                val cmd = gson.fromJson(json, JsonObject::class.java)
                val type = cmd.get("type")?.asString ?: return

                when (type) {
                    "tap" -> {
                        val x = cmd.get("x").asFloat
                        val y = cmd.get("y").asFloat
                        svc.performTap(x, y)
                    }

                    "swipe" -> {
                        val x1 = cmd.get("x1").asFloat
                        val y1 = cmd.get("y1").asFloat
                        val x2 = cmd.get("x2").asFloat
                        val y2 = cmd.get("y2").asFloat
                        val duration = cmd.get("duration")?.asLong ?: 300L
                        svc.performSwipe(x1, y1, x2, y2, duration)
                    }

                    "longpress" -> {
                        val x = cmd.get("x").asFloat
                        val y = cmd.get("y").asFloat
                        val duration = cmd.get("duration")?.asLong ?: 1000L
                        svc.performLongPress(x, y, duration)
                    }

                    "back" -> svc.performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> svc.performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

                    "pinch" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val cx = cmd.get("cx").asFloat
                            val cy = cmd.get("cy").asFloat
                            val scale = cmd.get("scale").asFloat
                            svc.performPinch(cx, cy, scale)
                        }
                    }

                    "scroll" -> {
                        val x = cmd.get("x").asFloat
                        val y = cmd.get("y").asFloat
                        val dx = cmd.get("dx")?.asFloat ?: 0f
                        val dy = cmd.get("dy")?.asFloat ?: 0f
                        svc.performSwipe(x, y, x + dx, y + dy, 200L)
                    }

                    else -> Log.d(TAG, "Unknown command: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command error: ${e.message}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    private fun performLongPress(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    private fun performPinch(cx: Float, cy: Float, scale: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val radius = 200f
        val builder = GestureDescription.Builder()

        if (scale > 1f) {
            // Zoom in: fingers move apart
            val path1 = Path().apply {
                moveTo(cx - 20f, cy)
                lineTo(cx - radius, cy)
            }
            val path2 = Path().apply {
                moveTo(cx + 20f, cy)
                lineTo(cx + radius, cy)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 300))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 300))
        } else {
            // Zoom out: fingers move together
            val path1 = Path().apply {
                moveTo(cx - radius, cy)
                lineTo(cx - 20f, cy)
            }
            val path2 = Path().apply {
                moveTo(cx + radius, cy)
                lineTo(cx + 20f, cy)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 300))
            builder.addStroke(GestureDescription.StrokeDescription(path2, 0, 300))
        }

        dispatchGesture(builder.build(), null, null)
    }
}
