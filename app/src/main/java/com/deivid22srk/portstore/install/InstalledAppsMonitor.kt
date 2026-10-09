package com.deivid22srk.portstore.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de instalação de um jogo do catálogo no aparelho. */
sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Installed(val versionName: String?, val versionCode: Long) : InstallState
    data class UpdateAvailable(val installedVersion: String?, val latestVersion: String?) : InstallState
    data object Unknown : InstallState
}

/**
 * Consulta PackageManager pelos pacotes do catálogo e reage a
 * instalar/desinstalar/atualizar (broadcast + refresh em ON_RESUME).
 */
class InstalledAppsMonitor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private val tracked = LinkedHashSet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            refresh()
        }
    }

    fun start() {
        scope.launch {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Registra/atualiza os pacotes que devem ser observados e re-verifica. */
    fun track(packages: Set<String>) {
        if (packages.isEmpty()) return
        scope.launch {
            var changed = false
            synchronized(tracked) {
                packages.forEach { if (tracked.add(it)) changed = true }
            }
            if (changed || _states.value.keys.intersect(packages).size != packages.size) refresh()
        }
    }

    /** Re-verifica todos os pacotes rastreados (chamado em ON_RESUME e em broadcasts). */
    fun refresh() {
        scope.launch {
            val snapshot = synchronized(tracked) { tracked.toSet() }
            if (snapshot.isEmpty()) return@launch
            val pm = context.packageManager
            val result = snapshot.associateWith { pkg -> checkPackage(pm, pkg) }
            _states.value = result
        }
    }

    fun stateFor(packages: List<String>): InstallState {
        val map = _states.value
        packages.forEach { pkg ->
            val s = map[pkg]
            if (s != null && s !is InstallState.NotInstalled) return s
        }
        return if (packages.isEmpty()) InstallState.Unknown else InstallState.NotInstalled
    }

    companion object {
        fun checkPackage(pm: PackageManager, pkg: String): InstallState {
            if (pkg.isBlank()) return InstallState.Unknown
            return try {
                val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                val versionName = info.versionName
                val code = if (Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                InstallState.Installed(versionName, code)
            } catch (_: PackageManager.NameNotFoundException) {
                InstallState.NotInstalled
            } catch (_: Exception) {
                InstallState.Unknown
            }
        }

        fun isInstalled(context: Context, pkg: String): Boolean =
            checkPackage(context.packageManager, pkg) is InstallState.Installed

        fun launchIntent(context: Context, pkg: String): Intent? =
            context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /**
         * Pacote declarado dentro de um APK baixado (verificação pós-instalação
         * via getPackageArchiveInfo). Retorna null se o arquivo não for válido.
         */
        fun archivePackageName(context: Context, path: String): String? = runCatching {
            val pm = context.packageManager
            val info: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, 0)
            }
            info?.packageName
        }.getOrNull()
    }
}
