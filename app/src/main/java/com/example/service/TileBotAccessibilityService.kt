package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.random.Random

class TileBotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        BotStateBridge.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not inspecting text/UI trees; vision runs via MediaProjection bitmaps.
    }

    override fun onInterrupt() {
        BotStateBridge.setAccessibilityConnected(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        BotStateBridge.setAccessibilityConnected(false)
    }

    suspend fun performTap(x: Float, y: Float, jitterPx: Int = 3): Boolean {
        val jx = (x + Random.nextInt(-jitterPx, jitterPx + 1)).coerceAtLeast(1f)
        val jy = (y + Random.nextInt(-jitterPx, jitterPx + 1)).coerceAtLeast(1f)
        val durationMs = Random.nextLong(36L, 58L)

        val path = Path().apply {
            moveTo(jx, jy)
            lineTo(jx + 0.5f, jy + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<TileBotAccessibilityService>? = null

        fun current(): TileBotAccessibilityService? = instanceRef?.get()
    }
}
