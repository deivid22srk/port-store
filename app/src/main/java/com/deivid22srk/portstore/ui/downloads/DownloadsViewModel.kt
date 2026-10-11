package com.deivid22srk.portstore.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.core.DownloadRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val app: android.app.Application,
    private val downloads: DownloadRepository,
) : ViewModel() {

    val items: StateFlow<List<com.deivid22srk.portstore.core.DownloadItem>> = downloads.items

    fun pause(id: String) = downloads.pause(id)
    fun resume(id: String) = downloads.resume(id)
    fun cancel(id: String) = downloads.cancel(id)
    fun retry(id: String) = downloads.retry(id)
    fun delete(id: String) = downloads.delete(id)
    fun clearFinished() = downloads.clearFinished()

    /**
     * Instala o APK concluído: no ambiente virtual quando o modo Instalação
     * Virtual está ativo (e o motor pronto), senão no Android como hoje.
     */
    fun install(path: String) {
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        val settings = com.deivid22srk.portstore.AppGraph.settings.settings.value
        if (settings.virtualInstallEnabled && virtual.engineReady) {
            val gameId = downloads.items.value.firstOrNull { it.destPath == path }?.gameId ?: path
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                virtual.installFromApk(gameId, java.io.File(path))
            }
        } else {
            com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
        }
    }
}
