package com.deivid22srk.portstore.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.deivid22srk.portstore.AppGraph
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.OffsetDateTime

/**
 * Exportação dos dados de um jogo virtual para UM ÚNICO ARQUIVO, em
 * streaming puro:
 *
 * `fontes do Bcore -> TAR (streaming) -> gzip (membro por entrada) -> arquivo único via SAF`
 *
 * Regras principais:
 * - o manifest.json vai DENTRO do pacote (primeira entrada do TAR) — o
 *   pacote continua sendo um único arquivo compartilhável;
 * - NUNCA materializa o TAR completo em disco (buffer fixo de 256 KB);
 * - SHA-256 do pacote calculado no mesmo stream da escrita;
 * - retomada: o arquivo é truncado no offset da última entrada commitada
 *   ([JobState.committedBytes]) e os frames continuam dali;
 * - cancelamento apaga o arquivo (nada fica pela metade);
 * - progresso publicado ANTES da varredura de arquivos (o card/notificação
 *   nunca ficam "Preparando…" sem resposta).
 */
class BackupManager(private val context: Context) {

    private val store = JobStateStore(context)

    /** Plano de exportação para o diálogo (tamanhos por categoria, versão). */
    fun previewExport(gameId: String): ExportPlan {
        val virtual = AppGraph.virtual
        if (!virtual.engineReady) {
            throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "motor virtual não está pronto")
        }
        val packageName = virtual.packageNameFor(gameId)
            ?: throw BackupException(BackupErrorKind.NOTHING_TO_EXPORT, "jogo não é virtual: $gameId")
        val paths = virtual.gamePaths(packageName)
            ?: throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "paths indisponíveis")
        val info = virtual.infoFor(packageName)
        val registry = virtual.registryEntryFor(packageName)
        return GameSources.collect(
            gameId = gameId,
            gameTitle = gameId, // título real resolvido pela UI (catálogo)
            packageName = packageName,
            versionName = info?.versionName,
            paths = paths,
            registryInlineJson = GameSources.registryJson(
                gameId,
                packageName,
                registry?.apkSizeBytes ?: info?.apkSizeBytes ?: 0L,
                registry?.installedAt ?: 0L,
            ),
            categories = BackupCategory.entries.toSet(),
        ).also { GameSources.remember(it) }
    }

    /**
     * Executa (ou retoma) a exportação descrita por [state]. [canceled] é o
     * cancelamento cooperativo (flag do bus OU worker parado). Publica
     * progresso no [BackupProgressBus].
     */
    fun export(state: JobState, canceled: () -> Boolean) {
        val bus = BackupProgressBus
        if (canceled()) throw BackupException(BackupErrorKind.CANCELED, "cancelado antes de começar")

        val virtual = AppGraph.virtual
        if (!virtual.engineReady) {
            throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "motor virtual não está pronto")
        }
        val paths = virtual.gamePaths(state.packageName)
            ?: throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "paths indisponíveis")

        // Sinaliza "trabalhando" ANTES de qualquer varredura (a coleta pode
        // demorar em jogos com dezenas de milhares de arquivos).
        bus.publish(preparingUi(state, bytesTotal = state.totalBytes))

        val registry = virtual.registryEntryFor(state.packageName)
        val plan = GameSources.recall(state.gameId, state.categories.mapNotNull(BackupCategory::fromId).toSet())
            ?: GameSources.collect(
                gameId = state.gameId,
                gameTitle = state.gameTitle,
                packageName = state.packageName,
                versionName = state.versionName,
                paths = paths,
                registryInlineJson = GameSources.registryJson(
                    state.gameId,
                    state.packageName,
                    registry?.apkSizeBytes ?: 0L,
                    registry?.installedAt ?: 0L,
                ),
                categories = state.categories.mapNotNull(BackupCategory::fromId).toSet(),
            ).also { GameSources.remember(it) }
        if (plan.isEmpty && state.nextFileIndex == 0) {
            throw BackupException(BackupErrorKind.NOTHING_TO_EXPORT, "nenhum arquivo para as categorias escolhidas")
        }

        val docUri = Uri.parse(state.documentUri)
        val document = DocumentFile.fromSingleUri(context, docUri)
        if (document == null || !document.exists() || !document.canWrite()) {
            throw BackupException(
                BackupErrorKind.IO_FAILURE,
                "arquivo do pacote indisponível (recrie a exportação escolhendo o destino de novo)",
            )
        }

        val total = plan.totalBytes
        var bytesDone = 0L

        fun publish(phase: BackupPhase, currentFile: String?) {
            bus.publish(
                BackupJobUi(
                    jobId = state.jobId,
                    direction = BackupDirection.EXPORT,
                    gameId = state.gameId,
                    gameTitle = state.gameTitle,
                    phase = phase,
                    bytesDone = bytesDone,
                    bytesTotal = total,
                    currentFile = currentFile,
                ),
            )
        }

        val speed = SpeedTracker(bytesDone)
        var lastPublish = 0L
        fun publishThrottled(currentFile: String?) {
            val now = System.currentTimeMillis()
            if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                lastPublish = now
                val sample = speed.sample(bytesDone)
                bus.publish(
                    BackupJobUi(
                        jobId = state.jobId,
                        direction = BackupDirection.EXPORT,
                        gameId = state.gameId,
                        gameTitle = state.gameTitle,
                        phase = BackupPhase.RUNNING,
                        bytesDone = bytesDone,
                        bytesTotal = total,
                        speedBps = sample.speedBps,
                        etaSec = sample.etaSeconds(total),
                        currentFile = currentFile,
                    ),
                )
            }
        }

        val sink = openExportSink(state, document)
        // Retomada REAL = o truncate no offset commitado funcionou. Se caiu
        // no fallback (arquivo recriado), tudo começa do zero — inclusive o
        // manifest (entrada 0 da sequência).
        val resumed = sink.resumed
        val startPlanIndex: Int
        if (resumed) {
            startPlanIndex = (state.nextFileIndex - 1).coerceIn(0, plan.entries.size)
            bytesDone = plan.entries.take(startPlanIndex).sumOf { it.bytes }
        } else {
            startPlanIndex = 0
            bytesDone = 0L
        }
        val writer = PackageWriter(sink.stream)
        try {
            // Retomada: manifest (entrada 0) já está no arquivo — NÃO reescrever.
            if (!resumed) {
                val manifest = BackupManifest(
                    gameId = state.gameId,
                    gameName = state.gameTitle,
                    packageName = state.packageName,
                    appVersion = state.versionName ?: "unknown",
                    createdAt = OffsetDateTime.now().toString(),
                    compressionLevel = state.compressionLevel,
                    totalSizeExtracted = total,
                    fileCount = plan.entries.size,
                    includes = plan.entries.map { it.category.id }.distinct(),
                )
                writer.addBytes(
                    MANIFEST_ENTRY_NAME,
                    LEVEL_BALANCED,
                    BackupManifest.encode(manifest).toByteArray(Charsets.UTF_8),
                )
                writer.commitEntry()
                persist(state, nextFileIndex = 1, committedBytes = writer.bytesWritten, total = total)
            }

            for (i in startPlanIndex until plan.entries.size) {
                if (canceled()) throw BackupException(BackupErrorKind.CANCELED, "cancelado pelo usuário")
                val entry = plan.entries[i]
                when {
                    entry.inlineBytes != null -> writer.addBytes(
                        entry.tarPath,
                        levelFor(entry.tarPath, state.compressionLevel),
                        entry.inlineBytes,
                    )
                    entry.file != null -> FileInputStream(entry.file).use { input ->
                        writer.addFile(
                            entry.tarPath,
                            entry.bytes,
                            levelFor(entry.tarPath, state.compressionLevel),
                            input,
                        ) { written ->
                            bytesDone += written
                            publishThrottled(entry.tarPath)
                            if (canceled()) {
                                throw BackupException(BackupErrorKind.CANCELED, "cancelado em ${entry.tarPath}")
                            }
                        }
                    }
                    else -> continue
                }
                // Fronteira de retomada: frame fechado + offset persistido.
                writer.commitEntry()
                persist(state, nextFileIndex = i + 2, committedBytes = writer.bytesWritten, total = total)
            }

            val sha256 = writer.finish()
            writer.closeSink()
            store.delete(state.jobId)
            publish(BackupPhase.DONE, null)
        } catch (t: Throwable) {
            runCatching { writer.abort() }
            runCatching { writer.closeSink() }
            val cancelRequested = bus.isCancelRequested(state.jobId) ||
                t is BackupException && t.kind == BackupErrorKind.CANCELED
            if (cancelRequested) {
                // Cancelamento: apaga o arquivo (nada fica pela metade).
                runCatching { document.delete() }
                store.delete(state.jobId)
                bus.publish(
                    bus.job(state.jobId)?.copy(phase = BackupPhase.CANCELED) ?: canceledUi(state),
                )
            }
            // Erro comum: arquivo e estado FICAM (a retomada continua de onde parou).
            throw t
        }
    }

    private fun preparingUi(state: JobState, bytesTotal: Long) = BackupJobUi(
        jobId = state.jobId,
        direction = BackupDirection.EXPORT,
        gameId = state.gameId,
        gameTitle = state.gameTitle,
        phase = BackupPhase.PREPARING,
        bytesTotal = bytesTotal,
    )

    private fun canceledUi(state: JobState) = BackupJobUi(
        jobId = state.jobId,
        direction = BackupDirection.EXPORT,
        gameId = state.gameId,
        gameTitle = state.gameTitle,
        phase = BackupPhase.CANCELED,
    )

    /** Persiste o ponto de retomada (arquivo pequeno, escrita barata). */
    private fun persist(state: JobState, nextFileIndex: Int, committedBytes: Long, total: Long) {
        store.save(
            state.copy(
                nextFileIndex = nextFileIndex,
                committedBytes = committedBytes,
                totalBytes = total,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Stream de escrita + se a retomada pelo offset commitado funcionou. */
    private class ExportSink(val stream: OutputStream, val resumed: Boolean)

    /**
     * Abre o arquivo único para escrita. Em retomada usa um descritor rw +
     * ftruncate no offset commitado (remove o frame parcialmente escrito
     * quando o processo morreu no meio) — se não der (fornecedor de SAF sem
     * suporte), recomeça do zero truncando o arquivo.
     */
    private fun openExportSink(state: JobState, document: DocumentFile): ExportSink {
        val uri = Uri.parse(state.documentUri)
        if (state.committedBytes > 0L && state.nextFileIndex > 0) {
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: return@runCatching
                try {
                    val existing = document.length()
                    if (existing < state.committedBytes) return@runCatching // arquivo trocado: recomeça
                    android.system.Os.ftruncate(pfd.fileDescriptor, state.committedBytes)
                    android.system.Os.lseek(
                        pfd.fileDescriptor,
                        state.committedBytes,
                        android.system.OsConstants.SEEK_SET,
                    )
                    return ExportSink(TrackedFdStream(FileOutputStream(pfd.fileDescriptor), pfd), resumed = true)
                } catch (t: Throwable) {
                    runCatching { pfd.close() }
                    throw t
                }
            }
        }
        return ExportSink(
            context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(BackupErrorKind.IO_FAILURE, "openOutputStream falhou para o pacote"),
            resumed = false,
        )
    }

    /** Fecha o FileOutputStream e o ParcelFileDescriptor (sem dupla posse). */
    private class TrackedFdStream(
        private val stream: FileOutputStream,
        private val pfd: android.os.ParcelFileDescriptor,
    ) : OutputStream() {
        override fun write(b: Int) = stream.write(b)
        override fun write(b: ByteArray) = stream.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
        override fun flush() = stream.flush()
        override fun close() {
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 300L
    }
}

/** Amostragem simples de velocidade (janela entre publicações). */
class SpeedTracker(initialBytes: Long) {

    private var lastBytes = initialBytes
    private var lastAt = System.currentTimeMillis()
    var speedBps: Long = 0
        private set

    fun sample(currentBytes: Long): SpeedTracker {
        val now = System.currentTimeMillis()
        val dt = now - lastAt
        if (dt >= 250) {
            val delta = (currentBytes - lastBytes).coerceAtLeast(0)
            speedBps = delta * 1000 / dt
            lastBytes = currentBytes
            lastAt = now
        }
        return this
    }

    fun etaSeconds(total: Long): Long {
        if (speedBps <= 0) return 0
        val remaining = (total - lastBytes).coerceAtLeast(0)
        return remaining / speedBps
    }
}
