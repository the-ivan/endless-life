package com.theivan.endlesslife

/**
 * Renders 25×25 Life grids into the format expected by the Glyph Matrix SDK.
 */
object GlyphRenderer {

    const val MATRIX_SIZE = 25
    const val FRAME_SIZE = MATRIX_SIZE * MATRIX_SIZE

    /**
     * Renders into a pre-allocated buffer (avoids allocations in the hot loop).
     * This is the fast raw path.
     */
    fun renderInto(grid: Array<IntArray>, brightness: Int, outFrame: IntArray) {
        require(grid.size == MATRIX_SIZE && grid.all { it.size == MATRIX_SIZE }) {
            "Grid must be exactly 25x25 for Phone (3)"
        }
        require(outFrame.size == FRAME_SIZE) { "outFrame must be $FRAME_SIZE elements" }

        outFrame.fill(0)

        for (r in 0 until MATRIX_SIZE) {
            for (c in 0 until MATRIX_SIZE) {
                if (grid[r][c] == 1) {
                    outFrame[r * MATRIX_SIZE + c] = brightness
                }
            }
        }
    }

    /**
     * Convenience version that allocates a new array (use only when you don't have a reusable one).
     */
    fun render(grid: Array<IntArray>, brightness: Int): IntArray {
        val frame = IntArray(FRAME_SIZE)
        renderInto(grid, brightness, frame)
        return frame
    }

    /**
     * Returns a pre-sized empty frame (all zeros).
     */
    fun emptyFrame(): IntArray = IntArray(FRAME_SIZE)

    /**
     * Renders only the cells for which the predicate returns true.
     * Extremely useful for reveal / build animations.
     */
    fun renderMasked(
        grid: Array<IntArray>,
        brightness: Int,
        shouldRender: (row: Int, col: Int) -> Boolean
    ): IntArray {
        val frame = IntArray(FRAME_SIZE)
        for (r in 0 until MATRIX_SIZE) {
            for (c in 0 until MATRIX_SIZE) {
                if (grid[r][c] == 1 && shouldRender(r, c)) {
                    frame[r * MATRIX_SIZE + c] = brightness
                }
            }
        }
        return frame
    }
}