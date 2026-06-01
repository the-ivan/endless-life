package com.theivan.endlesslife

import org.junit.Assert.*
import org.junit.Test

class LifeGameEngineTest {

    // === Basic Rules ===

    @Test
    fun `single live cell dies from underpopulation`() {
        val engine = LifeGameEngine(5, 5)
        engine.setGrid(arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        ))
        engine.step()
        assertTrue(engine.isExtinct())
    }

    @Test
    fun `live cell with exactly 2 neighbors survives`() {
        val engine = LifeGameEngine(5, 5)
        // Horizontal blinker center should survive
        engine.setGrid(arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,1,1,1,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        ))
        engine.step()
        val grid = engine.getGrid()
        assertEquals(1, grid[2][1])
        assertEquals(1, grid[2][2])
        assertEquals(1, grid[2][3])
    }

    @Test
    fun `dead cell with exactly 3 neighbors is born`() {
        val engine = LifeGameEngine(5, 5)
        engine.setGrid(arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,1,1,0,0),
            intArrayOf(0,1,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        ))
        engine.step()
        val grid = engine.getGrid()
        assertEquals(1, grid[1][2]) // should be born
    }

    @Test
    fun `live cell with 4 neighbors dies from overpopulation`() {
        val engine = LifeGameEngine(5, 5)
        engine.setGrid(arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,1,1,1,0),
            intArrayOf(0,1,1,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        ))
        engine.step()
        val grid = engine.getGrid()
        assertEquals(0, grid[1][1]) // center should die
    }

    // === Still Lifes ===

    @Test
    fun `block still life stays stable`() {
        val engine = LifeGameEngine(4, 4)
        val block = arrayOf(
            intArrayOf(0,0,0,0),
            intArrayOf(0,1,1,0),
            intArrayOf(0,1,1,0),
            intArrayOf(0,0,0,0)
        )
        engine.setGrid(block)
        engine.step()
        assertArrayEquals(block, engine.getGrid())
    }

    @Test
    fun `beehive still life stays stable`() {
        val engine = LifeGameEngine(6, 5)
        val beehive = arrayOf(
            intArrayOf(0,0,0,0,0,0),
            intArrayOf(0,0,1,1,0,0),
            intArrayOf(0,1,0,0,1,0),
            intArrayOf(0,0,1,1,0,0),
            intArrayOf(0,0,0,0,0,0)
        )
        engine.setGrid(beehive)
        engine.step()
        assertArrayEquals(beehive, engine.getGrid())
    }

    // === Oscillators ===

    @Test
    fun `blinker oscillates correctly`() {
        val engine = LifeGameEngine(5, 5)
        val horizontal = arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,1,1,1,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        )
        val vertical = arrayOf(
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,0,0,0)
        )

        engine.setGrid(horizontal)
        engine.step()
        assertArrayEquals(vertical, engine.getGrid())

        engine.step()
        assertArrayEquals(horizontal, engine.getGrid())
    }

    @Test
    fun `toad oscillates correctly`() {
        val engine = LifeGameEngine(6, 6)
        val phase1 = arrayOf(
            intArrayOf(0,0,0,0,0,0),
            intArrayOf(0,0,0,0,0,0),
            intArrayOf(0,0,1,1,1,0),
            intArrayOf(0,1,1,1,0,0),
            intArrayOf(0,0,0,0,0,0),
            intArrayOf(0,0,0,0,0,0)
        )
        val phase2 = arrayOf(
            intArrayOf(0,0,0,0,0,0),
            intArrayOf(0,0,0,1,0,0),
            intArrayOf(0,1,0,0,1,0),
            intArrayOf(0,1,0,0,1,0),
            intArrayOf(0,0,1,0,0,0),
            intArrayOf(0,0,0,0,0,0)
        )

        engine.setGrid(phase1)
        engine.step()
        assertArrayEquals(phase2, engine.getGrid())
    }

    // === Extinct & Population ===

    @Test
    fun `empty grid is extinct and has zero population`() {
        val engine = LifeGameEngine(5, 5)
        assertTrue(engine.isExtinct())
        assertEquals(0, engine.population())
    }

    @Test
    fun `full grid dies quickly and reports correct population`() {
        val engine = LifeGameEngine(3, 3)
        val full = Array(3) { IntArray(3) { 1 } }
        engine.setGrid(full)
        assertEquals(9, engine.population())
        assertFalse(engine.isExtinct())

        engine.step()
        assertTrue(engine.isExtinct())
        assertEquals(0, engine.population())
    }

    // === Boundaries (no wrap-around) ===

    @Test
    fun `cells on edge do not wrap around`() {
        val engine = LifeGameEngine(5, 5)
        // Three cells in a row on the left edge
        engine.setGrid(arrayOf(
            intArrayOf(1,1,1,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0),
            intArrayOf(0,0,0,0,0)
        ))
        engine.step()
        // Should all die (only 1 or 2 neighbors because no wrap)
        assertTrue(engine.isExtinct())
    }

    // === Utility methods ===

    @Test
    fun `setGrid and getGrid roundtrip works`() {
        val engine = LifeGameEngine(4, 4)
        val original = arrayOf(
            intArrayOf(0,1,0,0),
            intArrayOf(1,0,1,0),
            intArrayOf(0,1,0,1),
            intArrayOf(0,0,0,0)
        )
        engine.setGrid(original)
        val copy = engine.getGrid()
        assertArrayEquals(original, copy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setGrid rejects wrong dimensions`() {
        val engine = LifeGameEngine(5, 5)
        val badGrid = arrayOf(intArrayOf(1,0), intArrayOf(0,1))
        engine.setGrid(badGrid)
    }
}