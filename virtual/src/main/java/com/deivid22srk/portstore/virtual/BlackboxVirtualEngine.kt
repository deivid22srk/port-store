package com.deivid22srk.portstore.virtual

import android.content.Context
import android.os.Build
import com.deivid22srk.portstore.virtual.gex.GexEmbedding
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File

/**
 * Implementação da [VirtualEngine] sobre o Bcore (NewBlackbox, Apache-2.0).
 * Todas as chamadas são defensivas: qualquer exceção do motor vira status de
 * falha — o app hospedeiro nunca derruba junto.
 */
class BlackboxVirtualEngine(
    private val context: Context,
    private val registry: VirtualRegistry,
) : VirtualEngine {

    companion object {
        /** Cooldown do BlackManager para recriar serviço morto é 2s — esperamos além disso. */
        private const val RETRY_DELAY_MS = 2_600L
    }

    override val ready: Boolean
        get() = VirtualBootstrap.bootstrapped

    private fun core(): BlackBoxCore? = if (ready) runCatching { BlackBoxCore.get() }.getOrNull() else null

    override fun install(apk: File): VirtualInstallStatus {
        val core = core() ?: return VirtualInstallStatus.Failure(VirtualFailure.ENGINE_UNAVAILABLE)
        if (!apk.exists()) return VirtualInstallStatus.Failure(VirtualFailure.FILE_MISSING)

        // Proteção contra recursão: nunca instalar o próprio Port Store
        // dentro dele mesmo (travaria todo o ambiente virtual).
        val archive = VirtualVerification.archivePackage(context, apk)
            ?: return VirtualInstallStatus.Failure(VirtualFailure.FILE_MISSING, "APK inválido")
        if (archive.packageName == context.packageName) {
            return VirtualInstallStatus.Failure(VirtualFailure.HOST_PACKAGE)
        }

        val archError = VirtualVerification.checkArch(apk)
        if (archError != null) {
            return VirtualInstallStatus.Failure(VirtualFailure.ARCH_INCOMPATIBLE, archError)
        }

        val firstResult = runCatching { core.installPackageAsUser(apk, 0) }.getOrNull()
        if (firstResult != null && firstResult.success && !firstResult.packageName.isNullOrBlank()) {
            // O vínculo gameId<->pacote é registrado pelo VirtualManager,
            // que conhece o jogo do catálogo que originou a instalação.
            return VirtualInstallStatus.Success(firstResult.packageName)
        }

        // Sem resposta do servidor (o processo :black pode ter morrido entre
        // a criação do binder e a chamada — DeadObjectException). O
        // BlackManager só recria o serviço após o cooldown de 2s, e o
        // getService reinicia o processo sozinho. Esperamos esse intervalo e
        // tentamos UMA vez antes de reportar o erro ao usuário.
        val retriedResult = if (firstResult == null) {
            runCatching { Thread.sleep(RETRY_DELAY_MS) }
            runCatching { core.installPackageAsUser(apk, 0) }.getOrNull()
        } else {
            null
        }
        if (retriedResult != null && retriedResult.success && !retriedResult.packageName.isNullOrBlank()) {
            return VirtualInstallStatus.Success(retriedResult.packageName)
        }

        return VirtualInstallStatus.Failure(
            VirtualFailure.ENGINE_ERROR,
            firstResult?.msg,
        )
    }

    override fun uninstall(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching {
            core.uninstallPackageAsUser(packageName, 0)
            registry.remove(packageName)
            !core.isInstalled(packageName, 0)
        }.getOrDefault(false)
    }

    override fun clearData(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching {
            BlackBoxCore.getBPackageManager().clearPackage(packageName, 0)
            true
        }.getOrDefault(false)
    }

    override fun stop(packageName: String) {
        val core = core() ?: return
        runCatching { BlackBoxCore.getBPackageManager().stopPackage(packageName, 0) }
    }

    override fun isInstalled(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching { core.isInstalled(packageName, 0) }.getOrDefault(false)
    }

    override fun launch(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching { core.launchApk(packageName, 0) }.getOrDefault(false)
    }

    override fun launchEmbedded(packageName: String, displayId: Int, socketName: String): Boolean {
        val core = core() ?: return false
        return runCatching {
            val intent = BlackBoxCore.getBPackageManager()
                .getLaunchIntentForPackage(packageName, 0)
                ?: return@runCatching false
            // Extras que chegam ao processo do jogo (:pN) via GexEmbedding.
            intent.putExtra(GexEmbedding.EXTRA_DISPLAY_ID, displayId)
            intent.putExtra(GexEmbedding.EXTRA_SOCKET_NAME, socketName)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // A display viaja como ActivityOptions; o ActivityStack do motor
            // preserva o bundle até o startActivity real (mesmo na nova task).
            val options = android.app.ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            BlackBoxCore.getBActivityManager()
                .startActivityAms(0, intent, null, null, null, -1, intent.flags, options.toBundle()) == 0
        }.getOrDefault(false)
    }

    override fun listApps(): List<VirtualAppInfo> {
        val core = core() ?: return emptyList()
        return runCatching {
            core.getInstalledPackages(0, 0).orEmpty().mapNotNull { info ->
                toAppInfo(info)
            }
        }.getOrDefault(emptyList())
    }

    override fun packageInfo(packageName: String): VirtualAppInfo? {
        val core = core()
        return core?.let {
            runCatching {
                BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, 0)?.let { info ->
                    toAppInfo(info)
                }
            }.getOrNull()
        }
    }

    /**
     * O processo virtual do jogo está vivo? Base do selo "Em execução" do
     * Game Launcher: enquanto o processo existir (mesmo suspenso em segundo
     * plano após "Voltar ao início"), o jogo conta como em execução.
     */
    fun isProcessRunning(packageName: String): Boolean {
        if (!ready) return false
        return runCatching {
            BlackBoxCore.getBActivityManager()
                .getRunningAppProcesses(packageName, 0)
                ?.mAppProcessInfoList
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /**
     * Orientação preferida da activity principal do jogo (valor de
     * ActivityInfo.screenOrientation), para o Launcher preparar a rotação
     * ANTES da tela de carregamento — sem rotação visível no meio dela.
     */
    fun launchScreenOrientation(packageName: String): Int? {
        if (!ready) return null
        return runCatching {
            val info = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, 0)
            val activities = info?.activities
            when {
                activities.isNullOrEmpty() -> null
                else -> activities[0].screenOrientation
            }
        }.getOrNull()
    }

    /** APK base instalado no ambiente virtual (para "Instalar normalmente no Android"). */
    fun baseApkFile(packageName: String): File? = runCatching {
        BEnvironment.getBaseApkDir(packageName)?.takeIf { it.exists() }
    }.getOrNull()

    /** Espaço total ocupado pelo ambiente virtual (APKs + dados + config). */
    fun storageUsageBytes(): Long = directorySize(BEnvironment.getVirtualRoot())

    /** Espaço ocupado por um jogo (APK base + dados). */
    fun appUsageBytes(packageName: String): Long {
        val apk = BEnvironment.getBaseApkDir(packageName)
        val data = BEnvironment.getDataDir(packageName, 0)
        return apk.length() + directorySize(data)
    }

    private fun toAppInfo(info: android.content.pm.PackageInfo): VirtualAppInfo? {
        val pkg = info.packageName ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return VirtualAppInfo(
            packageName = pkg,
            versionName = info.versionName,
            versionCode = versionCode,
            installedAt = registry.entry(pkg)?.installedAt ?: 0L,
            apkSizeBytes = runCatching { BEnvironment.getBaseApkDir(pkg).length() }
                .getOrDefault(0L),
            dataBytes = runCatching {
                directorySize(BEnvironment.getDataDir(pkg, 0)).takeIf { it > 0 }
            }.getOrNull(),
        )
    }

    private fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
