package com.theivan.endlesslife

import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Pause + smooth fade-out between lives.
 */
object EndingAnimation {

    private const val MATRIX = 25

    /**
     * 50 steps over 1.2s for a silky brightness fade.
     */
    suspend fun pauseAndFadeOut(
        manager: GlyphMatrixManager,
        grid: Array<IntArray>,
        brightness: Int,
        pauseMs: Long = 300,
        fadeDurationMs: Long = 1200
    ) {
        try {
            delay(pauseMs)

            val steps = 50
            val stepDelay = (fadeDurationMs.toDouble() / steps).toLong().coerceAtLeast(8L)

            for (i in (steps - 1) downTo 0) {
                val currentBrightness = (brightness * (i / steps.toFloat())).toInt()
                val frame = GlyphRenderer.render(grid, currentBrightness)

                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }

                delay(stepDelay)
            }

            withContext(Dispatchers.Main) {
                // Final clear can stay raw
                safeMatrixUpdate(manager) { it.setMatrixFrame(GlyphRenderer.emptyFrame()) }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}