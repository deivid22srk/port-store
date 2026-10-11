package com.deivid22srk.portstore.backup

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Resultado de uma parte fechada (vira entrada do manifest; nome = partFileName(prefix, index)). */
data class PartResult(
    val index: Int,
    val sha256: String,
    val sizeCompressed: Long,
)

/**
 * Escritor de partes `.tar.zst` em streaming puro — nunca materializa o TAR
 * completo em disco.
 *
 * Estrutura por parte (um frame Zstandard por parte):
 * ```
 * SAF OutputStream <- CountingDigest <- FrameSwitchStream(zstd) <- TarArchiveOutputStream
 * ```
 * O hash SHA-256 e a contagem de bytes comprimidos são calculados NO MESMO
 * stream da escrita (nada é relido no final).
 *
 * As partes são cortadas sempre na fronteira de uma entrada TAR: o caller
 * consulta [shouldRollPart] ENTRE arquivos e chama [closePart]/[startPart].
 * Cada parte termina com o marcador de fim do TAR, então pode ser extraída
 * sozinha (retomada por parte).
 *
 * O frame Zstandard é trocado por arquivo ([startFrame] antes de cada
 * entrada): arquivos já comprimidos (.mp4/.zip/.obb...) recebem nível
 * mínimo (quase "store"), economizando tempo sem ganho relevante de tamanho.
 */
class ChunkWriter(
    /** Abre o stream de escrita da parte N (1-based) — via SAF. */
    private val openPartStream: (Int) -> OutputStream,
    /** Tamanho alvo da parte (soft — a parte pode passar se o arquivo for maior). */
    private val partSizeBytes: Long,
    /** Índice inicial (partes já concluídas em retomada). */
    startIndex: Int = 0,
) {

    /** Índice da parte atual (1-based; 0 = nenhuma aberta). */
    var partIndex = startIndex
        private set

    private var sink: OutputStream? = null
    private var counting: CountingDigest? = null
    private var frames: FrameSwitchStream? = null
    private var tar: TarArchiveOutputStream? = null

    /** Bytes comprimidos já escritos na parte atual. */
    val currentPartCompressedBytes: Long
        get() = counting?.count ?: 0L

    /** A parte atual já atingiu o tamanho alvo (decidir rolar entre arquivos). */
    fun shouldRollPart(): Boolean = currentPartCompressedBytes >= partSizeBytes

    /** Abre uma nova parte (fecha a anterior se estiver aberta). */
    fun startPart() {
        closePart()
        partIndex += 1
        sink = openPartStream(partIndex)
        counting = CountingDigest(sink!!)
        frames = FrameSwitchStream(counting!!)
        tar = TarArchiveOutputStream(frames, TAR_BLOCK_SIZE, "UTF-8").apply {
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }
    }

    /**
     * Escreve o arquivo [tarPath] dentro do TAR da parte atual, copiando de
     * [source] com buffer fixo. Retorna os bytes úteis copiados.
     * [onWritten] é chamado com cada bloco lido (progresso de bytes úteis).
     */
    fun addFile(tarPath: String, size: Long, level: Int, source: InputStream, onWritten: (Int) -> Unit): Long {
        ensureOpen()
        frames!!.startFrame(level)
        val entry = TarArchiveEntry(tarPath)
        entry.setSize(size)
        entry.modTime = System.currentTimeMillis()
        tar!!.putArchiveEntry(entry)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            tar!!.write(buffer, 0, read)
            onWritten(read)
            copied += read
        }
        tar!!.closeArchiveEntry()
        return copied
    }

    /** Variante para conteúdo gerado em memória (ex.: registry.json do launcher). */
    fun addBytes(tarPath: String, level: Int, data: ByteArray, onWritten: (Int) -> Unit) {
        ensureOpen()
        frames!!.startFrame(level)
        val entry = TarArchiveEntry(tarPath)
        entry.setSize(data.size.toLong())
        entry.modTime = System.currentTimeMillis()
        tar!!.putArchiveEntry(entry)
        tar!!.write(data)
        onWritten(data.size)
        tar!!.closeArchiveEntry()
    }

    /**
     * Fecha a parte atual (marcador de fim do TAR + fim do frame zstd +
     * hash/contagem finais). Retorna null se nenhuma parte estava aberta.
     */
    fun closePart(): PartResult? {
        val activeTar = tar ?: return null
        try {
            activeTar.finish() // marcador de fim do TAR — vai no frame aberto
        } finally {
            frames?.closeFrame() // termina o frame zstd, flusha hash/contagem
        }
        val digest = counting!!.digest()
        val size = counting!!.count
        val index = partIndex
        tar = null
        frames = null
        counting = null
        val stream = sink
        sink = null
        stream?.close()
        return PartResult(index = index, sha256 = digest, sizeCompressed = size)
    }

    private fun ensureOpen() {
        if (tar == null) startPart()
    }

    /**
     * Descarta a parte aberta no meio (erro/cancelamento): fecha streams e
     * APAGA o arquivo parcial via [deletePart]. O job morre aqui — uma
     * retomada cria um writer novo começando em startIndex.
     */
    fun abortPart(deletePart: (Int) -> Unit) {
        val hadPart = tar != null || sink != null
        runCatching { frames?.closeFrame() }
        tar = null
        frames = null
        counting = null
        val stream = sink
        sink = null
        runCatching { stream?.close() }
        if (hadPart && partIndex > 0) {
            val index = partIndex
            partIndex = 0
            runCatching { deletePart(index) }
        }
    }

    private companion object {
        const val TAR_BLOCK_SIZE = 10240
    }
}

/**
 * Conta bytes e calcula SHA-256 dos bytes comprimidos que passam para o
 * stream final (a parte no SAF) — no mesmo stream da escrita.
 */
private class CountingDigest(private val sink: OutputStream) : OutputStream() {

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
 * [ChunkWriter], no fim da parte).
 */
private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = Unit
}

/**
 * Fluxo de múltiplos frames Zstandard: cada arquivo do TAR é um frame com
 * nível próprio. Na leitura, o ZstdInputStream decodifica frames em
 * sequência (comportamento validado por probe do zstd-jni 1.5.7-3).
 */
private class FrameSwitchStream(private val sink: OutputStream) : OutputStream() {

    private var frame: ZstdOutputStream? = null

    fun startFrame(level: Int) {
        closeFrame()
        frame = ZstdOutputStream(NonClosing(sink), level)
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

fun sha256Hex(bytes: ByteArray): String =
    bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
