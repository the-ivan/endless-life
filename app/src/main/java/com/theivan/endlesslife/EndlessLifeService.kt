package com.theivan.endlesslife

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
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

/**
 * Endless Life — Conway's Game of Life for the Nothing Phone (3) Glyph Matrix.
 *
 * AOD support: when set as Always-on Glyph Toy, foreground service keeps the
 * simulation running persistently at normal speed (no timeout, no pause on ambient).
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

    // FG + wake for AOD persistent running
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundActive: Boolean = false
    private var lastSaveTime: Long? = null

    override fun onCreate() {
        Log.d("EndlessLife", "onCreate")
        super.onCreate()
        createNotificationChannel()

        ensureAodForeground()
        requestIgnoreBatteryOptimizationsIfNeeded()

        serviceJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)

        ensureDriverStartedEarly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("EndlessLife", "onStartCommand")
        ensureAodForeground()
        return Service.START_STICKY
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
            ensureAodForeground()
        } else if (currentEngine != null) {
            // Warm attach: push current frame (driver already running).
            ensureAodForeground()
            try {
                manager.setMatrixFrame(GlyphRenderer.render(currentEngine!!.getGrid(), BRIGHTNESS))
            } catch (e: Exception) {
                Log.w("EndlessLife", "Initial frame failed", e)
            }
        }

        if (pendingRevealGrid != null && pendingRevealType != null) {
            backgroundScope.launch {
                try {
                    StartingAnimation.playAnimation(manager, pendingRevealGrid!!, BRIGHTNESS, pendingRevealType!!)
                    currentLifeAnimComplete = true
                    saveLifeState(currentEngine!!.getGrid(), 0, currentLifeStartingAnimType, true)
                } catch (e: Exception) {
                    Log.w("EndlessLife", "Reveal animation failed", e)
                }
                pendingRevealGrid = null
                pendingRevealType = null
            }
        }
    }

    /**
     * If no engine, init from persisted (or fresh). Returns true if reveal needed.
     * Pushes immediate frame for mid-sim resumes. Caller launches driver + reveal if needed.
     */
    private fun initializeLifeIfNeeded(manager: GlyphMatrixManager?): Boolean {
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

        // Mid-sim resume: push immediately if we have a manager.
        if (initialGridForReveal == null && manager != null) {
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

        ensureAodForeground()
    }

    override fun onDestroy() {
        Log.d("EndlessLife", "onDestroy")
        super.onDestroy()

        stopForegroundIfActive()
        releaseAodWakeLockIfHeld()
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
        val engineSnapshot = currentEngine

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

        ensureDriverAndForeground()
        ensureAodForeground()
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

        ensureAodForeground()
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.aod_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.aod_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildAodNotification(): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.aod_notification_title))
                .setContentText(getString(R.string.aod_notification_text))
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
    }

    private fun startForegroundIfNeeded() {
        if (isForegroundActive) return
        try {
            val startIntent = Intent(this, EndlessLifeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            val notif = buildAodNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isForegroundActive = true
            Log.d("EndlessLife", "startForeground")
        } catch (e: Exception) {
            Log.w("EndlessLife", "startFg failed", e)
        }
    }

    private fun stopForegroundIfActive() {
        if (!isForegroundActive) return
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        isForegroundActive = false
        Log.d("EndlessLife", "stopForeground")
    }

    private fun acquireAodWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EndlessLife:AOD"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("EndlessLife", "wake acquired")
        } catch (e: Exception) {
            Log.w("EndlessLife", "wake failed", e)
        }
    }

    private fun releaseAodWakeLockIfHeld() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun ensureAodForeground() {
        acquireAodWakeLockIfNeeded()
        startForegroundIfNeeded()
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w("EndlessLife", "Not ignoring battery optimizations - this will cause pauses on battery. Requesting exemption...")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w("EndlessLife", "Battery opt check/request failed", e)
        }
    }

    private fun ensureDriverAndForeground() {
        if (lifeCycleJob?.isActive == true && currentEngine != null) {
            ensureAodForeground()
            glyphMatrixManager?.let { mgr ->
                try {
                    mgr.setMatrixFrame(GlyphRenderer.render(currentEngine!!.getGrid(), BRIGHTNESS))
                } catch (_: Exception) {}
            }
            return
        }
        Log.d("EndlessLife", "ensureDriver: waiting connect")
    }

    /**
     * Start the sim driver early (no manager needed for stepping) so it keeps
     * progressing in bg even if the system has not bound yet or after process
     * restarts. When a manager becomes available we will push/play as needed.
     */
    private fun ensureDriverStartedEarly() {
        if (lifeCycleJob?.isActive == true && currentEngine != null) return

        val settings = settingsRepository.getSettings()
        val persisted = if (settings.resumeEnabled) loadLifeState() else null

        val engine = LifeGameEngine()
        var initialGridForReveal: Array<IntArray>? = null
        var animTypeForThisLife: StartingAnimationType? = null

        when {
            persisted == null -> {
                val (grid, type) = generateFreshPattern()
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false
                engine.setGrid(grid)
                initialGridForReveal = grid
                animTypeForThisLife = type
            }
            !persisted.startingAnimComplete -> {
                val type = persisted.startingAnimType
                    ?: (settings.enabledAnimations.randomOrNull() ?: StartingAnimationType.ROW_BY_ROW)
                currentLifeStartingAnimType = type
                currentLifeAnimComplete = false
                engine.setGrid(persisted.grid)
                initialGridForReveal = persisted.grid
                animTypeForThisLife = type
            }
            else -> {
                currentLifeStartingAnimType = null
                currentLifeAnimComplete = true
                engine.setGrid(persisted.grid)
            }
        }
        currentEngine = engine
        pendingRevealGrid = initialGridForReveal
        pendingRevealType = animTypeForThisLife

        lifeCycleJob = backgroundScope.launch {
            // If we have a pending reveal we can't play it yet (no manager).
            // Leave it pending; the first connect with a manager will play it.
            // For pure bg progression we just use the grid as-is.
            runDriver(null, engine)
        }
        ensureAodForeground()
    }

    // ==================== FG helpers (AOD) ====================

    /**
     * The main simulation loop. Keeps the Conway engine stepping at the configured rate.
     * Renders only when manager available.
     * Sim continues across unbind gaps for AOD (FG keeps process alive).
     */
    private suspend fun runDriver(manager: GlyphMatrixManager?, engine: LifeGameEngine) {
        val stability = StabilityDetector()
        var currentSpeed = settingsRepository.getSettings().simulationSpeedMs
        var stableSince: Long = 0

        stability.reset()

        while (currentCoroutineContext().isActive) {
            // Always normal speed (no separate ambient speed, no timeout)
            val s = settingsRepository.getSettings()
            currentSpeed = s.simulationSpeedMs

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

                currentSpeed = settingsRepository.getSettings().simulationSpeedMs

                continue
            }

            delay(currentSpeed)
            engine.step()

            // Periodic save so we don't lose too much progress if process is killed by system
            // (saves also happen on unbind)
            if (System.currentTimeMillis() - (lastSaveTime ?: 0) > 30000) {
                if (currentEngine != null) {
                    saveLifeState(currentEngine!!.getGrid(), -1, currentLifeStartingAnimType, currentLifeAnimComplete)
                }
                lastSaveTime = System.currentTimeMillis()
            }
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

    private fun loadLifeState(ignoreAge: Boolean = false): PersistedLifeState? {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val flat = prefs.getString(KEY_GRID, null) ?: return null
            val generation = prefs.getInt(KEY_GENERATION, 0)
            val animTypeName = prefs.getString(KEY_STARTING_ANIM_TYPE, null)
            val animComplete = prefs.getBoolean(KEY_STARTING_ANIM_COMPLETE, false)
            val lastSave = prefs.getLong(KEY_LAST_SAVE, 0)

            if (!ignoreAge) {
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

        // FG notif for AOD persistent mode
        private const val NOTIF_ID = 4242
        private const val NOTIF_CHANNEL_ID = "endless_life_aod_ambient"
    }
}
