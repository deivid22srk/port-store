package com.deivid22srk.portstore.ui.downloads

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.core.DownloadRepository
import com.deivid22srk.portstore.ui.virtual.virtualFailureRes
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
     * Virtual está ativo — com inicialização sob demanda do motor, para não
     * exigir reinício — e no Android apenas com o modo desligado.
     *
     * Com o modo ativo o instalador do Android NUNCA é aberto implicitamente:
     * se o motor não subir, o usuário recebe um aviso claro em vez do diálogo
     * de instalação do sistema.
     */
    fun install(path: String) {
        val virtual = com.deivid22srk.portstore.AppGraph.virtual
        val settings = com.deivid22srk.portstore.AppGraph.settings.settings.value
        if (!settings.virtualInstallEnabled) {
            com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ready = virtual.ensureEngine()
            if (!ready) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        app.getString(R.string.virtual_err_engine),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            val gameId = downloads.items.value.firstOrNull { it.destPath == path }?.gameId ?: path
            val status = virtual.installFromApk(gameId, File(path))
            withContext(Dispatchers.Main) {
                val msg = when (status) {
                    is VirtualInstallStatus.Success ->
                        app.getString(R.string.virtual_install_done)
                    is VirtualInstallStatus.Failure ->
                        app.getString(virtualFailureRes(status.reason))
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
