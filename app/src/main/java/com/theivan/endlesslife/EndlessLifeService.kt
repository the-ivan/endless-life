package com.theivan.endlesslife

import android.content.Context
import android.util.Log
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.core.content.edit

/**
 * Endless Life — Conway's Game of Life for the Nothing Phone (3) Glyph Matrix.
 *
 * - Persistent across binds/unbinds (supports Always-on / Flip to Glyph)
 * - Time-seeded patterns with automatic stable/extinct detection
 * - Starting reveal animations and graceful ending fade
 * - Long press forces a new life via ending transition
 */
class EndlessLifeService : GlyphMatrixService("Endless-Life") {

    private var serviceJob = SupervisorJob()
    private var backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private var currentEngine: LifeGameEngine? = null
    private var lifeCycleJob: Job? = null
    private var lastLongPressTimeMs = 0L
    private var currentLifeStartingAnimType: StartingAnimationType? = null
    private var currentLifeAnimComplete: Boolean = false


    override fun performOnServiceConnected(context: Context, manager: GlyphMatrixManager) {
        Log.d("EndlessLife", ">>> performOnServiceConnected CALLED <<< (matrixLen=$detectedMatrixLength)")

        // Always start with a fresh scope for this bind session.
        serviceJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)

        resetCurrentLifeAnimationState()

        val matrixLen = detectedMatrixLength
        if (matrixLen != 25) {
            Log.w("EndlessLife", "This toy requires a 25×25 Glyph Matrix (Nothing Phone 3). " +
                    "Current device reports length=$matrixLen. Not starting.")
            return
        }

        val settings = settingsRepository.getSettings()
        val persisted = if (settings.resumeEnabled) loadLifeState() else null

        val engine = LifeGameEngine()
        val initialGridForReveal: Array<IntArray>?
        val animTypeForThisLife: StartingAnimationType?

        when {
            persisted == null -> {
                // Brand new life - pick animation, mark incomplete
                val (grid, type) = generateFreshPattern()
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false
                engine.setGrid(grid)
                initialGridForReveal = grid
                animTypeForThisLife = type
            }

            !persisted.startingAnimComplete -> {
                // We have a saved grid but its starting animation was never completed
                // (e.g. unbind happened during reveal). Replay the same animation.
                val type = persisted.startingAnimType
                    ?: (settings.enabledAnimations.randomOrNull() ?: StartingAnimationType.ROW_BY_ROW)
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false

                engine.setGrid(persisted.grid)
                initialGridForReveal = persisted.grid
                animTypeForThisLife = type

                Log.d("EndlessLife", ">>> Resuming interrupted starting animation (type=$type) <<<")
            }

            else -> {
                // Normal mid-simulation resume
                currentLifeStartingAnimType = null
                currentLifeAnimComplete = true

                engine.setGrid(persisted.grid)
                initialGridForReveal = null
                animTypeForThisLife = null
            }
        }
        currentEngine = engine

        // For true mid-sim resume: push the saved grid immediately (no animation).
        // For anything that needs a reveal (fresh or interrupted): let the animation draw first.
        if (initialGridForReveal == null) {
            val firstFrame = GlyphRenderer.render(engine.getGrid(), BRIGHTNESS)
            try {
                manager.setMatrixFrame(firstFrame)
                Log.d("EndlessLife", ">>> First frame pushed (mid-sim resume) <<<")
            } catch (e: Exception) {
                Log.w("EndlessLife", "Initial frame failed", e)
            }
        } else {
            Log.d("EndlessLife", ">>> Will play starting animation (fresh or interrupted life) <<<")
        }

