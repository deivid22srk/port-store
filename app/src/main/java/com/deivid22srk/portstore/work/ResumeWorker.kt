package com.deivid22srk.portstore.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.service.DownloadService

/** Reenfileira downloads interrompidos (queda do processo/reboot/rede). */
class ResumeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            AppGraph.downloads.restorePending()
            DownloadService.ensureStarted(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
