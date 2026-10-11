package com.deivid22srk.portstore.backup

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.service.Notifications
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Notificações dos jobs de backup (exportação/importação). Reusa os canais
 * de download do app; progresso/velocidade/ETA vêm do [BackupProgressBus].
 * O botão "Cancelar" acorda [BackupActionReceiver].
 */
object BackupNotifications {

    fun notificationId(jobId: String): Int = jobId.hashCode()

    fun foregroundInfo(context: Context, jobId: String, title: String, gameId: String): ForegroundInfo {
        val notification = base(context, jobId, title, gameId)
            .setProgress(0, 0, true)
            .setContentText(context.getString(R.string.backup_state_preparing))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(jobId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(jobId), notification)
        }
    }

    fun update(context: Context, jobId: String, title: String, gameId: String, ui: BackupJobUi) {
        if (!Notifications.canPost(context)) return
        val text = buildString {
            append(context.getString(R.string.notif_progress_pct, (ui.progress * 100).toInt()))
            val speed = com.deivid22srk.portstore.util.Formatters.formatSpeed(ui.speedBps)
            if (speed.isNotBlank()) append(" · ").append(speed)
            val eta = com.deivid22srk.portstore.util.Formatters.formatEta(ui.etaSec)
            if (eta.isNotBlank()) append(" · ").append(eta)
        }
        val notification = base(context, jobId, title, gameId)
            .setProgress(100, (ui.progress * 100).toInt(), false)
            .setContentText(text)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(jobId), notification)
        }
    }

    fun done(context: Context, title: String, gameId: String, message: String) {
        if (!Notifications.canPost(context)) return
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(("backup-done-$gameId").hashCode(), notification)
        }
    }

    fun error(context: Context, jobId: String, title: String, gameId: String, message: String) {
        if (!Notifications.canPost(context)) return
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_error, message))
            .setAutoCancel(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(jobId), notification)
        }
    }

    fun cancel(context: Context, jobId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(jobId)) }
    }

    private fun base(context: Context, jobId: String, title: String, gameId: String): NotificationCompat.Builder {
        val cancelIntent = Intent(context, BackupActionReceiver::class.java).apply {
            action = BackupActionReceiver.ACTION_CANCEL
            putExtra(BackupActionReceiver.EXTRA_JOB_ID, jobId)
        }
        val cancelPending = android.app.PendingIntent.getBroadcast(
            context,
            ("backup-cancel-$jobId").hashCode(),
            cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .addAction(0, context.getString(R.string.notif_action_cancel), cancelPending)
    }

    /** Título do job para notificações/diálogos. */
    fun jobTitle(context: Context, direction: BackupDirection, gameTitle: String): String =
        context.getString(
            if (direction == BackupDirection.EXPORT) R.string.notif_export_title else R.string.notif_import_title,
            gameTitle,
        )

    /** Mensagem de conclusão. */
    fun doneMessage(context: Context, direction: BackupDirection, gameTitle: String): String =
        context.getString(
            if (direction == BackupDirection.EXPORT) R.string.notif_export_done else R.string.notif_import_done,
            gameTitle,
        )
}
