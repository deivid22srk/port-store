package com.deivid22srk.portstore.virtual

import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Verificações de segurança e compatibilidade antes de instalar um APK no
 * ambiente virtual: integridade (SHA-256 do catálogo), arquitetura nativa
 * (32/64 bits) e proteção contra instalação recursiva do próprio host.
 */
object VirtualVerification {

    /**
     * Calcula o SHA-256 do arquivo de forma streaming. Retorna null se o
     * arquivo não puder ser lido.
     */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * Arquiteturas nativas embutidas no APK (entradas lib/<abi>/ dentro do zip).
     * APK sem código nativo retorna lista vazia (compatível com qualquer
     * aparelho).
     */
    fun apkAbis(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            val abis = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("lib/")) {
                    val abi = name.removePrefix("lib/").substringBefore('/')
                    if (abi.isNotEmpty()) abis += abi
                }
            }
            abis.toList()
        }
    }.getOrDefault(emptyList())

    /**
     * Retorna null quando o APK é compatível com o aparelho, ou uma string
     * descrevendo as arquiteturas faltantes.
     */
    fun checkArch(file: File): String? {
        val abis = apkAbis(file)
        if (abis.isEmpty()) return null
        val supported = Build.SUPPORTED_ABIS.toSet()
        val missing = abis.filter { it !in supported }
        return if (missing.isEmpty()) null else missing.joinToString(", ")
    }

    /**
     * Nome do pacote declarado no APK do arquivo, via PackageManager
     * (getPackageArchiveInfo). Null quando o arquivo não é um APK válido.
     */
    fun archivePackage(context: Context, file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
}
