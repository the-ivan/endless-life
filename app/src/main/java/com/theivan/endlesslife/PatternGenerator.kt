package com.theivan.endlesslife

import kotlin.random.Random

/**
 * Time-seeded initial patterns for Endless Life.
 */
object PatternGenerator {

    fun seedFromTime(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0
    ): Long {
        return year.toLong() * 100_000_000L +
                month.toLong() * 1_000_000L +
                day.toLong() * 10_000L +
                hour.toLong() * 100L +
                minute.toLong() * 10L +
                second.toLong()
    }

    /**
     * @param density fraction of cells that start alive (0.0–1.0)
     */
    fun generate(
        seed: Long,
        height: Int = GlyphRenderer.MATRIX_SIZE,
        width: Int = GlyphRenderer.MATRIX_SIZE,
        density: Double = 0.33
    ): Array<IntArray> {
        val grid = Array(height) { IntArray(width) }
        val random = Random(seed)
        val totalCells = height * width
        val target = (totalCells * density).toInt().coerceAtLeast(3).coerceAtMost(totalCells)

        val positions = (0 until totalCells).toMutableList()
        for (i in 0 until target) {
            val j = random.nextInt(i, totalCells)
            val temp = positions[i]
            positions[i] = positions[j]
            positions[j] = temp

            val pos = positions[i]
            val r = pos / width
            val c = pos % width
            grid[r][c] = 1
        }
        return grid
    }
}
