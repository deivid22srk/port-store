package com.deivid22srk.portstore.virtual

import android.content.Context
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
     * arquivo não puder ser lido. [onProgress] recebe (bytesLidos, bytesTotais)
     * a cada bloco — usado pela UI de progresso da instalação virtual.
     */
    fun sha256(file: File, onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }): String? = runCatching {
        val total = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        var readTotal = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                readTotal += read
                onProgress(readTotal, total)
            }
        }
        onProgress(total, total)
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
     * descrevendo as arquiteturas nativas dele.
     *
     * Semântica idêntica à do PackageManager: o APK é instalável quando NÃO
     * tem código nativo, ou quando PELO MENOS UM dos ABIs embutidos é
     * suportado pelo aparelho — diretórios extras (ex.: lib/x86 num APK
     * universal arm+x86) são ignorados pelo Android. Exigir que TODOS os
     * ABIs fossem suportados rejeitaria APKs universais perfeitamente
     * instaláveis.
     */
    fun checkArch(file: File): String? {
        val abis = apkAbis(file)
        if (abis.isEmpty()) return null
        val supported = Build.SUPPORTED_ABIS.toSet()
        val hasCompatible = abis.any { it in supported }
        return if (hasCompatible) null else abis.joinToString(", ")
    }

    /**
     * Nome do pacote declarado no APK do arquivo, via PackageManager
     * (getPackageArchiveInfo). Null quando o arquivo não é um APK válido.
     */
    fun archivePackage(context: Context, file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
}
