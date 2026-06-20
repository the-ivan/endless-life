package com.theivan.endlesslife

enum class StartingAnimationType {
    ROW_BY_ROW,
    BOTTOM_UP,
    COLUMN_BY_COLUMN,
    RIGHT_TO_LEFT,
    CENTER_OUTWARD,
    SPIRAL_CENTER_OUT,
    SPIRAL_EDGE_TO_CENTER,
    FADE_IN;

    val displayName: String
        get() = when (this) {
            ROW_BY_ROW -> "Row by Row"
            BOTTOM_UP -> "Bottom Up"
            COLUMN_BY_COLUMN -> "Column by Column"
            RIGHT_TO_LEFT -> "Right to Left"
            CENTER_OUTWARD -> "Center Outward"
            SPIRAL_CENTER_OUT -> "Spiral (Center Out)"
            SPIRAL_EDGE_TO_CENTER -> "Spiral (Edge In)"
            FADE_IN -> "Fade In"
        }
}

data class EndlessLifeSettings(
    val enabledAnimations: Set<StartingAnimationType> = StartingAnimationType.entries.toSet(),
    val simulationSpeedMs: Long = 220L,
    val initialDensity: Double = 0.33,
    val resumeEnabled: Boolean = true
)
