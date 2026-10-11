package com.deivid22srk.portstore.backup

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Leitor do pacote ÚNICO `.tar.zst` em streaming puro: extração DIRETA da
 * entrada TAR para o destino final — nunca existe arquivo descomprimido
 * intermediário (nunca ocupa o dobro do espaço).
 *
 * - O ZstdInputStream decodifica frames em sequência (um frame por entrada,
 *   escritos pelo [PackageWriter]) e VALIDA o checksum XXH64 de cada frame;
 *   qualquer corrupção vira [IOException] ainda durante o streaming;
 * - [forEachEntry] pula as primeiras [skip] entradas (retomada da
 *   importação: só decodifica, não extrai de novo) reportando os bytes
 *   pulados em [onSkip] (o progresso continua fiel);
 * - ao final esgota o stream até o último byte para o último frame ser
 *   validado por completo.
 */
class PackageReader {

    /**
     * Consome o pacote inteiro chamando [handler] para cada entrada de
     * arquivo (o [InputStream] do handler é o próprio fluxo TAR — ler
     * exatamente `entry.size` bytes). [sequenceIndex] é a posição da
     * entrada na sequência (manifest.json = 0, dados = 1..N).
     *
     * @throws IOException quando o pacote está truncado/corrompido
     *   (checksum de frame inválido, TAR inválido etc.).
     */
    fun forEachEntry(
        source: InputStream,
        skip: Int = 0,
        onSkip: (bytesUseful: Long) -> Unit = {},
        handler: (sequenceIndex: Int, entry: TarArchiveEntry, data: InputStream) -> Unit,
    ) {
        val zstd = ZstdInputStream(source)
        val tar = TarArchiveInputStream(zstd, "UTF-8")
        try {
            var sequenceIndex = 0
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                if (!tar.canReadEntryData(entry)) continue // headers pax/globais
                if (sequenceIndex < skip) {
                    drainEntry(tar, entry.size)
                    onSkip(entry.size)
                    sequenceIndex++
                    continue
                }
                handler(sequenceIndex, entry, tar)
                sequenceIndex++
            }
            // Esgota o restante do stream (bytes finais comprimidos que o TAR
            // já não lê) para o ÚLTIMO frame ser validado por completo.
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = zstd.read(buffer)
                if (read < 0) break
            }
        } finally {
            runCatching { tar.close() } // fecha zstd -> source (SAF)
        }
    }

    private fun drainEntry(tar: TarArchiveInputStream, size: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = size
        while (remaining > 0) {
            val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val read = tar.read(buffer, 0, want)
            if (read < 0) break
            remaining -= read
        }
    }

    companion object {
        /**
         * Lê SOMENTE a primeira entrada (manifest.json) do pacote — barato:
         * decodifica só o primeiro frame. Lança [BackupException]
         * (MANIFEST_INVALID) se o arquivo não for um pacote do Port Store.
         */
        fun readManifest(source: InputStream): BackupManifest {
            val zstd = ZstdInputStream(source)
            val tar = TarArchiveInputStream(zstd, "UTF-8")
            try {
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isDirectory) continue
                    if (!tar.canReadEntryData(entry)) continue
                    if (entry.name != MANIFEST_ENTRY_NAME) {
                        throw BackupException(
                            BackupErrorKind.MANIFEST_INVALID,
                            "não é um pacote do Port Store (primeira entrada: ${entry.name})",
                        )
                    }
                    val text = readFully(tar, entry.size).toString(Charsets.UTF_8)
                    return runCatching { BackupManifest.decode(text) }.getOrElse {
                        throw BackupException(
                            BackupErrorKind.MANIFEST_INVALID,
                            "manifest corrompido ou em formato incompatível",
                        )
                    }
                }
                throw BackupException(BackupErrorKind.MANIFEST_INVALID, "pacote vazio")
            } catch (e: BackupException) {
                throw e
            } catch (e: IOException) {
                throw BackupException(
                    BackupErrorKind.MANIFEST_INVALID,
                    "não é um pacote válido do Port Store (arquivo truncado ou corrompido)",
                )
            } finally {
                runCatching { tar.close() }
            }
        }

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

        private fun readFully(input: InputStream, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(if (size in 1..(8 * 1024 * 1024)) size.toInt() else 8192)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
                val read = input.read(buffer, 0, want)
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }
    }
}
