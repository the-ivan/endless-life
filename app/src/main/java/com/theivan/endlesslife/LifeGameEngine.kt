package com.theivan.endlesslife

/**
 * 25×25 Conway's Game of Life.
 */
class LifeGameEngine(
    val width: Int = GlyphRenderer.MATRIX_SIZE,
    val height: Int = GlyphRenderer.MATRIX_SIZE
) {
    private var grid: Array<IntArray> = Array(height) { IntArray(width) }

    fun setGrid(newGrid: Array<IntArray>) {
        require(newGrid.size == height && newGrid.all { it.size == width })

        for (r in 0 until height) {
            System.arraycopy(newGrid[r], 0, grid[r], 0, width)
        }
    }

    fun getGrid(): Array<IntArray> = Array(height) { r -> grid[r].copyOf() }

    fun step() {
        val next = Array(height) { IntArray(width) }

        for (r in 0 until height) {
            for (c in 0 until width) {
                val neighbors = countLiveNeighbors(r, c)
                val alive = grid[r][c] == 1

                next[r][c] = when {
                    alive && (neighbors == 2 || neighbors == 3) -> 1
                    !alive && neighbors == 3 -> 1
                    else -> 0
                }
            }
        }
        grid = next
    }

    private fun countLiveNeighbors(row: Int, col: Int): Int {
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r !in 0 until height || c !in 0 until width) continue
                count += grid[r][c]
            }
        }
        return count
    }

    fun isExtinct(): Boolean {
        for (r in 0 until height) {
            for (c in 0 until width) {
                if (grid[r][c] == 1) return false
            }
        }
        return true
    }

    fun population(): Int {
        var count = 0
        for (r in 0 until height) {
            for (c in 0 until width) {
                count += grid[r][c]
            }
        }
        return count
    }
}
