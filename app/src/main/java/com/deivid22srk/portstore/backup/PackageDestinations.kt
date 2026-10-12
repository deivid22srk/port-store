package com.deivid22srk.portstore.backup

import com.deivid22srk.portstore.virtual.VirtualGamePaths
import java.io.File

/**
 * Mapeamento das entradas do TAR (formato v3) para o destino final no
 * ambiente virtual — COMPARTILHADO entre a importação manual (Modo
 * Desenvolvedor, via SAF) e o instalador do repositório de dados
 * (download automático pós-instalação do APK).
 *
 * Retorna null para entradas desconhecidas (formato evoluível — puladas
 * pelo leitor); caminhos inseguros (zip-slip) são barrados antes.
 */
object PackageDestinations {

    fun resolve(paths: VirtualGamePaths, tarPath: String, pendingApk: File?): File? {
        if (isUnsafePath(tarPath)) return null
        return when {
            tarPath == GameSources.TAR_APK -> pendingApk
                ?: throw BackupException(BackupErrorKind.IO_FAILURE, "cache do motor indisponível para o APK")
            tarPath.startsWith("${GameSources.TAR_OBB_PREFIX}/") -> joinUnder(paths.obbDir, tarPath, GameSources.TAR_OBB_PREFIX)
            tarPath.startsWith("${GameSources.TAR_EXTDATA_PREFIX}/") -> joinUnder(paths.externalDataDir, tarPath, GameSources.TAR_EXTDATA_PREFIX)
            tarPath.startsWith("${GameSources.TAR_DATA_PREFIX}/") -> joinUnder(paths.dataDir, tarPath, GameSources.TAR_DATA_PREFIX)
            tarPath.startsWith("${GameSources.TAR_SAVES_PREFIX}/") -> joinUnder(paths.databasesDir, tarPath, GameSources.TAR_SAVES_PREFIX)
            tarPath.startsWith("${GameSources.TAR_SETTINGS_PREFIX}/") -> joinUnder(paths.sharedPrefsDir, tarPath, GameSources.TAR_SETTINGS_PREFIX)
            else -> null
        }
    }

    /** Extrai o caminho relativo e junta sob a raiz (raiz null -> fora). */
    private fun joinUnder(root: File?, tarPath: String, prefix: String): File? {
        if (root == null) return null
        val rel = tarPath.removePrefix("$prefix/")
        return File(root, rel)
    }

    private fun isUnsafePath(path: String): Boolean {
        if (path.startsWith("/") || path.contains('\\')) return true
        return path.split('/').any { it.isEmpty() || it == "." || it == ".." }
    }
}
