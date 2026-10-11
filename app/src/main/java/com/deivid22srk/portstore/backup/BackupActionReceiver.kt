package com.deivid22srk.portstore.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Cancelamento dos jobs de backup a partir da notificação. Seta a flag
 * cooperativa no [BackupProgressBus] (o worker interrompe com limpeza) e
 * cancela o trabalho no WorkManager.
 */
class BackupActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        BackupProgressBus.requestCancel(jobId)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(BackupJobs.workName(jobId)) }
    }

    companion object {
        const val ACTION_CANCEL = "com.deivid22srk.portstore.backup.ACTION_CANCEL"
        const val EXTRA_JOB_ID = "jobId"
    }
}
