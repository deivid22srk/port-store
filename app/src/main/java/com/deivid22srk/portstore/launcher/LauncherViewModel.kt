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
     * Jogos instalados no aparelho, ordenados por "jogados recentemente"
     * primeiro; sem dado de play, ordem alfabética.
     */
    val installedGames: StateFlow<List<Game>> = combine(
        catalogRepo.catalog,
        installedMonitor.states,
        playedAt,
    ) { catalog, states, played ->
        catalog?.games.orEmpty()
            .filter { g ->
                !g.isWeb && g.packageName.any { pkg ->
                    val state = states[pkg]
                    state is InstallState.Installed || state is InstallState.UpdateAvailable
                }
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
     * Abre um jogo instalado. Retorna false (sem abrir nada) quando nenhum
     * pacote do jogo existe mais — a UI mostra o aviso de erro.
     */
    fun launchGame(context: Context, game: Game): Boolean {
        val pm = context.packageManager
        val pkg = game.packageName.firstOrNull {
            InstalledAppsMonitor.checkPackage(pm, it) is InstallState.Installed
        } ?: game.primaryPackage ?: return false
        val intent = InstalledAppsMonitor.launchIntent(context, pkg) ?: return false
        recordPlayed(game.id)
        return runCatching { context.startActivity(intent) }.isSuccess
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

    /** Instala o APK concluído de um download. */
    fun installDownloadedApk(path: String) {
        com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
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
