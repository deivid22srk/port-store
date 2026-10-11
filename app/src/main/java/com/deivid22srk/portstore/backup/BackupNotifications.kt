package com.deivid22srk.portstore.backup

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.service.Notifications
import com.deivid22srk.portstore.util.Formatters

/**
 * Notificações dos jobs de backup (exportação/importação). O PROGRESSO é
 * propriedade do [BackupForegroundService] (uma única notificação, id fixo,
 * canal de downloads); conclusão/erro são notificações normais do worker.
 * O botão "Cancelar" acorda [BackupActionReceiver].
 */
object BackupNotifications {

    /** Id fixo da notificação do serviço foreground (um job por vez na barra). */
    const val SERVICE_NOTIFICATION_ID = 42130

    fun notificationId(jobId: String): Int = jobId.hashCode()

    fun canPost(context: Context): Boolean = Notifications.canPost(context)

    /**
     * Notificação de progresso do serviço foreground. [job] null = estado
     * inicial ("Preparando…", barra indeterminada). [extraActive] > 0 indica
     * fila ("+N na fila").
     */
    fun serviceProgress(context: Context, job: BackupJobUi?, extraActive: Int): Notification {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        if (job == null) {
            return builder
                .setContentTitle(context.getString(R.string.notif_backup_generic_title))
                .setContentText(context.getString(R.string.backup_state_preparing))
                .setProgress(0, 0, true)
                .build()
        }

        val title = jobTitle(context, job.direction, job.gameTitle)
        builder.setContentTitle(title)
            .setContentIntent(Notifications.contentIntent(context, job.gameId))
        if (extraActive > 0) {
            builder.setSubText(context.getString(R.string.notif_backup_queue_extra, extraActive))
        }

        when (job.phase) {
            BackupPhase.QUEUED -> builder
                .setContentText(context.getString(R.string.backup_state_queued))
                .setProgress(0, 0, true)

            BackupPhase.PREPARING -> builder
                .setContentText(context.getString(R.string.backup_state_preparing))
                .setProgress(0, 0, true)

            BackupPhase.RUNNING -> {
                val text = buildString {
                    append(context.getString(R.string.notif_progress_pct, (job.progress * 100).toInt()))
                    val speed = Formatters.formatSpeed(job.speedBps)
                    if (speed.isNotBlank()) append(" · ").append(speed)
                    val eta = Formatters.formatEta(job.etaSec)
                    if (eta.isNotBlank()) append(" · ").append(eta)
                }
                builder.setContentText(text)
                    .setProgress(100, (job.progress * 100).toInt(), false)
            }

            else -> Unit // DONE/ERROR/CANCELED não são de responsabilidade daqui
        }

        builder.addAction(0, context.getString(R.string.notif_action_cancel), cancelPending(context, job.jobId))
        return builder.build()
    }

    fun done(context: Context, title: String, gameId: String, message: String) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(doneId(gameId), notification)
        }
    }

    fun error(context: Context, title: String, gameId: String, message: String) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_error, message))
            .setAutoCancel(true)
            .setContentIntent(Notifications.contentIntent(context, gameId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(doneId(gameId), notification)
        }
    }

    /** Remove a notificação de progresso (fim/cancelamento do job). */
    fun cancel(context: Context, jobId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(jobId))
            NotificationManagerCompat.from(context).cancel(SERVICE_NOTIFICATION_ID)
        }
    }

    private fun doneId(gameId: String): Int = ("backup-done-$gameId").hashCode()

    private fun cancelPending(context: Context, jobId: String): android.app.PendingIntent {
        val cancelIntent = Intent(context, BackupActionReceiver::class.java).apply {
            action = BackupActionReceiver.ACTION_CANCEL
            putExtra(BackupActionReceiver.EXTRA_JOB_ID, jobId)
        }
        return android.app.PendingIntent.getBroadcast(
            context,
            ("backup-cancel-$jobId").hashCode(),
            cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
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
