package com.deivid22srk.portstore.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.github.ReleaseInfo
import com.deivid22srk.portstore.github.VersionResolver
import com.deivid22srk.portstore.github.VersionState
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.service.Notifications
import com.deivid22srk.portstore.util.VersionCompare
import java.util.concurrent.TimeUnit

/**
 * Verificação periódica (12 h) de atualizações: para cada jogo INSTALADO,
 * consulta a versão remota (com cache/ETag/token) e notifica se houver
 * versão mais nova. Respeita o rate limit da API do GitHub.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val catalog = AppGraph.catalog.catalog.value ?: return Result.success()
        val monitor = AppGraph.installedApps
        val resolver = AppGraph.versions
        val pm = context.packageManager

        val installed = catalog.games.filter { game ->
            !game.isWeb && game.packageName.isNotEmpty() &&
                monitor.stateFor(game.packageName) is InstallState.Installed
        }
        if (installed.isEmpty()) return Result.success()

        // Rastreia apenas os jogos instalados para limitar consultas.
        var checked = 0
        for (game in installed) {
            if (checked >= 10) break // proteção extra contra rate limit
            val state = resolver.resolve(game.id, game.links.github ?: game.links.releases)
            checked++
            if (state is VersionState.Resolved) {
                val pkg = game.primaryPackage ?: continue
                val installState = InstalledAppsMonitor.checkPackage(pm, pkg)
                if (installState is InstallState.Installed) {
                    val hasUpdate = VersionCompare.isUpdateAvailable(
                        state.release.version,
                        installState.versionName,
                    )
                    if (hasUpdate) notifyUpdate(context, game.id, game.title, state.release)
                }
            }
            // RateLimited / Unavailable: segue para o próximo sem retry.
        }
        return Result.success()
    }

    private fun notifyUpdate(context: Context, gameId: String, title: String, release: ReleaseInfo) {
        if (!Notifications.canPost(context)) return
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Atualização disponível")
            .setContentText("$title — nova versão ${release.version}")
            .setAutoCancel(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(("update-$gameId").hashCode(), notification)
        }
    }

    companion object {
        private const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
