package com.deivid22srk.portstore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.deivid22srk.portstore.MainActivity
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.util.Formatters

object Notifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_DONE = "downloads_done"
    const val CHANNEL_UPDATES = "updates"
    const val SUMMARY_ID = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val downloads = NotificationChannel(
            CHANNEL_DOWNLOADS,
            "Downloads em andamento",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progresso dos downloads de ports"
            setShowBadge(false)
        }
        val done = NotificationChannel(
            CHANNEL_DONE,
            "Downloads concluídos",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisos de download concluído ou com falha"
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Atualizações disponíveis",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Aviso quando um port instalado tiver versão nova"
        }
        nm.createNotificationChannel(downloads)
        nm.createNotificationChannel(done)
        nm.createNotificationChannel(updates)
    }

    /** Pode postar notificações? (Android 13+) */
    fun canPost(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun contentIntent(context: Context, gameId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", "downloads")
            gameId?.let { putExtra("gameId", it) }
        }
        return PendingIntent.getActivity(
            context,
            (gameId ?: "downloads").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, id: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_ID, id)
        }
        return PendingIntent.getService(
            context,
            (action + id).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun progressNotification(context: Context, item: DownloadItem): Notification {
        val ongoing = item.state in setOf(
            com.deivid22srk.portstore.core.DlState.DOWNLOADING,
            com.deivid22srk.portstore.core.DlState.CONNECTING,
            com.deivid22srk.portstore.core.DlState.VERIFYING,
        )
        val text = when (item.state) {
            com.deivid22srk.portstore.core.DlState.DOWNLOADING -> {
                val pct = (item.progress * 100).toInt()
                val parts = mutableListOf("$pct%")
                if (item.speedBps > 0) parts.add("${Formatters.formatSpeed(item.speedBps)}")
                if (item.etaSec > 0) parts.add(Formatters.formatEta(item.etaSec))
                parts.joinToString(" • ")
            }
            com.deivid22srk.portstore.core.DlState.CONNECTING -> "Conectando…"
            com.deivid22srk.portstore.core.DlState.VERIFYING -> "Verificando arquivo…"
            com.deivid22srk.portstore.core.DlState.QUEUED -> "Na fila…"
            else -> "Aguardando rede…"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent(context, item.gameId))
            .setGroup(DownloadService.GROUP_KEY)
            .setProgress(100, (item.progress * 100).toInt(), item.state == com.deivid22srk.portstore.core.DlState.CONNECTING)
        if (ongoing || item.state == com.deivid22srk.portstore.core.DlState.QUEUED) {
            builder.addAction(
                0,
                "Pausar",
                actionIntent(context, DownloadService.ACTION_PAUSE, item.id),
            )
        } else if (item.state == com.deivid22srk.portstore.core.DlState.PAUSED) {
            builder.addAction(
                0,
                "Retomar",
                actionIntent(context, DownloadService.ACTION_RESUME, item.id),
            )
        }
        builder.addAction(
            0,
            "Cancelar",
            actionIntent(context, DownloadService.ACTION_CANCEL, item.id),
        )
        return builder.build()
    }

    fun summaryNotification(context: Context, active: List<DownloadItem>): Notification {
        val totalBytes = active.sumOf { it.downloaded }
        val totalSize = active.sumOf { it.total }
        val pct = if (totalSize > 0) (totalBytes * 100 / totalSize).toInt() else 0
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Port Store")
            .setContentText("${active.size} download(s) • $pct%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(DownloadService.GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(contentIntent(context, null))
            .build()
    }

    fun doneNotification(context: Context, item: DownloadItem, success: Boolean): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(contentIntent(context, item.gameId))
            .setAutoCancel(true)
        if (success) {
            builder.setContentTitle("Download concluído")
                .setContentText("${item.title} — toque para instalar")
        } else {
            builder.setContentTitle("Falha no download")
                .setContentText("${item.title} — toque para tentar de novo")
        }
        return builder.build()
    }
}
