package com.deivid22srk.portstore.backup

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Escritor do pacote ÚNICO `.tar.zst` em streaming puro — nunca materializa
 * o TAR em disco.
 *
 * ```
 * OutputStream (SAF) <- CountingDigest <- FrameSwitchStream(zstd) <- TarArchiveOutputStream
 * ```
 *
 * - SHA-256 do pacote completo e contagem de bytes comprimidos calculados
 *   NO MESMO stream da escrita (nada é relido no final);
 * - um FRAME Zstandard por entrada (nível próprio por arquivo, checksum
 *   XXH64 habilitado): cada [commitEntry] fecha o frame atual e cria uma
 *   fronteira de retomada — [bytesWritten] vira [JobState.committedBytes];
 * - o marcador de fim do TAR só é escrito em [finish] (encerramento limpo).
 */
class PackageWriter(sink: OutputStream) {

    private val counting = CountingDigest(sink)
    private val frames = FrameSwitchStream(counting)
    private val tar = TarArchiveOutputStream(frames, TAR_BLOCK_SIZE, "UTF-8").apply {
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
    }

    /** Bytes comprimidos já escritos no arquivo (offset estável p/ retomada). */
    val bytesWritten: Long
        get() = counting.count

    /**
     * Escreve o arquivo [tarPath] dentro do TAR, copiando de [source] com
     * buffer fixo. Retorna os bytes úteis copiados. [onWritten] é chamado
     * com cada bloco lido (progresso de bytes úteis).
     */
    fun addFile(tarPath: String, size: Long, level: Int, source: InputStream, onWritten: (Int) -> Unit): Long {
        frames.startFrame(level)
        val entry = TarArchiveEntry(tarPath)
        entry.setSize(size)
        tar.putArchiveEntry(entry)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            tar.write(buffer, 0, read)
            onWritten(read)
            copied += read
        }
        tar.closeArchiveEntry()
        return copied
    }

    /** Variante para conteúdo gerado em memória (manifest.json, registry.json). */
    fun addBytes(tarPath: String, level: Int, data: ByteArray, onWritten: (Int) -> Unit = {}) {
        frames.startFrame(level)
        val entry = TarArchiveEntry(tarPath)
        entry.setSize(data.size.toLong())
        tar.putArchiveEntry(entry)
        tar.write(data)
        onWritten(data.size)
        tar.closeArchiveEntry()
    }

    /**
     * Fecha o frame zstd da entrada atual — fronteira de retomada. O caller
     * chama após cada [addFile]/[addBytes] e persiste [bytesWritten] como
     * [JobState.committedBytes].
     */
    fun commitEntry() {
        frames.closeFrame()
    }

    /** Encerramento limpo: marcador de fim do TAR + fecha o último frame. */
    fun finish(): String {
        // O marcador de fim do TAR (2 blocos nulos) precisa de um frame
        // aberto — o último commitEntry já fechou o frame do último arquivo.
        frames.startFrame(LEVEL_STORED)
        try {
            tar.finish()
        } finally {
            frames.closeFrame()
        }
        return counting.digest()
    }

    /** Descarta o estado aberto (erro/cancelamento) SEM fechar o sink. */
    fun abort() {
        runCatching { frames.closeFrame() }
    }

    /** Fecha o sink (arquivo no SAF). Chamar após [finish] ou [abort]. */
    fun closeSink() {
        runCatching { counting.close() }
    }

    private companion object {
        const val TAR_BLOCK_SIZE = 10240
    }
}

/**
 * Conta bytes e calcula SHA-256 dos bytes comprimidos que passam para o
 * stream final (o arquivo único no SAF) — no mesmo stream da escrita.
 */
class CountingDigest(private val sink: OutputStream) : OutputStream() {

    private val md = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(b: Int) {
        sink.write(b)
        md.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        md.update(b, off, len)
        count += len
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    fun digest(): String = md.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}

/**
 * Fecha frames Zstandard SEM fechar o stream de baixo (o ZstdOutputStream
 * fecha o underlying no close — o wrapper impede; quem fecha o arquivo é o
 * dono do sink). Cada frame carrega checksum XXH64 (validado na leitura).
 */
class FrameSwitchStream(private val sink: OutputStream) : OutputStream() {

    private var frame: ZstdOutputStream? = null

    fun startFrame(level: Int) {
        closeFrame()
        frame = ZstdOutputStream(NonClosing(sink), level).apply { setChecksum(true) }
    }

    fun closeFrame() {
        frame?.close()
        frame = null
    }

    override fun write(b: Int) {
        frame?.write(b) ?: error("frame zstd não aberto")
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        frame?.write(b, off, len) ?: error("frame zstd não aberto")
    }

    override fun flush() {
        frame?.flush()
    }

    override fun close() = closeFrame()
}

private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = Unit
}

fun sha256Hex(bytes: ByteArray): String =
    bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
