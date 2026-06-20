package com.theivan.endlesslife

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("endless_life_settings", Context.MODE_PRIVATE)

    fun getSettings(): EndlessLifeSettings {
        val enabledNames = prefs.getStringSet("enabled_animations", null)
            ?: StartingAnimationType.entries.map { it.name }.toSet()

        val enabled = enabledNames.mapNotNull { name ->
            runCatching { StartingAnimationType.valueOf(name) }.getOrNull()
        }.toSet().ifEmpty { StartingAnimationType.entries.toSet() }

        return EndlessLifeSettings(
            enabledAnimations = enabled,
            simulationSpeedMs = prefs.getLong("simulation_speed_ms", 220L),
            initialDensity = prefs.getFloat("initial_density", 0.33f).toDouble(),
            resumeEnabled = prefs.getBoolean("resume_enabled", true)
        )
    }

    fun saveSettings(settings: EndlessLifeSettings) {
        prefs.edit(commit = true) {
            putStringSet("enabled_animations", settings.enabledAnimations.map { it.name }.toSet())
                .putLong("simulation_speed_ms", settings.simulationSpeedMs)
                .putFloat("initial_density", settings.initialDensity.toFloat())
                .putBoolean("resume_enabled", settings.resumeEnabled)
        }
    }
}
