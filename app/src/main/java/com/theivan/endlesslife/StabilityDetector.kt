package com.theivan.endlesslife

/**
 * Rolling hash history to detect stable / oscillating patterns.
 */
class StabilityDetector(private val historySize: Int = 8) {

    private val history = ArrayDeque<Int>()

    fun addAndCheck(grid: Array<IntArray>): Boolean {
        val hash = hashGrid(grid)
        val stable = history.contains(hash)

        history.addLast(hash)
        while (history.size > historySize) {
            history.removeFirst()
        }
        return stable
    }

    private fun hashGrid(grid: Array<IntArray>): Int {
        val sb = StringBuilder(grid.size * grid[0].size)
        for (row in grid) {
            for (cell in row) {
                sb.append(if (cell == 1) '1' else '0')
            }
        }
        return sb.toString().hashCode()
    }

    fun reset() {
        history.clear()
    }
}