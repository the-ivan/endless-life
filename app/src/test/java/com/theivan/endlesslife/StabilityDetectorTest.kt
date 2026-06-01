package com.theivan.endlesslife

import org.junit.Assert.*
import org.junit.Test

class StabilityDetectorTest {

    private fun emptyGrid(size: Int = 5): Array<IntArray> =
        Array(size) { IntArray(size) { 0 } }

    private fun blockGrid(size: Int = 5): Array<IntArray> {
        val grid = emptyGrid(size)
        grid[1][1] = 1
        grid[1][2] = 1
        grid[2][1] = 1
        grid[2][2] = 1
        return grid
    }

    private fun blinkerHorizontal(size: Int = 5): Array<IntArray> {
        val grid = emptyGrid(size)
        grid[2][1] = 1
        grid[2][2] = 1
        grid[2][3] = 1
        return grid
    }

    private fun blinkerVertical(size: Int = 5): Array<IntArray> {
        val grid = emptyGrid(size)
        grid[1][2] = 1
        grid[2][2] = 1
        grid[3][2] = 1
        return grid
    }

    @Test
    fun `still life is detected after history fills`() {
        val detector = StabilityDetector(historySize = 4)
        val block = blockGrid()

        // First few steps should not trigger (history not full enough)
        repeat(3) {
            assertFalse(detector.addAndCheck(block))
        }

        // On the 4th identical state, it should detect stability
        assertTrue(detector.addAndCheck(block))
    }

    @Test
    fun `period 2 oscillator is detected`() {
        val detector = StabilityDetector(historySize = 5)
        val h = blinkerHorizontal()
        val v = blinkerVertical()

        // Alternate between two states
        assertFalse(detector.addAndCheck(h))
        assertFalse(detector.addAndCheck(v))
        assertFalse(detector.addAndCheck(h))
        assertFalse(detector.addAndCheck(v))
        assertFalse(detector.addAndCheck(h))

        // 6th call should see the first horizontal again within window
        assertTrue(detector.addAndCheck(v))
    }

    @Test
    fun `changing pattern does not falsely trigger`() {
        val detector = StabilityDetector(historySize = 6)
        val grid1 = blockGrid()
        val grid2 = blinkerHorizontal()

        repeat(10) {
            assertFalse(detector.addAndCheck(grid1))
            assertFalse(detector.addAndCheck(grid2))
        }
    }

    @Test
    fun `reset clears history`() {
        val detector = StabilityDetector(historySize = 4)
        val block = blockGrid()

        repeat(3) { detector.addAndCheck(block) }
        detector.reset()

        // After reset, it should behave like new
        repeat(3) {
            assertFalse(detector.addAndCheck(block))
        }
        assertTrue(detector.addAndCheck(block))
    }

    @Test
    fun `history size limits detection window`() {
        val detector = StabilityDetector(historySize = 3)
        val h = blinkerHorizontal()
        val v = blinkerVertical()

        // With history size 3, it should still detect the oscillator
        assertFalse(detector.addAndCheck(h))
        assertFalse(detector.addAndCheck(v))
        assertFalse(detector.addAndCheck(h))
        assertTrue(detector.addAndCheck(v)) // v seen before within window
    }

    @Test
    fun `different still lifes are treated separately`() {
        val detector = StabilityDetector(historySize = 5)
        val block = blockGrid()
        val beehive = Array(5) { IntArray(5) { 0 } }.apply {
            this[1][2] = 1; this[1][3] = 1
            this[2][1] = 1; this[2][4] = 1
            this[3][2] = 1; this[3][3] = 1
        }

        repeat(4) { detector.addAndCheck(block) }
        assertTrue(detector.addAndCheck(block))

        detector.reset()

        repeat(4) { detector.addAndCheck(beehive) }
        assertTrue(detector.addAndCheck(beehive))
    }
}