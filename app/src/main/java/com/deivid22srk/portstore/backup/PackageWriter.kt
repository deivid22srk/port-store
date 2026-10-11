package com.deivid22srk.portstore.backup

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Escritor do pacote ÚNICO `.tar.gz` em streaming puro — nunca materializa
 * o TAR em disco.
 *
 * ```
 * OutputStream (SAF) <- CountingDigest <- GzipMemberSwitchStream <- TarArchiveOutputStream
 * ```
 *
 * - SHA-256 do pacote completo e contagem de bytes escritos calculados
 *   NO MESMO stream da escrita (nada é relido no final);
 * - um MEMBRO GZIP por entrada (nível próprio por arquivo, CRC32 nativo):
 *   cada [commitEntry] fecha o membro atual e cria uma fronteira de
 *   retomada — [bytesWritten] vira [JobState.committedBytes];
 * - membros concatenados formam um .tar.gz padrão (legível por qualquer
 *   ferramenta gzip/tar);
 * - o marcador de fim do TAR só é escrito em [finish] (encerramento limpo).
 */
class PackageWriter(sink: OutputStream) {

    private val counting = CountingDigest(sink)
    private val members = GzipMemberSwitchStream(counting)
    private val tar = TarArchiveOutputStream(members, TAR_BLOCK_SIZE, "UTF-8").apply {
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
    }

    /** Bytes já escritos no arquivo (offset estável p/ retomada). */
    val bytesWritten: Long
        get() = counting.count

    /**
     * Escreve o arquivo [tarPath] dentro do TAR, copiando de [source] com
     * buffer fixo. Retorna os bytes úteis copiados. [onWritten] é chamado
     * com cada bloco lido (progresso de bytes úteis).
     */
    fun addFile(tarPath: String, size: Long, level: Int, source: InputStream, onWritten: (Int) -> Unit = {}): Long {
        members.startMember(level)
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
        members.startMember(level)
        val entry = TarArchiveEntry(tarPath)
        entry.setSize(data.size.toLong())
        tar.putArchiveEntry(entry)
        tar.write(data)
        onWritten(data.size)
        tar.closeArchiveEntry()
    }

    /**
     * Fecha o membro GZIP da entrada atual — fronteira de retomada. O caller
     * chama após cada [addFile]/[addBytes] e persiste [bytesWritten] como
     * [JobState.committedBytes].
     */
    fun commitEntry() {
        members.closeMember()
    }

    /** Encerramento limpo: marcador de fim do TAR + fecha o último membro. */
    fun finish(): String {
        // O marcador de fim do TAR (2 blocos nulos) precisa de um membro
        // aberto — o último commitEntry já fechou o membro do último arquivo.
        members.startMember(LEVEL_STORED)
        try {
            tar.finish()
        } finally {
            members.closeMember()
        }
        return counting.digest()
    }

    /** Descarta o estado aberto (erro/cancelamento) SEM fechar o sink. */
    fun abort() {
        runCatching { members.closeMember() }
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
 * Fecha membros GZIP SEM fechar o stream de baixo (o GZIPOutputStream fecha
 * o underlying no close — o wrapper impede; quem fecha o arquivo é o dono do
 * sink). Cada membro carrega CRC32 próprio (validado na leitura).
 */
class GzipMemberSwitchStream(private val sink: OutputStream) : OutputStream() {

    private var member: GZIPOutputStream? = null

    fun startMember(level: Int) {
        closeMember()
        member = LeveledGzipStream(NonClosing(sink), GZIP_MEMBER_BUFFER_BYTES, level)
    }

    fun closeMember() {
        member?.close()
        member = null
    }

    override fun write(b: Int) {
        member?.write(b) ?: error("membro gzip não aberto")
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        member?.write(b, off, len) ?: error("membro gzip não aberto")
    }

    override fun flush() {
        member?.flush()
    }

    override fun close() = closeMember()

    private companion object {
        const val GZIP_MEMBER_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * GZIPOutputStream com nível de Deflater próprio (por membro). O nível é
 * definido ANTES do primeiro setInput — aplica-se a todo o membro.
 */
private class LeveledGzipStream(
    out: OutputStream,
    size: Int,
    level: Int,
) : GZIPOutputStream(out, size) {
    init {
        require(level in 0..9) { "nível de deflate inválido: $level" }
        def.setLevel(level)
    }
}

private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = Unit
}

fun sha256Hex(bytes: ByteArray): String =
    bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
