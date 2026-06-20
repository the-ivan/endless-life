package com.theivan.endlesslife

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EndlessLifeSettingsScreen(
                        repository = settingsRepository,
                        onOpenAppSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                // System app settings unavailable
                            }
                        },
                        onOpenGlyphSettings = {
                            val intent = Intent().apply {
                                setComponent(
                                    android.content.ComponentName(
                                        "com.nothing.thirdparty",
                                        "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"
                                    )
                                )
                            }
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                // Glyph Toys Manager not available
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        onClick = { onExpandedChange(!expanded) }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndlessLifeSettingsScreen(
    repository: SettingsRepository,
    onOpenAppSettings: () -> Unit,
    onOpenGlyphSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(repository.getSettings()) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Collapsible section states (survive config changes)
    var animationsExpanded by rememberSaveable { mutableStateOf(false) }
    var simulationExpanded by rememberSaveable { mutableStateOf(false) }
    var resumeExpanded by rememberSaveable { mutableStateOf(false) }

    fun updateSettings(newSettings: EndlessLifeSettings) {
        settings = newSettings
        hasUnsavedChanges = true
    }

    fun resetToDefaults() {
        val defaults = EndlessLifeSettings()
        settings = defaults
        repository.saveSettings(defaults)
        hasUnsavedChanges = false
        scope.launch {
            snackbarHostState.showSnackbar("Settings reset to defaults")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(paddingValues)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "Endless Life",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Settings for the Nothing Phone (3) Glyph Matrix",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Starting Animations
        CollapsibleSection(
            title = "Starting Animations",
            expanded = animationsExpanded,
            onExpandedChange = { animationsExpanded = it }
        ) {

        StartingAnimationType.entries.forEach { type ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Checkbox(
                    checked = settings.enabledAnimations.contains(type),
                    onCheckedChange = { checked ->
                        val newSet = if (checked) {
                            settings.enabledAnimations + type
                        } else {
                            settings.enabledAnimations - type
                        }
                        updateSettings(settings.copy(enabledAnimations = newSet.ifEmpty { setOf(type) }))
                    }
                )
                Text(type.displayName)
            }
        }
        }

        // Simulation
        CollapsibleSection(
            title = "Simulation",
            expanded = simulationExpanded,
            onExpandedChange = { simulationExpanded = it }
        ) {

        Text("Simulation Speed", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Slow", style = MaterialTheme.typography.labelSmall)
            Text("Fast", style = MaterialTheme.typography.labelSmall)
        }

        val speedOptions = listOf(900L, 780L, 660L, 550L, 450L, 360L, 280L, 220L, 160L, 105L, 55L)
        val lastIndex = speedOptions.lastIndex

        val currentIndex = speedOptions.withIndex()
            .minByOrNull { kotlin.math.abs(it.value - settings.simulationSpeedMs) }?.index ?: 7
        val currentProgress = currentIndex / lastIndex.toFloat()

        Slider(
            value = currentProgress,
            onValueChange = { progress ->
                val idx = (progress * lastIndex).roundToInt().coerceIn(0, lastIndex)
                updateSettings(settings.copy(simulationSpeedMs = speedOptions[idx]))
            },
            steps = lastIndex - 1
        )

        Spacer(Modifier.height(16.dp))

        Text("Initial Density: ${(settings.initialDensity * 100).toInt()}%")
        Slider(
            value = settings.initialDensity.toFloat(),
            onValueChange = { updateSettings(settings.copy(initialDensity = it.toDouble())) },
            valueRange = 0.15f..0.5f
        )

        }

        // Resume
        CollapsibleSection(
            title = "Resume Behavior",
            expanded = resumeExpanded,
            onExpandedChange = { resumeExpanded = it }
        ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.resumeEnabled,
                onCheckedChange = { updateSettings(settings.copy(resumeEnabled = it)) }
            )
            Spacer(Modifier.width(8.dp))
            Text("Resume interrupted simulations")
        }

        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                repository.saveSettings(settings)
                hasUnsavedChanges = false
                scope.launch {
                    snackbarHostState.showSnackbar("Settings saved")
                }
            },
            enabled = hasUnsavedChanges,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasUnsavedChanges) "Save Changes" else "Saved")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "For uninterrupted Always-on play on battery, open app settings and set Battery background usage to Unrestricted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open App Settings")
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showResetDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset to Defaults"
                )
            }

            Button(
                onClick = onOpenGlyphSettings,
                modifier = Modifier.weight(1f)
            ) {
                Text("Open Glyph Toys Manager")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Changes take effect on the next life or after re-selecting the toy as Always-on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset to defaults?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            resetToDefaults()
                            showResetDialog = false
                        }
                    ) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
