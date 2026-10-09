package com.deivid22srk.portstore.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.core.DownloadRepository
import com.deivid22srk.portstore.github.ApkAsset
import com.deivid22srk.portstore.github.ReleaseResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val game: Game? = null,
    val resolving: Boolean = false,
    val resolveError: String? = null,
    val showVariants: List<ApkAsset>? = null,
)

class DetailViewModel(
    private val app: android.app.Application,
    private val gameId: String,
    catalogRepo: CatalogRepository,
    private val downloads: DownloadRepository,
    private val resolver: ReleaseResolver,
) : ViewModel() {

    val game: StateFlow<Game?> = catalogRepo.catalog
        .map { cat -> cat?.games?.firstOrNull { it.id == gameId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val downloadItem: StateFlow<DownloadItem?> = downloads.items
        .map { items -> items.firstOrNull { it.gameId == gameId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    fun downloadFor(game: Game): DownloadItem? = downloadItem.value

    fun startDownload(game: Game, asset: ApkAsset?) {
        _ui.value = _ui.value.copy(showVariants = null, resolveError = null)
        downloads.startDownload(game, asset?.url ?: game.links.download ?: return, asset?.sha256)
    }

    /** Resolve o link (GitHub releases/MediaFire/direto) e decide: baixar ou escolher variante. */
    fun onInstallClicked(game: Game) {
        if (game.isSoon || game.isRemoved) return
        if (game.isWeb) return // tratado na UI (botão jogar no navegador)
        val direct = game.links.download ?: return

        if (_ui.value.resolving) return
        _ui.value = _ui.value.copy(resolving = true, resolveError = null)
        viewModelScope.launch {
            resolver.resolve(direct)
                .onSuccess { assets ->
                    _ui.value = _ui.value.copy(resolving = false)
                    when {
                        assets.isEmpty() ->
                            _ui.value = _ui.value.copy(resolveError = "Nenhum APK foi encontrado no release deste port.")
                        assets.size == 1 -> startDownload(game, assets.first())
                        else -> _ui.value = _ui.value.copy(showVariants = assets)
                    }
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(resolving = false, resolveError = e.message)
                }
        }
    }

    fun dismissVariants() {
        _ui.value = _ui.value.copy(showVariants = null)
    }

    fun pause() = downloadItem.value?.let { downloads.pause(it.id) }
    fun resume() = downloadItem.value?.let { downloads.resume(it.id) }
    fun cancel() = downloadItem.value?.let { downloads.cancel(it.id) }

    fun installApk(path: String) {
        com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
    }

    fun retry() = downloadItem.value?.let { downloads.retry(it.id) }
}
