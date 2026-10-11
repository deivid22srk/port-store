package com.deivid22srk.portstore.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.deivid22srk.portstore.AppGraph
import java.io.FileInputStream
import java.time.OffsetDateTime

/**
 * Exportação dos dados de um jogo virtual, em streaming puro:
 *
 * `fontes do Bcore -> TAR (streaming) -> zstd -> partes via SAF -> manifest.json`
 *
 * Regras principais:
 * - NUNCA materializa o TAR completo em disco (buffer fixo de 256 KB);
 * - hash SHA-256 de cada parte calculado no mesmo stream da escrita;
 * - corte de partes sempre na fronteira de entrada TAR (parte extraível
 *   sozinha, retomada por parte);
 * - cancelamento cooperativo apaga as partes geradas (nada fica pela metade);
 * - estado persistido ([JobState]) permite retomada após o app fechar.
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
        )
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
        val registry = virtual.registryEntryFor(state.packageName)
        val plan = GameSources.collect(
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
        )
        if (plan.isEmpty && state.completedParts.isEmpty()) {
            throw BackupException(BackupErrorKind.NOTHING_TO_EXPORT, "nenhum arquivo para as categorias escolhidas")
        }

        val dir = openPackageDir(state) ?: throw BackupException(
            BackupErrorKind.IO_FAILURE,
            "não foi possível abrir a pasta do pacote (permissão do SAF expirada?)",
        )

        val free = freeSpaceBestEffort(Uri.parse(state.treeUri))
        if (free != Long.MAX_VALUE && free < plan.totalBytes) {
            throw BackupException(
                BackupErrorKind.INSUFFICIENT_SPACE,
                "espaço livre insuficiente no destino",
                plan.totalBytes,
            )
        }

        val total = plan.totalBytes
        var bytesDone = state.completedParts.sumOf { it.sizeExtracted }
        val parts = state.completedParts.toMutableList()
        var extractedThisPart = 0L

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
                    speedBps = 0,
                    etaSec = 0,
                    currentFile = currentFile,
                    partsDone = parts.size,
                    partsTotal = parts.size.coerceAtLeast(1),
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
                        partsDone = parts.size,
                        partsTotal = parts.size.coerceAtLeast(1),
                    ),
                )
            }
        }

        publish(BackupPhase.PREPARING, null)

        val writer = ChunkWriter(
            openPartStream = { index ->
                val name = partFileName(state.prefix, index)
                // Se uma retomada recria a parte, apague a anterior primeiro
                // (SAF criaria "nome (1).tar.zst").
                dir.findFile(name)?.delete()
                val file = dir.createFile("application/octet-stream", name)
                    ?: throw BackupException(BackupErrorKind.IO_FAILURE, "createFile falhou: $name")
                context.contentResolver.openOutputStream(file.uri)
                    ?: throw BackupException(BackupErrorKind.IO_FAILURE, "openOutputStream falhou: $name")
            },
            partSizeBytes = state.partSizeBytes,
            startIndex = state.completedParts.size,
        )

        try {
            val nextIndex = state.nextFileIndex.coerceIn(0, plan.entries.size)
            for (i in nextIndex until plan.entries.size) {
                if (canceled()) throw BackupException(BackupErrorKind.CANCELED, "cancelado pelo usuário")
                val entry = plan.entries[i]
                when {
                    entry.inlineBytes != null -> writer.addBytes(
                        entry.tarPath,
                        levelFor(entry.tarPath, state.compressionLevel),
                        entry.inlineBytes,
                    ) { _ -> },
                    entry.file != null -> FileInputStream(entry.file).use { input ->
                        writer.addFile(
                            entry.tarPath,
                            entry.bytes,
                            levelFor(entry.tarPath, state.compressionLevel),
                            input,
                        ) { written ->
                            bytesDone += written
                            extractedThisPart += written
                            publishThrottled(entry.tarPath)
                            if (canceled()) {
                                throw BackupException(BackupErrorKind.CANCELED, "cancelado em ${entry.tarPath}")
                            }
                        }
                    }
                    else -> continue
                }
                if (writer.shouldRollPart() && i < plan.entries.size - 1) {
                    closeAndRecord(writer, state, parts, extractedThisPart)
                    extractedThisPart = 0L
                    saveState(state, parts, i + 1)
                    publish(BackupPhase.RUNNING, null)
                }
            }
            // Fecha a última parte (null = já rolou/retomada completa).
            writer.closePart()?.let { last ->
                parts += CompletedPart(
                    index = last.index,
                    name = partFileName(state.prefix, last.index),
                    sha256 = last.sha256,
                    sizeCompressed = last.sizeCompressed,
                    sizeExtracted = extractedThisPart,
                )
            }
        } catch (t: Throwable) {
            // Erro: apaga SÓ a parte parcial (concluídas ficam para retomada).
            // Cancelamento: apaga TODAS as partes (limpeza completa).
            val cancelRequested = bus.isCancelRequested(state.jobId) || t is BackupException && t.kind == BackupErrorKind.CANCELED
            writer.abortPart { index ->
                runCatching { dir.findFile(partFileName(state.prefix, index))?.delete() }
            }
            if (cancelRequested) {
                deleteAllParts(dir, state.prefix)
                store.delete(state.jobId)
                bus.publish(bus.job(state.jobId)?.copy(phase = BackupPhase.CANCELED) ?: canceledUi(state))
            }
            throw t
        }

        writeManifest(dir, state, plan, parts)
        store.delete(state.jobId)
        publish(BackupPhase.DONE, null)
    }

    private fun canceledUi(state: JobState) = BackupJobUi(
        jobId = state.jobId,
        direction = BackupDirection.EXPORT,
        gameId = state.gameId,
        gameTitle = state.gameTitle,
        phase = BackupPhase.CANCELED,
    )

    private fun closeAndRecord(
        writer: ChunkWriter,
        state: JobState,
        parts: MutableList<CompletedPart>,
        extractedThisPart: Long,
    ) {
        val result = writer.closePart() ?: return
        parts += CompletedPart(
            index = result.index,
            name = partFileName(state.prefix, result.index),
            sha256 = result.sha256,
            sizeCompressed = result.sizeCompressed,
            sizeExtracted = extractedThisPart,
        )
    }

    private fun saveState(state: JobState, parts: List<CompletedPart>, nextFileIndex: Int) {
        store.save(
            state.copy(
                completedParts = parts.toList(),
                nextFileIndex = nextFileIndex,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun writeManifest(
        dir: DocumentFile,
        state: JobState,
        plan: ExportPlan,
        parts: List<CompletedPart>,
    ) {
        val manifest = BackupManifest(
            gameId = state.gameId,
            gameName = state.gameTitle,
            packageName = state.packageName,
            appVersion = state.versionName ?: "unknown",
            createdAt = OffsetDateTime.now().toString(),
            compressionLevel = state.compressionLevel,
            partSizeBytes = state.partSizeBytes,
            totalSizeCompressed = parts.sumOf { it.sizeCompressed },
            totalSizeExtracted = plan.totalBytes,
            parts = parts.map { BackupPart(it.name, it.sizeCompressed, it.sizeExtracted, it.sha256) },
            includes = plan.entries.map { it.category.id }.distinct(),
        )
        dir.findFile(MANIFEST_FILE_NAME)?.delete()
        val file = dir.createFile("application/json", MANIFEST_FILE_NAME)
            ?: throw BackupException(BackupErrorKind.IO_FAILURE, "não foi possível criar o manifest.json")
        (context.contentResolver.openOutputStream(file.uri) ?: throw BackupException(
            BackupErrorKind.IO_FAILURE,
            "não foi possível escrever o manifest.json",
        )).use { out ->
            out.write(BackupManifest.JSON.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun openPackageDir(state: JobState): DocumentFile? = runCatching {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(state.treeUri)) ?: return null
        if (!tree.canWrite()) return null
        tree.findFile(state.packageDirName) ?: tree.createDirectory(state.packageDirName)
    }.getOrNull()

    private fun deleteAllParts(dir: DocumentFile, prefix: String) {
        dir.listFiles().forEach { f ->
            if (f.name?.endsWith(PART_EXTENSION) == true && f.name?.startsWith("$prefix.part") == true) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     * Espaço livre do volume do tree URI (best-effort). SAF genérico não
     * expõe espaço — resolvemos o volume pelo caminho do tree (primary e
     * cartões). Retorna Long.MAX_VALUE quando não dá para saber.
     */
    private fun freeSpaceBestEffort(treeUri: Uri): Long = runCatching {
        val path = treeUri.path ?: return Long.MAX_VALUE
        val body = path.removePrefix("/tree/")
        val volume = body.substringBefore(':')
        val root = when {
            volume == "primary" -> "/storage/emulated/0"
            volume.isNotBlank() -> "/storage/$volume"
            else -> return Long.MAX_VALUE
        }
        StatFs(root).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

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

/** Abre OutputStream de uma DocumentFile — helper comum a export/import. */
internal fun OutputStream.writeAll(bytes: ByteArray) {
    write(bytes)
    flush()
}
