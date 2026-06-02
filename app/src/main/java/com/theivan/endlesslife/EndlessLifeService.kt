package com.theivan.endlesslife

import android.content.Context
import android.content.Intent
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

    // Pending starting anim to play on next driver launch (for resume after unbind).
    private var pendingRevealGrid: Array<IntArray>? = null
    private var pendingRevealType: StartingAnimationType? = null

    override fun onCreate() {
        Log.d("EndlessLife", "onCreate")
        super.onCreate()

        // Long-lived scope for driver across bind/unbind cycles (e.g. AOD).
        // Cancel only onDestroy.
        serviceJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)
    }

    override fun performOnServiceConnected(context: Context, manager: GlyphMatrixManager) {
        Log.d("EndlessLife", "performOnServiceConnected (matrixLen=$detectedMatrixLength)")

        val matrixLen = detectedMatrixLength
        if (matrixLen != 25) {
            Log.w("EndlessLife", "This toy requires a 25×25 Glyph Matrix (Nothing Phone 3). " +
                    "Current device reports length=$matrixLen. Not starting.")
            return
        }

        val needsReveal = initializeLifeIfNeeded(manager)

        if (lifeCycleJob?.isActive != true) {
            val revealG = pendingRevealGrid
            val revealT = pendingRevealType
            pendingRevealGrid = null
            pendingRevealType = null

            lifeCycleJob = backgroundScope.launch {
                if (needsReveal && revealG != null && revealT != null) {
                    try {
                        StartingAnimation.playAnimation(manager, revealG, BRIGHTNESS, revealT)
                        currentLifeAnimComplete = true
                        saveLifeState(currentEngine!!.getGrid(), 0, currentLifeStartingAnimType, true)
                    } catch (e: Exception) {
                        Log.w("EndlessLife", "Reveal animation failed", e)
                    }
                }
                runDriver(manager, currentEngine!!)
            }
        } else if (currentEngine != null) {
            // Warm attach: push current frame (driver already running).
            try {
                manager.setMatrixFrame(GlyphRenderer.render(currentEngine!!.getGrid(), BRIGHTNESS))
            } catch (e: Exception) {
                Log.w("EndlessLife", "Initial frame failed", e)
            }
        }
    }

    /**
     * If no engine, init from persisted (or fresh). Returns true if reveal needed.
     * Pushes immediate frame for mid-sim resumes. Caller launches driver + reveal if needed.
     */
    private fun initializeLifeIfNeeded(manager: GlyphMatrixManager): Boolean {
        if (currentEngine != null) return false

        resetCurrentLifeAnimationState()

        val settings = settingsRepository.getSettings()
        val persisted = if (settings.resumeEnabled) loadLifeState() else null

        val engine = LifeGameEngine()
        var initialGridForReveal: Array<IntArray>? = null
        var animTypeForThisLife: StartingAnimationType? = null

        when {
            persisted == null -> {
                // Fresh life: pick anim type, mark reveal incomplete.
                val (grid, type) = generateFreshPattern()
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false
                engine.setGrid(grid)
                initialGridForReveal = grid
                animTypeForThisLife = type
            }

            !persisted.startingAnimComplete -> {
                // Interrupted starting anim (unbind during reveal): replay same type.
                val type = persisted.startingAnimType
                    ?: (settings.enabledAnimations.randomOrNull() ?: StartingAnimationType.ROW_BY_ROW)
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false

                engine.setGrid(persisted.grid)
                initialGridForReveal = persisted.grid
                animTypeForThisLife = type

                Log.d("EndlessLife", "Resuming interrupted starting animation (type=$type)")
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

        // Mid-sim resume: push immediately. (Reveal handled by caller for fresh/interrupted.)
        if (initialGridForReveal == null) {
            val firstFrame = GlyphRenderer.render(engine.getGrid(), BRIGHTNESS)
            try {
                manager.setMatrixFrame(firstFrame)
            } catch (e: Exception) {
                Log.w("EndlessLife", "Initial frame failed", e)
            }
        }

        pendingRevealGrid = initialGridForReveal
        pendingRevealType = animTypeForThisLife
        return initialGridForReveal != null && animTypeForThisLife != null
    }

    override fun performOnServiceDisconnected(context: Context) {
        Log.d("EndlessLife", "performOnServiceDisconnected")

        // Save state for resume across bind/unbind.
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

        // Intentionally do NOT:
        // - push black (base onUnbind does it)
        // - cancel lifeCycleJob
        // - null engine/pending state
        // Keeps sim running across unbind gaps (e.g. AOD). Full cleanup in onDestroy.
    }

    override fun onDestroy() {
        Log.d("EndlessLife", "onDestroy")
        super.onDestroy()

        serviceJob.cancel()
        try {
            glyphMatrixManager?.setMatrixFrame(GlyphRenderer.emptyFrame())
        } catch (_: Exception) {}
    }

    override fun onGlyphLongPress() {
        Log.i("EndlessLife", "onGlyphLongPress")

        val now = System.currentTimeMillis()
        if (now - lastLongPressTimeMs < 700L) {
            return  // debounce rapid presses
        }
        lastLongPressTimeMs = now

        clearLifeState()
        resetCurrentLifeAnimationState()
        pendingRevealGrid = null
        pendingRevealType = null

        val mgr = glyphMatrixManager
        val engineSnapshot = currentEngine  // snapshot grid for the ending fade before clearing state

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

    override fun onAODEvent() {
        Log.d("EndlessLife", "onAODEvent")

        val mgr = glyphMatrixManager ?: return

        val needsReveal = initializeLifeIfNeeded(mgr)

        if (lifeCycleJob?.isActive != true) {
            val revealG = pendingRevealGrid
            val revealT = pendingRevealType
            pendingRevealGrid = null
            pendingRevealType = null

            lifeCycleJob = backgroundScope.launch {
                if (needsReveal && revealG != null && revealT != null) {
                    try {
                        StartingAnimation.playAnimation(mgr, revealG, BRIGHTNESS, revealT)
                        currentLifeAnimComplete = true
                        saveLifeState(currentEngine!!.getGrid(), 0, currentLifeStartingAnimType, true)
                    } catch (e: Exception) {
                        Log.w("EndlessLife", "Reveal animation failed", e)
                    }
                }
                runDriver(mgr, currentEngine!!)
            }
        } else if (currentEngine != null) {
            // Warm attach on AOD heartbeat: push current frame.
            try {
                mgr.setMatrixFrame(GlyphRenderer.render(currentEngine!!.getGrid(), BRIGHTNESS))
            } catch (_: Exception) {}
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
    private fun startFreshLife(manager: GlyphMatrixManager?) {
        val (grid, type) = generateFreshPattern()
        val engine = LifeGameEngine()
        engine.setGrid(grid)
        currentEngine = engine
        currentLifeStartingAnimType = type
        currentLifeAnimComplete = false

        pendingRevealGrid = null
        pendingRevealType = null

        lifeCycleJob?.cancel()
        lifeCycleJob = backgroundScope.launch {
            prepareAndPlayNewLife(manager, engine)
            runDriver(manager, engine)
        }
    }

    /**
     * New pattern + (if manager) play reveal + persist.
     * Without manager, just set grid so driver keeps time for next attach.
     */
    private suspend fun prepareAndPlayNewLife(
        manager: GlyphMatrixManager?,
        engine: LifeGameEngine
    ) {
        val (grid, type) = generateFreshPattern()
        engine.setGrid(grid)
        currentLifeStartingAnimType = type
        currentLifeAnimComplete = false

        val mgr = glyphMatrixManager ?: manager
        if (mgr != null) {
            try {
                StartingAnimation.playAnimation(mgr, grid, BRIGHTNESS, type)
                currentLifeAnimComplete = true
                saveLifeState(grid, 0, type, true)
            } catch (e: Exception) {
                Log.w("EndlessLife", "Reveal animation failed", e)
            }
        } else {
            // No manager: mark reveal incomplete for replay on next attach.
            currentLifeAnimComplete = false
            saveLifeState(grid, 0, type, false)
        }
    }

    /**
     * The main simulation loop. Keeps the Conway engine stepping at the configured rate.
     * Renders only when manager available (falls back to captured one).
     * Sim continues across unbind gaps (AOD uses short binds).
     */
    private suspend fun runDriver(manager: GlyphMatrixManager?, engine: LifeGameEngine) {
        val stability = StabilityDetector()
        var currentSpeed = settingsRepository.getSettings().simulationSpeedMs
        var stableSince: Long = 0

        stability.reset()

        while (currentCoroutineContext().isActive) {
            val currentGrid = engine.getGrid()
            val frame = GlyphRenderer.render(currentGrid, BRIGHTNESS)

            val mgr = glyphMatrixManager ?: manager
            if (mgr != null) {
                withContext(Dispatchers.Main) {
                    try {
                        mgr.setMatrixFrame(frame)
                    } catch (e: Exception) {
                        Log.w("EndlessLife", "Frame update failed", e)
                    }
                }
            }

            // Step/delay even with no manager; render when available (survives AOD unbind gaps).

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

                val mgr2 = glyphMatrixManager ?: manager
                if (mgr2 != null) {
                    try {
                        EndingAnimation.pauseAndFadeOut(mgr2, currentGrid, BRIGHTNESS, 300, 1200)
                    } catch (_: Exception) {}
                }

                delay(1000L)

                prepareAndPlayNewLife(mgr2, engine)
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

    // ==================== State Persistence ====================

    /**
     * Persists the current life grid + metadata (starting anim type + completion flag).
     * This allows resuming an interrupted reveal animation with the exact same type.
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

    /** Persisted snapshot of a life (grid + whether its starting reveal completed). */
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

            // Enforce max resume age (from settings, default 5min).
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
