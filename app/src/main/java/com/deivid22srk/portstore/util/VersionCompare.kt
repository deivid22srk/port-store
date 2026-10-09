package com.deivid22srk.portstore.util

/**
 * Comparação de versões tolerante: extrai números, ignora prefixos (v),
 * sufixos (-beta, -rc1). Retorna null quando não é possível comparar com
 * segurança (ex.: nenhuma das partes tem números).
 */
object VersionCompare {

    private val numberRegex = Regex("\\d+")

    /** Normaliza "v1.2.3-beta" -> "1.2.3". */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().removePrefix("v").removePrefix("V").removePrefix("r")
        val dash = s.indexOf('-')
        if (dash > 0) s = s.substring(0, dash)
        s = s.removePrefix(".")
        return s.takeIf { it.isNotBlank() }
    }

    /**
     * Positivo se [remote] é mais nova que [installed]; negativo se mais antiga;
     * 0 se iguais; null se incomparável.
     */
    fun compare(remote: String?, installed: String?): Int? {
        val a = normalize(remote)?.let { numberRegex.findAll(it).map { m -> m.value.toLongOrNull() ?: 0L }.toList() }
        val b = normalize(installed)?.let { numberRegex.findAll(it).map { m -> m.value.toLongOrNull() ?: 0L }.toList() }
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return null
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isUpdateAvailable(remote: String?, installed: String?): Boolean = compare(remote, installed) == 1
}
