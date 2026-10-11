package com.deivid22srk.portstore.backup

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Leitor de partes `.tar.zst` em streaming puro: SHA-256 calculado DURANTE
 * a leitura (no fluxo comprimido) e extração DIRETA da entrada TAR para o
 * destino final — nunca existe arquivo descomprimido intermediário.
 *
 * O mesmo [source] alimenta o digest e o decoder Zstandard (frames em
 * sequência). Depois do fim do TAR o stream é esgotado até o último byte,
 * para o hash cobrir a parte inteira — sem reler o arquivo.
 */
class ChunkReader {

    /**
     * Consome a parte inteira chamando [handler] para cada entrada de
     * arquivo (o [InputStream] do handler é o próprio fluxo TAR — ler
     * exatamente `entry.size` bytes). Retorna o SHA-256 real da parte.
     *
     * @throws BackupException (HASH_MISMATCH) quando [expectedSha256] não confere.
     */
    fun forEachEntry(
        source: InputStream,
        expectedSha256: String?,
        handler: (entry: TarArchiveEntry, data: InputStream) -> Unit,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = DigestInputStream(source, md)
        val zstd = ZstdInputStream(digest)
        val tar = TarArchiveInputStream(zstd, "UTF-8")
        try {
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                if (!tar.canReadEntryData(entry)) continue // headers pax/globais
                handler(entry, tar)
            }
            // Esgota o restante do frame zstd (bytes finais comprimidos que o
            // TAR já não lê) para o digest cobrir TODA a parte.
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = zstd.read(buffer)
                if (read < 0) break
            }
        } finally {
            runCatching { tar.close() } // fecha zstd -> digest -> source (SAF)
        }
        val actual = sha256Hex(md.digest())
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            throw BackupException(
                BackupErrorKind.HASH_MISMATCH,
                "sha256 da parte difere do manifest (esperado=$expectedSha256, real=$actual)",
                actual,
            )
        }
        return actual
    }

    companion object {
        /** Copia a entrada TAR para [target] com buffer fixo. Retorna bytes copiados. */
        fun copyEntry(data: InputStream, size: Long, target: java.io.File): Long {
            target.parentFile?.mkdirs()
            var copied = 0L
            java.io.FileOutputStream(target).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (copied < size) {
                    val remaining = size - copied
                    val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
                    val read = data.read(buffer, 0, want)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                }
            }
            return copied
        }
    }
}
