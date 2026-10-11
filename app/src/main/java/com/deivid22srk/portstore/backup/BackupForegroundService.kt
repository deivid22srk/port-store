package com.deivid22srk.portstore.backup

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço FOREGROUND do backup/importação de dados de jogos (tipo dataSync):
 *
 * - é iniciado no instante em que um job entra na fila (e de novo pelos
 *   workers, por garantia) e chama startForeground IMEDIATAMENTE com uma
 *   notificação "Preparando…" — dentro dos 5 s exigidos pelo sistema;
 * - observa o [BackupProgressBus] e atualiza a notificação com %, bytes,
 *   velocidade, tempo restante e a ação "Cancelar" — independentemente da
 *   saúde do worker (a notificação não morre com ele);
 * - encerra a si mesmo quando não há mais job ativo no bus.
 *
 * Sem ele, a notificação dependia do setForeground do WorkManager — que
 * falha silenciosamente em vários cenários (app em background no Android
 * 12+, restrições de fabricante) e deixava o job "invisível".
 */
class BackupForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Primeira notificação ANTES de qualquer outra coisa (regra do FGS).
        val notification = BackupNotifications.serviceProgress(this, null, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(BackupNotifications.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(BackupNotifications.SERVICE_NOTIFICATION_ID, notification)
        }
        scope.launch { observeJobs() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reentradas (novo job enfileirado) apenas confirmam o serviço vivo;
        // a observação do bus já está rodando.
        stopping = false
        return START_NOT_STICKY
    }

    private suspend fun observeJobs() {
        BackupProgressBus.jobs.collect { jobs ->
            val active = jobs.filter { it.isActive }
            if (active.isEmpty()) {
                if (!stopping) {
                    stopping = true
                    stopSelf()
                }
                return@collect
            }
            stopping = false
            if (!BackupNotifications.canPost(this)) return@collect
            runCatching {
                val notification = BackupNotifications.serviceProgress(this, active.first(), active.size - 1)
                androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(BackupNotifications.SERVICE_NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BackupForegroundService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BackupForegroundService::class.java)) }
        }
    }
}
