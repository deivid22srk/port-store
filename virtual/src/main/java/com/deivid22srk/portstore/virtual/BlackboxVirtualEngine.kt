package com.deivid22srk.portstore.virtual

import android.content.Context
import android.os.Build
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

        return runCatching {
            val result = core.installPackageAsUser(apk, 0)
            if (result != null && result.success && !result.packageName.isNullOrBlank()) {
                // O vínculo gameId<->pacote é registrado pelo VirtualManager,
                // que conhece o jogo do catálogo que originou a instalação.
                VirtualInstallStatus.Success(result.packageName)
            } else {
                VirtualInstallStatus.Failure(
                    VirtualFailure.ENGINE_ERROR,
                    result?.msg,
                )
            }
        }.getOrElse {
            VirtualInstallStatus.Failure(VirtualFailure.ENGINE_ERROR, it.message)
        }
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
            core.getBPackageManager().clearPackage(packageName, 0)
            true
        }.getOrDefault(false)
    }

    override fun stop(packageName: String) {
        val core = core() ?: return
        runCatching { core.getBPackageManager().stopPackage(packageName, 0) }
    }

    override fun isInstalled(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching { core.isInstalled(packageName, 0) }.getOrDefault(false)
    }

    override fun launch(packageName: String): Boolean {
        val core = core() ?: return false
        return runCatching { core.launchApk(packageName, 0) }.getOrDefault(false)
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
        val core = core() ?: return null
        return runCatching {
            core.getBPackageManager().getPackageInfo(packageName, 0, 0)?.let { toAppInfo(it) }
        }.getOrNull()
    }

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
