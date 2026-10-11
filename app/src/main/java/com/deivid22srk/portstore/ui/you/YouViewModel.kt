package com.deivid22srk.portstore.ui.you

import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.deivid22srk.portstore.settings.SettingsRepository
import kotlinx.coroutines.launch

class YouViewModel(
    private val app: Application,
) : ViewModel() {

    private val settings: SettingsRepository = com.deivid22srk.portstore.AppGraph.settings

    val settingsFlow = settings.settings

    fun setTheme(value: Int) {
        viewModelScope.launch {
            settings.setTheme(value)
        }
    }

    fun setSegments(value: Int) {
        viewModelScope.launch {
            settings.setSegments(value)
            com.deivid22srk.portstore.AppGraph.downloads.applyConfigFromSettings()
        }
    }

    fun setMaxConcurrent(value: Int) {
        viewModelScope.launch {
            settings.setMaxConcurrent(value)
            com.deivid22srk.portstore.AppGraph.downloads.applyConfigFromSettings()
        }
    }

    fun setSpeedLimit(mbps: Int) {
        viewModelScope.launch {
            settings.setSpeedLimitMbps(mbps)
            com.deivid22srk.portstore.AppGraph.downloads.applyConfigFromSettings()
        }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(value)
            com.deivid22srk.portstore.AppGraph.downloads.applyConfigFromSettings()
        }
    }

    fun setGithubToken(value: String) {
        viewModelScope.launch { settings.setGithubToken(value) }
    }

    fun setAllowPrerelease(value: Boolean) {
        viewModelScope.launch { settings.setAllowPrerelease(value) }
    }

    /** Liga/desliga o modo "Game Launcher" (interface alternativa estilo console). */
    fun setGameLauncherEnabled(value: Boolean) {
        viewModelScope.launch { settings.setGameLauncherEnabled(value) }
    }

    /**
     * Liga/desliga o modo "Instalação Virtual". Desligar NÃO apaga jogos
     * virtuais existentes — só afeta onde os novos jogos são instalados.
     * Ao LIGAR, tenta inicializar o motor na hora (bootstrap tardio): se
     * conseguir, o modo já funciona sem reiniciar o app; se não conseguir,
     * a UI mostra o aviso de reinício.
     */
    fun setVirtualInstallEnabled(value: Boolean) {
        viewModelScope.launch {
            settings.setVirtualInstallEnabled(value)
            if (value) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.deivid22srk.portstore.AppGraph.virtual.ensureEngine()
                    }
                }
            }
        }
    }

    /** Marca o aviso inicial do modo virtual como confirmado. */
    fun acknowledgeVirtualNotice() {
        viewModelScope.launch { settings.setVirtualNoticeAcknowledged(true) }
    }

    /** Gesto do menu rápido dentro de jogos virtuais (Recurso 3). */
    fun setQuickMenuGestureEnabled(value: Boolean) {
        viewModelScope.launch { settings.setQuickMenuGestureEnabled(value) }
    }

    /** Som + vibração curtos ao abrir um jogo pelo Launcher (Recurso 1). */
    fun setLaunchFeedbackEnabled(value: Boolean) {
        viewModelScope.launch { settings.setLaunchFeedbackEnabled(value) }
    }

    /** Remove TODOS os jogos virtuais (jogos + dados) — só com confirmação. */
    fun uninstallAllVirtualGames() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.deivid22srk.portstore.AppGraph.virtual.apps.value.forEach { app ->
                runCatching { com.deivid22srk.portstore.AppGraph.virtual.uninstall(app.packageName) }
            }
        }
    }

    /** Reabre o assistente: etapa de permissões (0) ou repositórios (1). */
    fun openSetup(step: Int) {
        viewModelScope.launch {
            // Persiste a etapa ANTES de abrir a sessão: o SetupViewModel lê o
            // setupStep ao iniciar/resetar e precisa do valor novo.
            settings.setSetupStep(step)
            settings.beginSetupSession()
        }
    }

    /**
     * Pasta opcional (SAF): os APKs continuam sendo baixados na pasta interna
     * (sem permissões extras) e, ao concluir, são copiados para a pasta escolhida.
     * O resultado do OpenDocumentTree chega no launcher da YouScreen.
     */
    fun setDownloadTreeUri(uri: android.net.Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                settings.setDownloadTreeUri("")
            } else {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching {
                    app.contentResolver.takePersistableUriPermission(uri, flags)
                }
                settings.setDownloadTreeUri(uri.toString())
            }
        }
    }

    fun clearImageCache(context: Context) {
        runCatching { context.imageLoader.memoryCache?.clear() }
        runCatching { context.imageLoader.diskCache?.clear() }
    }
}
