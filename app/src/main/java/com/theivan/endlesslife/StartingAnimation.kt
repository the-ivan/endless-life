package com.theivan.endlesslife

import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Starting reveal animations for new lives.
 */
object StartingAnimation {

    private const val MATRIX = GlyphRenderer.MATRIX_SIZE

    private const val TARGET_DURATION_MS = 1150L

    /** Reveals the pattern row by row from the top. */
    suspend fun revealRowByRow(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        rowDelayMs: Long = TARGET_DURATION_MS / MATRIX
    ) {
        try {
            for (row in 0 until MATRIX) {
                val frame = GlyphRenderer.renderMasked(initialGrid, brightness) { r, _ -> r <= row }
                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }
                delay(rowDelayMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** Reveals the pattern column by column from left to right. */
    suspend fun revealColumnByColumn(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        columnDelayMs: Long = TARGET_DURATION_MS / MATRIX
    ) {
        try {
            for (col in 0 until MATRIX) {
                val frame = GlyphRenderer.renderMasked(initialGrid, brightness) { _, c -> c <= col }
                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }
                delay(columnDelayMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** Reveals the pattern row by row from the bottom up. */
    suspend fun revealBottomUp(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        rowDelayMs: Long = TARGET_DURATION_MS / MATRIX
    ) {
        try {
            for (row in (MATRIX - 1) downTo 0) {
                val frame = GlyphRenderer.renderMasked(initialGrid, brightness) { r, _ -> r >= row }
                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }
                delay(rowDelayMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Reveals the initial pattern column by column from right to left.
     */
    suspend fun revealRightToLeft(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        columnDelayMs: Long = TARGET_DURATION_MS / MATRIX
    ) {
        try {
            for (col in (MATRIX - 1) downTo 0) {
                val frame = GlyphRenderer.renderMasked(initialGrid, brightness) { _, c -> c >= col }
                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }
                delay(columnDelayMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Reveals the initial pattern expanding outward from the center.
     * Uses Manhattan distance for a nice diamond-style growth on the grid.
     */
    suspend fun revealCenterOutward(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        ringDelayMs: Long = TARGET_DURATION_MS / 26
    ) {
        val center = MATRIX / 2
        try {
            for (dist in 0..(MATRIX)) {
                val frame = IntArray(MATRIX * MATRIX)

                for (r in 0 until MATRIX) {
                    for (c in 0 until MATRIX) {
                        val d = abs(r - center) + abs(c - center)
                        if (d <= dist && initialGrid[r][c] == 1) {
                            frame[r * MATRIX + c] = brightness
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }

                delay(ringDelayMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Reveals the initial pattern in a spiral starting from the center and moving outward.
     * Chunky + tuned to ~1.1-1.25s total.
     */
    suspend fun revealSpiralCenterOut(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        stepDelayMs: Long = 30,
        chunkSize: Int = 5
    ) {
        val coords = generateSpiralCoordinates(center = MATRIX / 2, outward = true)
        revealAlongPath(manager, initialGrid, brightness, coords, stepDelayMs, chunkSize)
    }

    /**
     * Reveals the initial pattern in a spiral starting from the outer edge and moving inward to the center.
     * Chunky + tuned to ~1.1-1.25s total.
     */
    suspend fun revealSpiralEdgeToCenter(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        stepDelayMs: Long = 30,
        chunkSize: Int = 5
    ) {
        val coords = generateSpiralCoordinates(center = MATRIX / 2, outward = false)
        revealAlongPath(manager, initialGrid, brightness, coords, stepDelayMs, chunkSize)
    }

    /** Linear fade-in followed by hold at full brightness. */
    suspend fun fadeIn(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        steps: Int = 24,
        stepDelayMs: Long = 48,
        holdAtFullMs: Long = 500L
    ) {
        try {
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                val currentBrightness = (brightness * t).toInt().coerceAtLeast(0)

                val frame = IntArray(MATRIX * MATRIX)
                for (r in 0 until MATRIX) {
                    for (c in 0 until MATRIX) {
                        if (initialGrid[r][c] == 1) {
                            frame[r * MATRIX + c] = currentBrightness
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                }

                delay(stepDelayMs)
            }

            val fullFrame = IntArray(MATRIX * MATRIX)
            for (r in 0 until MATRIX) {
                for (c in 0 until MATRIX) {
                    if (initialGrid[r][c] == 1) {
                        fullFrame[r * MATRIX + c] = brightness
                    }
                }
            }

            withContext(Dispatchers.Main) {
                safeMatrixUpdate(manager) { it.setMatrixFrame(fullFrame) }
            }
            delay(holdAtFullMs)

        } catch (e: CancellationException) {
            throw e
        }
    }

    suspend fun playAnimation(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        type: StartingAnimationType
    ) {
        when (type) {
            StartingAnimationType.ROW_BY_ROW -> revealRowByRow(manager, initialGrid, brightness)
            StartingAnimationType.BOTTOM_UP -> revealBottomUp(manager, initialGrid, brightness)
            StartingAnimationType.COLUMN_BY_COLUMN -> revealColumnByColumn(manager, initialGrid, brightness)
            StartingAnimationType.RIGHT_TO_LEFT -> revealRightToLeft(manager, initialGrid, brightness)
            StartingAnimationType.CENTER_OUTWARD -> revealCenterOutward(manager, initialGrid, brightness)
            StartingAnimationType.SPIRAL_CENTER_OUT -> revealSpiralCenterOut(manager, initialGrid, brightness)
            StartingAnimationType.SPIRAL_EDGE_TO_CENTER -> revealSpiralEdgeToCenter(manager, initialGrid, brightness)
            StartingAnimationType.FADE_IN -> fadeIn(manager, initialGrid, brightness)
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Generates coordinates in spiral order.
     * outward = true  → starts at center and spirals out
     * outward = false → starts at edges and spirals in (reversed list)
     */
    private fun generateSpiralCoordinates(center: Int, outward: Boolean): List<Pair<Int, Int>> {
        val visited = Array(MATRIX) { BooleanArray(MATRIX) }
        val result = mutableListOf<Pair<Int, Int>>()

        // Directions: right, down, left, up
        val dr = intArrayOf(0, 1, 0, -1)
        val dc = intArrayOf(1, 0, -1, 0)

        var r = center
        var c = center
        var dir = 0
        var steps = 1          // current leg length
        var legCount = 0

        // Start at center
        if (r in 0 until MATRIX) {
            visited[r][c] = true
            result.add(r to c)
        }

        while (result.size < MATRIX * MATRIX) {
            for (i in 0 until steps) {
                r += dr[dir]
                c += dc[dir]

                if (r in 0 until MATRIX && c in 0 until MATRIX && !visited[r][c]) {
                    visited[r][c] = true
                    result.add(r to c)
                }
            }

            dir = (dir + 1) % 4
            legCount++

            // Increase leg length every two turns
            if (legCount % 2 == 0) {
                steps++
            }
        }

        if (!outward) {
            result.reverse()
        }

        return result
    }

    private suspend fun revealAlongPath(
        manager: GlyphMatrixManager,
        initialGrid: Array<IntArray>,
        brightness: Int,
        path: List<Pair<Int, Int>>,
        stepDelayMs: Long,
        chunkSize: Int = 1
    ) {
        try {
            val revealed = Array(MATRIX) { BooleanArray(MATRIX) }
            var cellsLitSinceUpdate = 0

            for ((r, c) in path) {
                var litNewCell = false

                if (initialGrid[r][c] == 1 && !revealed[r][c]) {
                    revealed[r][c] = true
                    litNewCell = true
                    cellsLitSinceUpdate++
                }

                // Send an update when we've lit enough new cells (chunky steps)
                // or as a safety net on very sparse patterns.
                if (litNewCell && cellsLitSinceUpdate >= chunkSize) {
                    val frame = IntArray(MATRIX * MATRIX)
                    for (rr in 0 until MATRIX) {
                        for (cc in 0 until MATRIX) {
                            if (revealed[rr][cc]) {
                                frame[rr * MATRIX + cc] = brightness
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        safeMatrixUpdate(manager) { it.setMatrixFrame(frame) }
                    }

                    cellsLitSinceUpdate = 0
                    delay(stepDelayMs)
                }
            }

            // Always send a final frame so the full pattern is visible
            val finalFrame = IntArray(MATRIX * MATRIX)
            for (rr in 0 until MATRIX) {
                for (cc in 0 until MATRIX) {
                    if (initialGrid[rr][cc] == 1) {
                        finalFrame[rr * MATRIX + cc] = brightness
                    }
                }
            }
            withContext(Dispatchers.Main) {
                safeMatrixUpdate(manager) { it.setMatrixFrame(finalFrame) }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
