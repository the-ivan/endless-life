package com.theivan.endlesslife

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundActive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun performOnServiceConnected(context: Context, manager: GlyphMatrixManager) {
        serviceJob = SupervisorJob()
        backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)

        try {
            manager.setGlyphMatrixTimeout(false)
        } catch (e: Exception) {
            Log.w("EndlessLife", "setGlyphMatrixTimeout failed", e)
        }

        val settings = settingsRepository.getSettings()
        val persisted = if (settings.resumeEnabled) loadLifeState() else null

        val engine = LifeGameEngine()
        val initialGridForReveal: Array<IntArray>?
        val animTypeForThisLife: StartingAnimationType?

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
                initialGridForReveal = null
                animTypeForThisLife = null
            }
        }
        currentEngine = engine

        if (initialGridForReveal == null) {
            try {
                manager.setMatrixFrame(GlyphRenderer.render(engine.getGrid(), BRIGHTNESS))
            } catch (e: Exception) {
                Log.w("EndlessLife", "Initial frame failed", e)
            }
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

    override fun onDestroy() {
        stopForegroundIfActive()
        releaseAodWakeLockIfHeld()
        serviceJob.cancel()
        try {
            glyphMatrixManager?.setMatrixFrame(GlyphRenderer.emptyFrame())
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onGlyphLongPress() {
        val now = System.currentTimeMillis()
        if (now - lastLongPressTimeMs < 700L) return
        lastLongPressTimeMs = now

        val mgr = glyphMatrixManager ?: return

        clearLifeState()
        resetCurrentLifeAnimationState()

        val engineSnapshot = currentEngine
        lifeCycleJob?.cancel()
        lifeCycleJob = null
        currentEngine = null

        lifeCycleJob = backgroundScope.launch {
            if (engineSnapshot != null) {
                try {
                    EndingAnimation.pauseAndFadeOut(mgr, engineSnapshot.getGrid(), BRIGHTNESS, 300, 1200)
                } catch (_: Exception) {}
                delay(1000L)
            }
            startFreshLife(mgr)
        }
    }

    override fun onAODEvent() {
        ensureAodForeground()
        val mgr = glyphMatrixManager ?: return
        val engine = currentEngine ?: return
        if (lifeCycleJob?.isActive != true) return
        try {
            mgr.setGlyphMatrixTimeout(false)
            mgr.setMatrixFrame(GlyphRenderer.render(engine.getGrid(), BRIGHTNESS))
        } catch (e: Exception) {
            Log.w("EndlessLife", "AOD frame failed", e)
        }
    }

    private fun resetCurrentLifeAnimationState() {
        currentLifeStartingAnimType = null
        currentLifeAnimComplete = false
    }

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

    private fun startFreshLife(manager: GlyphMatrixManager) {
        val engine = LifeGameEngine()
        currentEngine = engine

        lifeCycleJob?.cancel()
        lifeCycleJob = backgroundScope.launch {
            prepareAndPlayNewLife(manager, engine)
            runDriver(manager, engine)
        }
    }

    private suspend fun prepareAndPlayNewLife(manager: GlyphMatrixManager, engine: LifeGameEngine) {
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

    private suspend fun runDriver(manager: GlyphMatrixManager, engine: LifeGameEngine) {
        val stability = StabilityDetector()
        var currentSpeed = settingsRepository.getSettings().simulationSpeedMs
        var stableSince: Long = 0

        stability.reset()

        while (currentCoroutineContext().isActive) {
            val s = settingsRepository.getSettings()
            currentSpeed = s.simulationSpeedMs

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
                if (stableSince == 0L) stableSince = System.currentTimeMillis()
            } else {
                stableSince = 0L
            }

            val endedNaturally = engine.isExtinct() ||
                (stableSince > 0 && System.currentTimeMillis() - stableSince >= STABLE_TIME_MS)
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
                currentSpeed = settingsRepository.getSettings().simulationSpeedMs
                continue
            }

            delay(currentSpeed)
            engine.step()
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
            val notif = buildAodNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isForegroundActive = true
        } catch (e: Exception) {
            Log.w("EndlessLife", "startFg failed", e)
        }
    }

    private fun stopForegroundIfActive() {
        if (!isForegroundActive) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        isForegroundActive = false
    }

    private fun acquireAodWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessLife:AOD").apply {
                setReferenceCounted(false)
                acquire()
            }
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
            }
        } catch (e: Exception) {
            Log.w("EndlessLife", "Failed to save life state", e)
        }
    }

    private data class PersistedLifeState(
        val grid: Array<IntArray>,
        val generation: Int,
        val startingAnimType: StartingAnimationType?,
        val startingAnimComplete: Boolean
    )

    private fun loadLifeState(): PersistedLifeState? {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val flat = prefs.getString(KEY_GRID, null) ?: return null
            val generation = prefs.getInt(KEY_GENERATION, 0)
            val animTypeName = prefs.getString(KEY_STARTING_ANIM_TYPE, null)
            val animComplete = prefs.getBoolean(KEY_STARTING_ANIM_COMPLETE, false)

            if (flat.length != GlyphRenderer.FRAME_SIZE) return null

            val grid = Array(GlyphRenderer.MATRIX_SIZE) { IntArray(GlyphRenderer.MATRIX_SIZE) }
            var idx = 0
            for (r in 0 until GlyphRenderer.MATRIX_SIZE) {
                for (c in 0 until GlyphRenderer.MATRIX_SIZE) {
                    grid[r][c] = if (flat[idx] == '1') 1 else 0
                    idx++
                }
            }

            val animType = runCatching {
                animTypeName?.let { StartingAnimationType.valueOf(it) }
            }.getOrNull()
            return PersistedLifeState(grid, generation, animType, animComplete)
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

    private companion object {
        private const val BRIGHTNESS = 2200
        private const val STABLE_TIME_MS = 1500L
        private const val PREFS_NAME = "endless_life_state"
        private const val KEY_GRID = "grid"
        private const val KEY_GENERATION = "generation"
        private const val KEY_STARTING_ANIM_TYPE = "starting_anim_type"
        private const val KEY_STARTING_ANIM_COMPLETE = "starting_anim_complete"
        private const val NOTIF_ID = 4242
        private const val NOTIF_CHANNEL_ID = "endless_life_aod_ambient"
    }
}