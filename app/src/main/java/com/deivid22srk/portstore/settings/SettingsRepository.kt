package com.deivid22srk.portstore.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "portstore_settings")

data class AppSettings(
    val theme: Int = THEME_DARK,
    val segments: Int = 6,
    val maxConcurrent: Int = 3,
    val speedLimitMbps: Int = 0,
    val wifiOnly: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val downloadTreeUri: String = "",
) {
    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
    }
}

class SettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val keys = object {
        val theme = intPreferencesKey("theme")
        val segments = intPreferencesKey("segments")
        val maxConcurrent = intPreferencesKey("max_concurrent")
        val speedLimitMbps = intPreferencesKey("speed_limit_mbps")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val disclaimer = booleanPreferencesKey("disclaimer_accepted")
        val treeUri = stringPreferencesKey("download_tree_uri")
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            val prefs = context.dataStore.data.first()
            _settings.value = AppSettings(
                theme = prefs[keys.theme] ?: AppSettings.THEME_DARK,
                segments = (prefs[keys.segments] ?: 6).coerceIn(1, 16),
                maxConcurrent = (prefs[keys.maxConcurrent] ?: 3).coerceIn(1, 6),
                speedLimitMbps = prefs[keys.speedLimitMbps] ?: 0,
                wifiOnly = prefs[keys.wifiOnly] ?: false,
                disclaimerAccepted = prefs[keys.disclaimer] ?: false,
                downloadTreeUri = prefs[keys.treeUri] ?: "",
            )
            context.dataStore.data.collect { prefs ->
                _settings.value = AppSettings(
                    theme = prefs[keys.theme] ?: AppSettings.THEME_DARK,
                    segments = (prefs[keys.segments] ?: 6).coerceIn(1, 16),
                    maxConcurrent = (prefs[keys.maxConcurrent] ?: 3).coerceIn(1, 6),
                    speedLimitMbps = prefs[keys.speedLimitMbps] ?: 0,
                    wifiOnly = prefs[keys.wifiOnly] ?: false,
                    disclaimerAccepted = prefs[keys.disclaimer] ?: false,
                    downloadTreeUri = prefs[keys.treeUri] ?: "",
                )
            }
        }
    }

    suspend fun setTheme(value: Int) = save { it[keys.theme] = value }
    suspend fun setSegments(value: Int) = save { it[keys.segments] = value.coerceIn(1, 16) }
    suspend fun setMaxConcurrent(value: Int) = save { it[keys.maxConcurrent] = value.coerceIn(1, 6) }
    suspend fun setSpeedLimitMbps(value: Int) = save { it[keys.speedLimitMbps] = value.coerceIn(0, 200) }
    suspend fun setWifiOnly(value: Boolean) = save { it[keys.wifiOnly] = value }
    suspend fun setDisclaimerAccepted(value: Boolean) = save { it[keys.disclaimer] = value }
    suspend fun setDownloadTreeUri(value: String) = save { it[keys.treeUri] = value }

    private suspend fun save(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
