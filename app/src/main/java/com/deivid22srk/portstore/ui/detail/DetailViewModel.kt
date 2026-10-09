package com.deivid22srk.portstore.ui.detail

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.core.DownloadRepository
import com.deivid22srk.portstore.github.ApkAsset
import com.deivid22srk.portstore.github.ReleaseInfo
import com.deivid22srk.portstore.github.ReleaseResolver
import com.deivid22srk.portstore.github.VersionResolver
import com.deivid22srk.portstore.github.VersionState
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.util.VersionCompare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado de versão/instalação exposto para a página do jogo. */
data class VersionUi(
    val loading: Boolean = true,
    val release: ReleaseInfo? = null,
    val fromCache: Boolean = false,
    val rateLimited: Boolean = false,
    val unavailable: Boolean = false,
    val install: InstallState = InstallState.Unknown,
    val updateAvailable: Boolean = false,
    val installedVersion: String? = null,
)

data class DetailUiState(
    val game: Game? = null,
    val resolving: Boolean = false,
    val resolveError: String? = null,
    val showVariants: List<ApkAsset>? = null,
    val installWarning: String? = null,
)

private const val UNINSTALL_TAG = "PortStoreUninstall"

class DetailViewModel(
    private val app: android.app.Application,
    private val gameId: String,
    catalogRepo: CatalogRepository,
    private val downloads: DownloadRepository,
    private val resolver: ReleaseResolver,
) : ViewModel() {

    private val versions = com.deivid22srk.portstore.AppGraph.versions
    private val monitor = com.deivid22srk.portstore.AppGraph.installedApps

    val game: StateFlow<Game?> = catalogRepo.catalog
        .map { cat -> cat?.games?.firstOrNull { it.id == gameId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val downloadItem: StateFlow<DownloadItem?> = downloads.items
        .map { items -> items.firstOrNull { it.gameId == gameId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    private val _version = MutableStateFlow(VersionUi())
    val version: StateFlow<VersionUi> = _version.asStateFlow()

    init {
        // Resolve a versão sob demanda (só o jogo aberto), reagindo a mudanças do jogo.
        viewModelScope.launch {
            var lastRepo: String? = null
            game.collect { g ->
                if (g == null || g.isWeb) return@collect
                monitor.track(g.packageName.toSet())
                val repo = g.links.github ?: g.links.releases
                if (repo != lastRepo || _version.value.loading) {
                    lastRepo = repo
                    loadVersion(g, repo, force = false)
                }
            }
        }
        // Reage a instalar/desinstalar para atualizar o botão automaticamente.
        viewModelScope.launch {
            monitor.states.collect {
                recomputeInstallState()
            }
        }
    }

    private suspend fun loadVersion(g: Game, repo: String?, force: Boolean) {
        if (repo == null) {
            _version.value = VersionUi(loading = false, unavailable = true)
            recomputeInstallState()
            return
        }
        _version.value = _version.value.copy(loading = true, unavailable = false)
        when (val state = versions.resolve(g.id, repo, force = force)) {
            is VersionState.Resolved -> _version.value = _version.value.copy(
                loading = false,
                release = state.release,
                fromCache = state.fromCache,
                rateLimited = false,
                unavailable = false,
            )
            is VersionState.RateLimited -> _version.value = _version.value.copy(
                loading = false,
                rateLimited = true,
            )
            is VersionState.Unavailable -> _version.value = _version.value.copy(
                loading = false,
                unavailable = true,
            )
            else -> Unit
        }
        recomputeInstallState()
    }

    fun retryVersion() {
        val g = game.value ?: return
        viewModelScope.launch { loadVersion(g, g.links.github ?: g.links.releases, force = true) }
    }

    private fun recomputeInstallState() {
        val g = game.value ?: return
        if (g.isWeb) {
            _version.value = _version.value.copy(install = InstallState.Unknown, updateAvailable = false)
            return
        }
        val state = monitor.stateFor(g.packageName)
        val release = _version.value.release
        val update = state is InstallState.Installed &&
            release != null &&
            VersionCompare.isUpdateAvailable(release.version, state.versionName)
        _version.value = _version.value.copy(
            install = state,
            updateAvailable = update,
            installedVersion = (state as? InstallState.Installed)?.versionName,
        )
    }

    fun downloadFor(game: Game): DownloadItem? = downloadItem.value

    fun startDownload(game: Game, asset: ApkAsset?) {
        _ui.value = _ui.value.copy(showVariants = null, resolveError = null, installWarning = null)
        downloads.startDownload(game, asset?.url ?: game.links.download ?: return, asset?.sha256)
    }

    /** Resolve o APK a baixar: prioriza os assets do release real; cai no links.download. */
    fun onInstallClicked(game: Game) {
        if (game.isSoon || game.isRemoved) return
        if (game.isWeb) return // tratado na UI (botão jogar no navegador)
        if (_ui.value.resolving) return

        val release = _version.value.release
        if (release != null && release.assets.isNotEmpty()) {
            offerAssets(release.assets)
            return
        }

        val direct = game.links.download ?: game.links.github ?: return
        _ui.value = _ui.value.copy(resolving = true, resolveError = null)
        viewModelScope.launch {
            resolver.resolve(direct)
                .onSuccess { assets ->
                    _ui.value = _ui.value.copy(resolving = false)
                    offerAssets(assets)
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(resolving = false, resolveError = e.message)
                }
        }
    }

    private fun offerAssets(assets: List<ApkAsset>) {
        val game = game.value ?: return
        when {
            assets.isEmpty() ->
                _ui.value = _ui.value.copy(resolveError = "Nenhum APK foi encontrado no release deste port.")
            assets.size == 1 -> startDownload(game, assets.first())
            else -> _ui.value = _ui.value.copy(showVariants = assets)
        }
    }

    fun openInstalled() {
        val g = game.value ?: return
        val pkg = g.primaryPackage ?: return
        val intent = InstalledAppsMonitor.launchIntent(app, pkg)
        if (intent != null) {
            app.startActivity(intent)
        } else {
            _ui.value = _ui.value.copy(resolveError = "O app está instalado, mas não tem tela inicial própria.")
        }
    }

    /**
     * Monta o Intent de desinstalação (ACTION_DELETE) para o pacote do jogo
     * que está de fato instalado. A escolha consulta o PackageManager na hora
     * (não confia no cache do monitor) — pode divergir do pacote primário
     * quando o jogo declara mais de um packageName.
     *
     * Retorna null quando nenhum pacote do jogo está instalado: nesse caso o
     * monitor é re-verificado e o botão volta para "Instalar" sozinho.
     * O LAUNCH do intent é responsabilidade da UI (ActivityResultLauncher);
     * falhas de launch são reportadas via [reportUninstallLaunchError].
     */
    fun buildUninstallIntent(): Intent? {
        val g = game.value ?: return null
        if (g.packageName.isEmpty() && g.primaryPackage == null) return null

        val pm = app.packageManager
        val fresh = g.packageName.associateWith { InstalledAppsMonitor.checkPackage(pm, it) }

        when {
            // Pacote confirmadamente instalado: desinstala esse.
            fresh.any { it.value is InstallState.Installed } -> Unit
            // Todos os candidatos consultáveis dizem "não instalado": estado do
            // botão está velho — re-verifica e aborta sem abrir o desinstalador.
            fresh.isNotEmpty() && fresh.values.all { it is InstallState.NotInstalled } -> {
                Log.i(UNINSTALL_TAG, "Nenhum pacote de '${g.title}' está instalado; atualizando estado.")
                monitor.refresh()
                return null
            }
            // Consultas falharam (Unknown) ou lista vazia: tenta o pacote primário.
            else -> Log.w(UNINSTALL_TAG, "Consulta fresca inconclusiva (${fresh.values}); usando pacote primário.")
        }

        val pkg = fresh.filterValues { it is InstallState.Installed }.keys.firstOrNull()
            ?: fresh.filterValues { it is InstallState.Unknown }.keys.firstOrNull()
            ?: g.primaryPackage
            ?: return null

        Log.i(UNINSTALL_TAG, "Solicitando desinstalação de '$pkg' (jogo '${g.title}') via ACTION_DELETE.")
        return Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())
    }

    /** Resultado do UninstallerActivity do sistema (lançado pelo launcher da UI). */
    fun onUninstallResult(resultCode: Int) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.i(UNINSTALL_TAG, "Desinstalação concluída pelo usuário.")
                monitor.refresh()
            }
            Activity.RESULT_CANCELED -> Log.i(UNINSTALL_TAG, "Desinstalação cancelada pelo usuário.")
            else -> {
                Log.i(UNINSTALL_TAG, "Desinstalador retornou resultCode=$resultCode; re-verificando pacotes.")
                monitor.refresh()
            }
        }
    }

    /** Falha síncrona ao lançar o desinstalador (permissão ausente, sem handler, etc.). */
    fun reportUninstallLaunchError(e: Throwable) {
        Log.e(UNINSTALL_TAG, "Falha ao abrir a desinstalação do sistema", e)
        _ui.value = _ui.value.copy(
            resolveError = when (e) {
                is SecurityException ->
                    "O sistema bloqueou a desinstalação " +
                        "(permissão REQUEST_DELETE_PACKAGES ausente ou negada)."
                is ActivityNotFoundException ->
                    "Nenhum desinstalador do sistema foi encontrado neste aparelho."
                else -> "Não foi possível abrir a desinstalação do sistema: ${e.message ?: e.javaClass.simpleName}"
            },
        )
    }

    fun dismissVariants() {
        _ui.value = _ui.value.copy(showVariants = null)
    }

    fun pause() = downloadItem.value?.let { downloads.pause(it.id) }
    fun resume() = downloadItem.value?.let { downloads.resume(it.id) }
    fun cancel() = downloadItem.value?.let { downloads.cancel(it.id) }

    fun installApk(path: String) {
        com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
        // Confere se o pacote do APK bate com o catálogo (aviso em caso de divergência).
        val g = game.value
        val archivePkg = InstalledAppsMonitor.archivePackageName(app, path)
        if (g != null && g.packageName.isNotEmpty() && archivePkg != null && archivePkg !in g.packageName) {
            _ui.value = _ui.value.copy(
                installWarning = "O APK baixado declara o pacote \"$archivePkg\", que é diferente do esperado para este port (${g.primaryPackage}). Verifique se é o app certo antes de abrir.",
            )
        }
    }

    fun retry() = downloadItem.value?.let { downloads.retry(it.id) }
}
