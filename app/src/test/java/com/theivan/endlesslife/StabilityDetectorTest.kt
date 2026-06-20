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
    fun `still life is detected on repeat`() {
        val detector = StabilityDetector(historySize = 4)
        val block = blockGrid()

        assertFalse(detector.addAndCheck(block))
        assertTrue(detector.addAndCheck(block))
    }

    @Test
    fun `period 2 oscillator is detected`() {
        val detector = StabilityDetector(historySize = 5)
        val h = blinkerHorizontal()
        val v = blinkerVertical()

        assertFalse(detector.addAndCheck(h))
        assertFalse(detector.addAndCheck(v))
        assertTrue(detector.addAndCheck(h))
        assertTrue(detector.addAndCheck(v))
    }

    @Test
    fun `repeating cyclic pattern is detected`() {
        val detector = StabilityDetector(historySize = 6)
        val grid1 = blockGrid()
        val grid2 = blinkerHorizontal()

        assertFalse(detector.addAndCheck(grid1))
        assertFalse(detector.addAndCheck(grid2))
        assertTrue(detector.addAndCheck(grid1))
    }

    @Test
    fun `reset clears history`() {
        val detector = StabilityDetector(historySize = 4)
        val block = blockGrid()

        assertFalse(detector.addAndCheck(block))
        assertTrue(detector.addAndCheck(block))
        detector.reset()

        assertFalse(detector.addAndCheck(block))
        assertTrue(detector.addAndCheck(block))
    }

    @Test
    fun `history size limits detection window`() {
        val detector = StabilityDetector(historySize = 2)
        val a = blockGrid()
        val b = blinkerHorizontal()
        val c = emptyGrid()

        assertFalse(detector.addAndCheck(a))
        assertFalse(detector.addAndCheck(b))
        assertFalse(detector.addAndCheck(c))
        assertTrue(detector.addAndCheck(b))
        assertFalse(detector.addAndCheck(a))
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

        assertFalse(detector.addAndCheck(block))
        assertTrue(detector.addAndCheck(block))

        detector.reset()

        assertFalse(detector.addAndCheck(beehive))
        assertTrue(detector.addAndCheck(beehive))
    }
}