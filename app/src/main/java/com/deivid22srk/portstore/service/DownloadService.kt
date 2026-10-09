package com.deivid22srk.portstore.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.core.DlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service (dataSync) que mantém os downloads vivos com o app
 * fechado e publica as notificações persistentes de progresso.
 */
class DownloadService : Service() {

    companion object {
        const val GROUP_KEY = "com.deivid22srk.portstore.DOWNLOADS"
        const val ACTION_PAUSE = "com.deivid22srk.portstore.PAUSE"
        const val ACTION_RESUME = "com.deivid22srk.portstore.RESUME"
        const val ACTION_CANCEL = "com.deivid22srk.portstore.CANCEL"
        const val EXTRA_ID = "id"

        fun ensureStarted(context: Context) {
            val items = runCatching { AppGraph.downloads.items.value }.getOrDefault(emptyList())
            val hasActive = items.any { it.isActive }
            if (hasActive) {
                val intent = Intent(context, DownloadService::class.java)
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        observeDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_PAUSE -> id?.let { AppGraph.downloads.pause(it) }
            ACTION_RESUME -> id?.let { AppGraph.downloads.resume(it) }
            ACTION_CANCEL -> id?.let { AppGraph.downloads.cancel(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val summary = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Port Store")
            .setContentText("Gerenciando downloads…")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            Notifications.SUMMARY_ID,
            summary,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun observeDownloads() {
        scope.launch {
            AppGraph.downloads.items.collectLatest { items ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val active = items.filter { it.isActive }
                val finishedRecently = items.filter { it.isCompleted || it.isFailed }

                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                for (item in active) {
                    nm.notify(item.id.hashCode(), Notifications.progressNotification(this@DownloadService, item))
                }
                nm.notify(Notifications.SUMMARY_ID, Notifications.summaryNotification(this@DownloadService, active))

                updateLocks(active.isNotEmpty())
            }
        }

        // Notificações finais (canal separado) quando um download termina.
        scope.launch {
            val notified = mutableSetOf<String>()
            AppGraph.downloads.items.collectLatest { items ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                for (item in items) {
                    when (item.state) {
                        DlState.COMPLETED -> if (notified.add(item.id)) {
                            nm.notify(("done" + item.id).hashCode(), Notifications.doneNotification(this@DownloadService, item, true))
                        }
                        DlState.FAILED -> if (notified.add(item.id)) {
                            nm.notify(("done" + item.id).hashCode(), Notifications.doneNotification(this@DownloadService, item, false))
                        }
                    }
                }
            }
        }
    }

    private fun updateLocks(active: Boolean) {
        if (active) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portstore:downloads").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "portstore:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }
}
