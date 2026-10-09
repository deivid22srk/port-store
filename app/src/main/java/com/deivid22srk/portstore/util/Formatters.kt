package com.deivid22srk.portstore.util

import java.util.Locale

object Formatters {

    private val ptBR: Locale = Locale("pt", "BR")

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1.0) {
            val kb = bytes / 1024.0
            return String.format(ptBR, "%.0f KB", kb)
        }
        if (mb < 1024.0) return String.format(ptBR, "%.0f MB", mb)
        return String.format(ptBR, "%.2f GB", mb / 1024.0)
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return ""
        val mb = bytesPerSecond / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(ptBR, "%.1f MB/s", mb)
        } else {
            String.format(ptBR, "%.0f KB/s", bytesPerSecond / 1024.0)
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val s = seconds
        return when {
            s < 60 -> "${s}s restantes"
            s < 3600 -> {
                val m = s / 60
                if (m == 1L) "1 min restante" else "$m min restantes"
            }
            else -> {
                val h = s / 3600
                val m = (s % 3600) / 60
                if (m > 0) "${h}h ${m}min restantes" else "$h h restantes"
            }
        }
    }

    fun formatCount(value: Long): String = when {
        value >= 1_000_000 -> String.format(ptBR, "%.1f mi", value / 1_000_000.0)
        value >= 1_000 -> String.format(ptBR, "%.1f mil", value / 1_000.0)
        else -> value.toString()
    }
}
