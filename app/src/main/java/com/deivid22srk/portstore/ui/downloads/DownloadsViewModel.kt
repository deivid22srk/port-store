package com.deivid22srk.portstore.ui.downloads

import androidx.lifecycle.ViewModel
import com.deivid22srk.portstore.core.DownloadRepository
import kotlinx.coroutines.flow.StateFlow

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
    fun install(path: String) {
        com.deivid22srk.portstore.installer.ApkInstaller.installApk(app, path)
    }
}
