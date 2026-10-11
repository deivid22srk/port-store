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
    val setupCompleted: Boolean = false,
    val setupStep: Int = 0,
    val continueWithoutNotifications: Boolean = false,
    val githubToken: String = "",
    val allowPrerelease: Boolean = false,
    /** Modo "Game Launcher": interface alternativa estilo console (home do Switch). */
    val gameLauncherEnabled: Boolean = false,
    /** Feedback sutil do Launcher: vibração leve ao mover o foco. */
    val launcherHaptics: Boolean = true,
    /** Feedback sutil do Launcher: beep curto de navegação. */
    val launcherSounds: Boolean = false,
    /** Mapa gameId -> timestamp do último play, usado para "jogados recentemente". */
    val launcherPlayed: String = "",
    /**
     * Modo "Instalação Virtual": novos jogos podem ser instalados dentro do
     * Port Store (ambiente virtual), sem aparecer no Android.
     */
    val virtualInstallEnabled: Boolean = false,
    /** True após o usuário confirmar o aviso inicial do modo virtual. */
    val virtualNoticeAcknowledged: Boolean = false,
    /** True após o primeiro load do DataStore (decisão setup × app principal). */
    val bootReady: Boolean = false,
    /**
     * Gesto do menu rápido dentro de jogos virtuais: duas deslizadas rápidas
     * da esquerda para a direita abrem o menu lateral. Ligado por padrão;
     * jogos com gestos horizontais podem exigir desligar.
     */
    val quickMenuGestureEnabled: Boolean = true,
    /** Som curto + vibração leve ao abrir um jogo pelo Game Launcher. */
    val launchFeedbackEnabled: Boolean = false,
    /**
     * Modo Desenvolvedor: expõe "Exportar/Importar dados do jogo" no menu de
     * toque longo. Desligado por padrão — recursos avançados de backup.
     */
    val developerMode: Boolean = false,
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
        val setupCompleted = booleanPreferencesKey("setup_completed")
        val setupStep = intPreferencesKey("setup_step")
        val continueNoNotif = booleanPreferencesKey("continue_without_notifications")
        val githubToken = stringPreferencesKey("github_token")
        val allowPrerelease = booleanPreferencesKey("allow_prerelease")
        val gameLauncher = booleanPreferencesKey("game_launcher_enabled")
        val launcherHaptics = booleanPreferencesKey("launcher_haptics")
        val launcherSounds = booleanPreferencesKey("launcher_sounds")
        val launcherPlayed = stringPreferencesKey("launcher_played")
        val virtualInstall = booleanPreferencesKey("virtual_install_enabled")
        val virtualNotice = booleanPreferencesKey("virtual_notice_acknowledged")
        val quickMenuGesture = booleanPreferencesKey("quick_menu_gesture_enabled")
        val launchFeedback = booleanPreferencesKey("launch_feedback_enabled")
        val developerMode = booleanPreferencesKey("developer_mode_enabled")
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Sessão de setup ativa (primeira execução ou reaberta de "Você"). */
    private val _setupSession = MutableStateFlow(false)
    val setupSession: StateFlow<Boolean> = _setupSession.asStateFlow()

    init {
        scope.launch {
            val prefs = context.dataStore.data.first()
            _settings.value = map(prefs).copy(bootReady = true)
            context.dataStore.data.collect { prefs ->
                _settings.value = map(prefs).copy(bootReady = _settings.value.bootReady)
            }
        }
    }

    private fun map(prefs: androidx.datastore.preferences.core.Preferences): AppSettings = AppSettings(
        theme = prefs[keys.theme] ?: AppSettings.THEME_DARK,
        segments = (prefs[keys.segments] ?: 6).coerceIn(1, 16),
        maxConcurrent = (prefs[keys.maxConcurrent] ?: 3).coerceIn(1, 6),
        speedLimitMbps = prefs[keys.speedLimitMbps] ?: 0,
        wifiOnly = prefs[keys.wifiOnly] ?: false,
        disclaimerAccepted = prefs[keys.disclaimer] ?: false,
        downloadTreeUri = prefs[keys.treeUri] ?: "",
        setupCompleted = prefs[keys.setupCompleted] ?: false,
        setupStep = prefs[keys.setupStep] ?: 0,
        continueWithoutNotifications = prefs[keys.continueNoNotif] ?: false,
        githubToken = prefs[keys.githubToken] ?: "",
        allowPrerelease = prefs[keys.allowPrerelease] ?: false,
        gameLauncherEnabled = prefs[keys.gameLauncher] ?: false,
        launcherHaptics = prefs[keys.launcherHaptics] ?: true,
        launcherSounds = prefs[keys.launcherSounds] ?: false,
        launcherPlayed = prefs[keys.launcherPlayed] ?: "",
        virtualInstallEnabled = prefs[keys.virtualInstall] ?: false,
        virtualNoticeAcknowledged = prefs[keys.virtualNotice] ?: false,
        quickMenuGestureEnabled = prefs[keys.quickMenuGesture] ?: true,
        launchFeedbackEnabled = prefs[keys.launchFeedback] ?: false,
        developerMode = prefs[keys.developerMode] ?: false,
    )

    suspend fun setTheme(value: Int) = save { it[keys.theme] = value }
    suspend fun setSegments(value: Int) = save { it[keys.segments] = value.coerceIn(1, 16) }
    suspend fun setMaxConcurrent(value: Int) = save { it[keys.maxConcurrent] = value.coerceIn(1, 6) }
    suspend fun setSpeedLimitMbps(value: Int) = save { it[keys.speedLimitMbps] = value.coerceIn(0, 200) }
    suspend fun setWifiOnly(value: Boolean) = save { it[keys.wifiOnly] = value }
    suspend fun setDisclaimerAccepted(value: Boolean) = save { it[keys.disclaimer] = value }
    suspend fun setDownloadTreeUri(value: String) = save { it[keys.treeUri] = value }
    suspend fun setSetupCompleted(value: Boolean) = save { it[keys.setupCompleted] = value }
    suspend fun setSetupStep(value: Int) = save { it[keys.setupStep] = value.coerceIn(0, 1) }
    suspend fun setContinueWithoutNotifications(value: Boolean) = save { it[keys.continueNoNotif] = value }
    suspend fun setGithubToken(value: String) = save { it[keys.githubToken] = value.trim() }
    suspend fun setAllowPrerelease(value: Boolean) = save { it[keys.allowPrerelease] = value }
    suspend fun setGameLauncherEnabled(value: Boolean) = save { it[keys.gameLauncher] = value }
    suspend fun setLauncherHaptics(value: Boolean) = save { it[keys.launcherHaptics] = value }
    suspend fun setLauncherSounds(value: Boolean) = save { it[keys.launcherSounds] = value }
    suspend fun setLauncherPlayed(json: String) = save { it[keys.launcherPlayed] = json }

    /**
     * Liga/desliga o modo Instalação Virtual. Além do DataStore (fonte para a
     * UI), escreve a flag espelho em SharedPreferences que o bootstrap do
     * motor lê de forma síncrona no próximo boot do processo.
     */
    suspend fun setVirtualInstallEnabled(value: Boolean) {
        save { it[keys.virtualInstall] = value }
        com.deivid22srk.portstore.virtual.VirtualBootstrap.setBootFlag(context, value)
    }

    suspend fun setVirtualNoticeAcknowledged(value: Boolean) =
        save { it[keys.virtualNotice] = value }

    /**
     * Gesto do menu rápido nos jogos virtuais. Além do DataStore, espelha em
     * SharedPreferences para o processo do motor (:pN) ler — DataStore não é
     * seguro para leitura multiprocesso.
     */
    suspend fun setQuickMenuGestureEnabled(value: Boolean) {
        save { it[keys.quickMenuGesture] = value }
        com.deivid22srk.portstore.virtual.gex.GameExperiencePrefs
            .setQuickMenuGestureEnabled(context, value)
    }

    /** Som curto + vibração ao abrir um jogo pelo Launcher (espelho idem). */
    suspend fun setLaunchFeedbackEnabled(value: Boolean) {
        save { it[keys.launchFeedback] = value }
        com.deivid22srk.portstore.virtual.gex.GameExperiencePrefs
            .setLaunchFeedbackEnabled(context, value)
    }

    /** Modo Desenvolvedor (só UI do processo principal — DataStore basta). */
    suspend fun setDeveloperMode(value: Boolean) = save { it[keys.developerMode] = value }

    fun beginSetupSession() {
        _setupSession.value = true
    }

    fun finishSetupSession() {
        _setupSession.value = false
    }

    private suspend fun save(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
