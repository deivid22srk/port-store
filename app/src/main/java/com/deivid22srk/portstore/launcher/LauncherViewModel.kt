package com.deivid22srk.portstore.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.catalog.Catalog
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.catalog.categoryLabel
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Seções da loja estilo eShop do Game Launcher. */
data class LauncherStoreSections(
    val banner: Game? = null,
    val trending: List<Game> = emptyList(),
    val fresh: List<Game> = emptyList(),
    val mostDownloaded: List<Game> = emptyList(),
    val byCategory: List<Pair<String, List<Game>>> = emptyList(),
)

/**
 * ViewModel compartilhado por todas as telas do Game Launcher (escopo da
 * Activity). Reaproveita as camadas de dados do Port Store — catálogo,
 * downloads e monitor de apps instalados — sem duplicar lógica.
 */
class LauncherViewModel(private val app: Application) : ViewModel() {

    private val settingsRepo = AppGraph.settings
    private val catalogRepo = AppGraph.catalog
    private val downloadsRepo = AppGraph.downloads
    private val installedMonitor = AppGraph.installedApps

    private val json = Json { ignoreUnknownKeys = true }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val downloadItems: StateFlow<List<DownloadItem>> = downloadsRepo.items
    val catalogState: StateFlow<Catalog?> = catalogRepo.catalog

    /** gameId -> timestamp do último play (fonte de "jogados recentemente"). */
    private val playedAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Memória de foco: card focado na Home antes de navegar (restaurado ao voltar). */
    var homeFocusedGameId: String? = null

    init {
        viewModelScope.launch {
            playedAt.value = parsePlayed(settingsRepo.settings.value.launcherPlayed)
        }
    }

    /**
     * Jogos instalados no aparelho (Android OU virtualmente), ordenados por
     * "jogados recentemente" primeiro; sem dado de play, ordem alfabética.
     */
    val installedGames: StateFlow<List<Game>> = combine(
        catalogRepo.catalog,
        installedMonitor.states,
        playedAt,
        com.deivid22srk.portstore.AppGraph.virtual.apps,
    ) { catalog, states, played, vapps ->
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        catalog?.games.orEmpty()
            .filter { g ->
                !g.isWeb && (
                    g.packageName.any { pkg ->
                        val state = states[pkg]
                        state is InstallState.Installed || state is InstallState.UpdateAvailable
                    } ||
                        vapps.any { it.packageName == virtual.packageNameFor(g.id) }
                    )
            }
            .sortedWith(
                compareByDescending<Game> { played[it.id] ?: 0L }
                    .thenBy { it.title.lowercase() },
            )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Conteúdo da loja estilo eShop (mesmo catálogo, outro layout). */
    val storeSections: StateFlow<LauncherStoreSections> = catalogRepo.catalog
        .map { catalog -> buildStoreSections(catalog) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherStoreSections())

    private fun buildStoreSections(catalog: Catalog?): LauncherStoreSections {
        val games = catalog?.games.orEmpty().filter { it.isAvailable }
        if (games.isEmpty()) return LauncherStoreSections()
        val banner = games.firstOrNull { it.featured && it.bannerUrl != null }
            ?: games.firstOrNull { it.featured }
            ?: games.firstOrNull()
        val trending = games
            .sortedWith(compareByDescending<Game> { it.featured }.thenByDescending { it.downloads })
            .take(SECTION_LIMIT)
        val fresh = games
            .sortedByDescending { it.dateAdded.orEmpty() }
            .take(SECTION_LIMIT)
        val mostDownloaded = games
            .sortedByDescending { it.downloads }
            .take(SECTION_LIMIT)
        val categoryOrder = games.flatMap { it.categories }.distinct().take(CATEGORY_LIMIT)
        val byCategory = categoryOrder.map { slug ->
            categoryLabel(slug) to games.filter { slug in it.categories }.take(SECTION_LIMIT)
        }
        return LauncherStoreSections(banner, trending, fresh, mostDownloaded, byCategory)
    }

    /** Registra o play de um jogo para a ordenação "jogados recentemente". */
    fun recordPlayed(gameId: String) {
        val updated = playedAt.value + (gameId to System.currentTimeMillis())
        playedAt.value = updated
        viewModelScope.launch {
            runCatching {
                val serialized = json.encodeToString(
                    MapSerializer(String.serializer(), Long.serializer()),
                    updated,
                )
                settingsRepo.setLauncherPlayed(serialized)
            }
        }
    }

    /**
     * Abre um jogo: instalação virtual primeiro (ambiente dentro do Port
     * Store), senão a instalação normal do Android. Retorna false (sem abrir
     * nada) quando nenhum pacote existe mais — a UI mostra o aviso de erro.
     */
    fun launchGame(context: Context, game: Game): Boolean {
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        val vpkg = virtual.packageNameFor(game.id)
        if (vpkg != null && virtual.isInstalled(vpkg) && virtual.launch(vpkg)) {
            recordPlayed(game.id)
            return true
        }
        val pm = context.packageManager
        val pkg = game.packageName.firstOrNull {
            InstalledAppsMonitor.checkPackage(pm, it) is InstallState.Installed
        } ?: game.primaryPackage ?: return false
        val intent = InstalledAppsMonitor.launchIntent(context, pkg) ?: return false
        recordPlayed(game.id)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** O jogo está instalado dentro do ambiente virtual? */
    fun isVirtualGame(game: Game): Boolean {
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        val pkg = virtual.packageNameFor(game.id) ?: return false
        return virtual.apps.value.any { it.packageName == pkg }
    }

    /** Desinstala o jogo do ambiente virtual (jogo + dados), se virtual. */
    fun uninstallVirtualGame(game: Game) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.deivid22srk.portstore.AppGraph.virtual
                .packageNameFor(game.id)?.let { pkg ->
                    com.deivid22srk.portstore.AppGraph.virtual.uninstall(pkg)
                }
        }
    }

