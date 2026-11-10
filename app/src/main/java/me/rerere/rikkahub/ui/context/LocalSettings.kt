package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}

val LocalPowerSavingMode = staticCompositionLocalOf { false }