        lifeCycleJob?.cancel()
        lifeCycleJob = backgroundScope.launch {
            if (initialGridForReveal != null && animTypeForThisLife != null) {
                try {
                    StartingAnimation.playAnimation(manager, initialGridForReveal, BRIGHTNESS, animTypeForThisLife)
                    currentLifeAnimComplete = true
                    saveLifeState(engine.getGrid(), 0, currentLifeStartingAnimType, true)
                } catch (e: Exception) {
                    Log.w("EndlessLife", "Reveal animation failed", e)
                }
            }
            runDriver(manager, engine)
        }
    }

    override fun performOnServiceDisconnected(context: Context) {
        Log.d("EndlessLife", ">>> performOnServiceDisconnected CALLED <<<")

        glyphMatrixManager?.let { mgr ->
            try {
                mgr.setMatrixFrame(GlyphRenderer.emptyFrame())
            } catch (_: Exception) {}
        }

        if (currentEngine != null) {
            try {
                saveLifeState(
                    currentEngine!!.getGrid(),
                    -1,
                    currentLifeStartingAnimType,
                    currentLifeAnimComplete
                )
            } catch (_: Exception) {}
        }

        currentEngine = null
        lifeCycleJob?.cancel()
        lifeCycleJob = null
    }

    override fun onCreate() {
        Log.d("EndlessLife", ">>> onCreate CALLED <<<")
        super.onCreate()
    }

    override fun onDestroy() {
        Log.d("EndlessLife", ">>> onDestroy CALLED <<<")
        super.onDestroy()

        try {
            glyphMatrixManager?.setMatrixFrame(GlyphRenderer.emptyFrame())
        } catch (_: Exception) {}
    }

    override fun onGlyphLongPress() {
        Log.i("EndlessLife", ">>> onGlyphLongPress RECEIVED <<<")

        val now = System.currentTimeMillis()
        if (now - lastLongPressTimeMs < 700L) {
            return   // debounce rapid presses
        }
        lastLongPressTimeMs = now

        clearLifeState()
        resetCurrentLifeAnimationState()

        val mgr = glyphMatrixManager
        val engineSnapshot = currentEngine   // capture the current grid before we nuke things

        lifeCycleJob?.cancel()
        lifeCycleJob = null
        currentEngine = null

        if (mgr != null && engineSnapshot != null) {
            lifeCycleJob = backgroundScope.launch {
                try {
                    val grid = engineSnapshot.getGrid()
                    EndingAnimation.pauseAndFadeOut(mgr, grid, BRIGHTNESS, 300, 1200)
                } catch (_: Exception) {}

                delay(1000L)
                startFreshLife(mgr)
            }
        } else if (mgr != null) {
            startFreshLife(mgr)
        }
    }

    private fun resetCurrentLifeAnimationState() {
        currentLifeStartingAnimType = null
        currentLifeAnimComplete = false
    }

    /**
     * Generates a brand new time-seeded pattern + picks a random enabled starting animation type.
     * Used by both normal fresh starts and long-press forced new lives.
     */
    private fun generateFreshPattern(): Pair<Array<IntArray>, StartingAnimationType> {
        val settings = settingsRepository.getSettings()
        val cal = Calendar.getInstance()
        val seed = PatternGenerator.seedFromTime(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
        val grid = PatternGenerator.generate(seed, 25, 25, settings.initialDensity)
        val type = settings.enabledAnimations.randomOrNull() ?: StartingAnimationType.ROW_BY_ROW
        return grid to type
    }

    /**
     * Starts a fresh life with reveal animation and runs the driver.
     * Used after long press forced ending or early startup.
     */
    private fun startFreshLife(manager: GlyphMatrixManager) {
        val (grid, type) = generateFreshPattern()
        val engine = LifeGameEngine()
        engine.setGrid(grid)
        currentEngine = engine
        currentLifeStartingAnimType = type
        currentLifeAnimComplete = false

        lifeCycleJob?.cancel()
        lifeCycleJob = backgroundScope.launch {
            prepareAndPlayNewLife(manager, engine)
            runDriver(manager, engine)
        }
    }

    /**
     * Creates a new time-seeded pattern, plays its reveal animation, and persists state.
     */
    private suspend fun prepareAndPlayNewLife(
        manager: GlyphMatrixManager,
        engine: LifeGameEngine
    ) {
        val (grid, type) = generateFreshPattern()
        engine.setGrid(grid)
        currentLifeStartingAnimType = type
        currentLifeAnimComplete = false

        try {
            StartingAnimation.playAnimation(manager, grid, BRIGHTNESS, type)
            currentLifeAnimComplete = true
            saveLifeState(grid, 0, type, true)
        } catch (e: Exception) {
            Log.w("EndlessLife", "Reveal animation failed", e)
        }
    }

    /**
     * The main simulation loop. Runs while the service is bound.
     */
    private suspend fun runDriver(manager: GlyphMatrixManager, engine: LifeGameEngine) {
        val stability = StabilityDetector()
        var currentSpeed = settingsRepository.getSettings().simulationSpeedMs
        var stableSince: Long = 0

        stability.reset()

        while (currentCoroutineContext().isActive) {
            val currentGrid = engine.getGrid()
            val frame = GlyphRenderer.render(currentGrid, BRIGHTNESS)
            withContext(Dispatchers.Main) {
                try {
                    manager.setMatrixFrame(frame)
                } catch (e: Exception) {
                    Log.w("EndlessLife", "Frame update failed", e)
                }
            }

            if (stability.addAndCheck(currentGrid)) {
                if (stableSince == 0L) {
                    stableSince = System.currentTimeMillis()
                }
            } else {
                stableSince = 0L
            }

            val endedNaturally = engine.isExtinct() || (stableSince > 0 && System.currentTimeMillis() - stableSince >= STABLE_TIME_MS)
            if (endedNaturally) {
                clearLifeState()
                resetCurrentLifeAnimationState()

                try {
                    EndingAnimation.pauseAndFadeOut(manager, currentGrid, BRIGHTNESS, 300, 1200)
                } catch (_: Exception) {}

                delay(1000L)

                prepareAndPlayNewLife(manager, engine)
                stability.reset()
                stableSince = 0L

                val s = settingsRepository.getSettings()
                currentSpeed = s.simulationSpeedMs

                continue
            }

            delay(currentSpeed)
            engine.step()
        }
    }

    // ==================== State Persistence (SharedPreferences) ====================
    // We persist the grid so the simulation can resume across the system's bind/unbind cycles
    // for Always-on Glyph Toy use.

    /**
     * Persists the current life grid + metadata.
     * animType + animComplete let us detect "this fresh life had its starting animation interrupted"
     * and replay the *same* animation type on resume (see Option 3).
     */
    private fun saveLifeState(
        grid: Array<IntArray>,
        generation: Int,
        animType: StartingAnimationType? = null,
        animComplete: Boolean = false
    ) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val flat = StringBuilder(GlyphRenderer.FRAME_SIZE)
            for (row in grid) {
                for (cell in row) {
                    flat.append(if (cell == 1) '1' else '0')
                }
            }
            prefs.edit {
                putString(KEY_GRID, flat.toString())
                    .putInt(KEY_GENERATION, generation)
                    .putString(KEY_STARTING_ANIM_TYPE, animType?.name)
                    .putBoolean(KEY_STARTING_ANIM_COMPLETE, animComplete)
                    .putLong(KEY_LAST_SAVE, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w("EndlessLife", "Failed to save life state", e)
        }
    }

    /**
     * Rich persisted state for a life, including whether its starting animation completed.
     */
    private data class PersistedLifeState(
        val grid: Array<IntArray>,
        val generation: Int,
        val startingAnimType: StartingAnimationType?,
        val startingAnimComplete: Boolean,
        val lastSave: Long
    )

    private fun loadLifeState(): PersistedLifeState? {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val flat = prefs.getString(KEY_GRID, null) ?: return null
            val generation = prefs.getInt(KEY_GENERATION, 0)
            val animTypeName = prefs.getString(KEY_STARTING_ANIM_TYPE, null)
            val animComplete = prefs.getBoolean(KEY_STARTING_ANIM_COMPLETE, false)
            val lastSave = prefs.getLong(KEY_LAST_SAVE, 0)

            // Honor the user's "max resume age" setting (falls back to 5 min).
            val maxAgeMin = try {
                getSharedPreferences("endless_life_settings", Context.MODE_PRIVATE)
                    .getInt("max_resume_age_minutes", 5)
            } catch (_: Exception) { 5 }
            val maxAgeMs = maxAgeMin.coerceIn(1, 60) * 60_000L

            if (System.currentTimeMillis() - lastSave > maxAgeMs) {
                clearLifeState()
                return null
            }

            if (flat.length != GlyphRenderer.FRAME_SIZE) return null

            val grid = Array(GlyphRenderer.MATRIX_SIZE) { IntArray(GlyphRenderer.MATRIX_SIZE) }
            var idx = 0
            val size = GlyphRenderer.MATRIX_SIZE
            for (r in 0 until size) {
                for (c in 0 until size) {
                    grid[r][c] = if (flat[idx] == '1') 1 else 0
                    idx++
                }
            }

            val animType = runCatching { animTypeName?.let { StartingAnimationType.valueOf(it) } }.getOrNull()
            return PersistedLifeState(grid, generation, animType, animComplete, lastSave)
        } catch (e: Exception) {
            Log.w("EndlessLife", "Failed to load life state", e)
            return null
        }
    }

    private fun clearLifeState() {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
        } catch (e: Exception) {
            Log.w("EndlessLife", "Failed to clear life state", e)
        }
    }

    // ==================== End State Persistence ====================

    private companion object {
        private const val BRIGHTNESS = 2200

        // How long a pattern must remain stable before we fade it out.
        private const val STABLE_TIME_MS = 1500L

        // Persistence keys (must match what load/save expect)
        private const val PREFS_NAME = "endless_life_state"
        private const val KEY_GRID = "grid"
        private const val KEY_GENERATION = "generation"
        private const val KEY_LAST_SAVE = "last_save"
        private const val KEY_STARTING_ANIM_TYPE = "starting_anim_type"
        private const val KEY_STARTING_ANIM_COMPLETE = "starting_anim_complete"
    }
}