    /** Intent de desinstalação do sistema (REQUEST_DELETE_PACKAGES já no manifest). */
    fun uninstallIntent(game: Game): Intent? {
        val pm = app.packageManager
        val pkg = game.packageName.firstOrNull {
            InstalledAppsMonitor.checkPackage(pm, it) is InstallState.Installed
        } ?: game.primaryPackage ?: return null
        return Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())
    }

    // ===== Downloads: delegação direta ao repositório existente =====

    fun pauseDownload(id: String) = downloadsRepo.pause(id)
    fun resumeDownload(id: String) = downloadsRepo.resume(id)
    fun cancelDownload(id: String) = downloadsRepo.cancel(id)
    fun retryDownload(id: String) = downloadsRepo.retry(id)
    fun deleteDownload(id: String) = downloadsRepo.delete(id)

    /**
     * Instala o APK concluído de um download: no ambiente virtual quando o
     * modo está ativo e o motor pronto, senão no Android (comportamento atual).
     */
    fun installDownloadedApk(path: String) {
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        val settings = settingsRepo.settings.value
        if (settings.virtualInstallEnabled && virtual.engineReady) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                virtual.installFromApk(
                    gameId = downloads.items.value.firstOrNull { it.destPath == path }?.gameId ?: path,
                    apk = java.io.File(path),
                )
            }
        } else {
            com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
        }
    }

    /** Recarrega o catálogo (estado de erro/offline da loja). */
    fun refreshCatalog() {
        viewModelScope.launch {
            runCatching { catalogRepo.refreshAll(force = false) }
        }
    }

    private fun parsePlayed(raw: String): Map<String, Long> = runCatching {
        if (raw.isBlank()) emptyMap() else json.decodeFromString(
            MapSerializer(String.serializer(), Long.serializer()),
            raw,
        )
    }.getOrDefault(emptyMap())

    private companion object {
        const val SECTION_LIMIT = 14
        const val CATEGORY_LIMIT = 6
    }
}
