package com.deivid22srk.portstore.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Resumo do pacote para o diálogo de confirmação da importação.
 */
data class ImportInspect(
    val manifest: BackupManifest,
    /** Tamanho do ARQUIVO do pacote (comprimido). */
    val packageSizeBytes: Long,
    val totalSizeExtracted: Long,
    val freeBytes: Long?,
    val problem: BackupErrorKind? = null,
) {
    val valid: Boolean get() = problem == null
}

/**
 * Importação de um pacote ÚNICO `.tar.gz` direto para o ambiente virtual —
 * streaming completo (`SAF -> gzip -> TAR -> destino`), SEM arquivo
 * descomprimido intermediário (nunca ocupa o dobro do espaço).
 *
 * - valida o manifest (embutido como primeira entrada do TAR) antes de começar;
 * - o CRC32 de cada membro GZIP é validado automaticamente durante
 *   a leitura — corrupção interrompe com erro claro;
 * - retomada por índice via [JobState]: entradas já extraídas são puladas
 *   (só decodificadas) ao reabrir o stream;
 * - o APK base vai para o cache do Bcore e o instalador oficial do Bcore
 *   (`installPackageAsUser`) recria app dir + libs + package.conf —
 *   dados existentes (saves) são preservados;
 * - opcional: backup dos saves atuais antes de substituir (gravado nos
 *   dados do app, `filesDir/backup_saves/`) e apagar o pacote ao concluir.
 */
class ImportManager(private val context: Context) {

    private val store = JobStateStore(context)

    // =====================================================================
    // Inspeção (diálogo de confirmação)
    // =====================================================================

    /** Lê o manifest embutido no arquivo escolhido (só o primeiro membro). */
    fun inspect(documentUri: Uri): ImportInspect {
        val document = DocumentFile.fromSingleUri(context, documentUri)
            ?: throw BackupException(BackupErrorKind.MANIFEST_INVALID, "arquivo inválido")
        if (!document.exists() || !document.canRead()) {
            throw BackupException(BackupErrorKind.MANIFEST_INVALID, "arquivo não acessível")
        }
        val input = context.contentResolver.openInputStream(documentUri)
            ?: throw BackupException(BackupErrorKind.MANIFEST_INVALID, "não foi possível abrir o arquivo")
        val manifest = input.use { PackageReader.readManifest(it) }
        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            throw BackupException(
                BackupErrorKind.MANIFEST_INVALID,
                "pacote mais novo que o app (formato ${manifest.formatVersion})",
            )
        }
        if (manifest.gameId.isBlank()) {
            throw BackupException(BackupErrorKind.MANIFEST_INVALID, "manifest sem gameId")
        }
        return ImportInspect(
            manifest = manifest,
            packageSizeBytes = document.length(),
            totalSizeExtracted = manifest.totalSizeExtracted,
            freeBytes = freeSpaceBestEffort(),
        )
    }

    // =====================================================================
    // Execução (worker)
    // =====================================================================

    /** Executa (ou retoma) a importação descrita por [state]. */
    fun import(state: JobState, canceled: () -> Boolean) {
        val bus = BackupProgressBus
        if (canceled()) throw BackupException(BackupErrorKind.CANCELED, "cancelado antes de começar")

        val docUri = Uri.parse(state.documentUri)
        val document = DocumentFile.fromSingleUri(context, docUri)
        if (document == null || !document.exists() || !document.canRead()) {
            throw BackupException(
                BackupErrorKind.MANIFEST_INVALID,
                "arquivo do pacote não acessível (permissão do SAF expirada? reescolha o pacote)",
            )
        }

        val virtual = AppGraph.virtual
        if (!virtual.engineReady) {
            throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "motor virtual não está pronto")
        }

        // Sinaliza "trabalhando" antes de qualquer leitura pesada.
        bus.publish(preparingUi(state))

        val resumed = state.nextFileIndex > 0
        // Manifest: fresh lê a PRIMEIRA entrada do arquivo (barato — só o 1º
        // membro); retomada reconstrói a partir do JobState (campos salvos na
        // fila pelo startImport e no 1º processamento).
        val manifest: BackupManifest = if (!resumed) {
            val first = context.contentResolver.openInputStream(docUri) ?: throw BackupException(
                BackupErrorKind.IO_FAILURE,
                "não foi possível abrir o arquivo do pacote",
            )
            first.use { PackageReader.readManifest(it) }
        } else {
            BackupManifest(
                gameId = state.gameId,
                gameName = state.gameTitle,
                packageName = state.packageName,
                appVersion = state.versionName ?: "unknown",
                createdAt = "",
                compressionLevel = state.compressionLevel,
                totalSizeExtracted = state.totalBytes,
                fileCount = state.fileCount,
                includes = state.categories,
            )
        }
        if (!resumed) {
            store.save(
                state.copy(
                    packageName = manifest.packageName.ifBlank { state.packageName },
                    totalBytes = manifest.totalSizeExtracted,
                    fileCount = manifest.fileCount,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        var bytesDone = 0L
        var totalAtEnd = state.totalBytes

        val input = context.contentResolver.openInputStream(docUri) ?: throw BackupException(
            BackupErrorKind.IO_FAILURE,
            "não foi possível abrir o arquivo do pacote",
        )
        input.use { stream ->
            val pkg = manifest.packageName.ifBlank { state.packageName }
            val paths = virtual.gamePaths(pkg)
                ?: throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "paths do ambiente virtual indisponíveis")

            val total = if (manifest.totalSizeExtracted > 0) manifest.totalSizeExtracted else state.totalBytes
            if (total <= 0) {
                throw BackupException(BackupErrorKind.MANIFEST_INVALID, "manifest sem tamanho total")
            }

            // Espaço antes de começar (destinos no sandbox interno + externo do app).
            val free = freeSpaceBestEffort()
            if (free != Long.MAX_VALUE && free < total) {
                throw BackupException(
                    BackupErrorKind.INSUFFICIENT_SPACE,
                    "espaço insuficiente para extrair ${FormattersCompat.humanBytes(total)} (livre: ${FormattersCompat.humanBytes(free)})",
                    total,
                )
            }

            // Backup dos saves atuais antes de substituir (rápido — saves são pequenos).
            if (state.backupSavesFirst && !resumed) {
                runCatching { exportSavesBackupInline(manifest) }
            }

            val speed = SpeedTracker(0L)
            var lastPublish = 0L
            fun publishThrottled(currentFile: String) {
                val now = System.currentTimeMillis()
                if (now - lastPublish >= 300) {
                    lastPublish = now
                    val sample = speed.sample(bytesDone)
                    bus.publish(
                        BackupJobUi(
                            jobId = state.jobId,
                            direction = BackupDirection.IMPORT,
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

            val pendingApk = paths.engineCacheDir?.let { File(it, state.pendingApkName ?: tempApkName(pkg)) }
            var registryBytes: ByteArray? = null
            var lastSaved = state.copy() // trabalho sobre uma cópia local persistível
            val reader = PackageReader()

            try {
                reader.forEachEntry(
                    stream,
                    skip = state.nextFileIndex,
                    onSkip = { useful ->
                        bytesDone += useful
                        publishThrottled("(retomando…)")
                    },
                ) { sequenceIndex, entry, data ->
                    if (canceled()) {
                        throw BackupException(BackupErrorKind.CANCELED, "cancelado em ${entry.name}")
                    }
                    val name = entry.name
                    when {
                        name == MANIFEST_ENTRY_NAME -> Unit // já lido no início

                        name == GameSources.TAR_REGISTRY -> {
                            registryBytes = data.readBytes()
                            bytesDone += entry.size
                        }

                        else -> {
                            val target = resolveDestination(paths, name, pendingApk)
                                ?: return@forEachEntry // entrada desconhecida: pula (formato evoluível)
                            val copied = PackageReader.copyEntry(data, entry.size, target)
                            bytesDone += copied
                        }
                    }
                    lastSaved = lastSaved.copy(
                        nextFileIndex = sequenceIndex + 1,
                        pendingApkName = pendingApk?.name,
                        registryPending = registryBytes != null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    store.save(lastSaved)
                    publishThrottled(name)
                }

                // ---- Finalização: registra o APK no ambiente virtual ----
                if (manifest.includesCategory(BackupCategory.INSTALL) && pendingApk != null && pendingApk.exists()) {
                    val status = virtual.installFromApk(state.gameId, pendingApk, expectedSha256 = null)
                    if (status is VirtualInstallStatus.Failure) {
                        throw BackupException(
                            BackupErrorKind.INSTALL_FAILED,
                            "o ambiente virtual recusou o APK: ${status.reason} ${status.detail.orEmpty()}",
                        )
                    }
                    runCatching { pendingApk.delete() }
                } else if (manifest.includesCategory(BackupCategory.LAUNCHER) && registryBytes != null) {
                    // Pacote só de dados/saves: garante o vínculo gameId <-> pacote.
                    runCatching {
                        val json = org.json.JSONObject(registryBytes!!.toString(Charsets.UTF_8))
                        virtual.registerVirtualLink(
                            gameId = manifest.gameId,
                            packageName = json.optString("packageName", pkg),
                            apkSizeBytes = json.optLong("apkSizeBytes", 0L),
                        )
                    }
                }
            } catch (t: Throwable) {
                // Erro/cancelamento: o que já foi extraído fica; a retomada
                // reabre o pacote e pula as entradas concluídas (índice salvo).
                if (t is BackupException && t.kind == BackupErrorKind.INSTALL_FAILED) {
                    runCatching { pendingApk?.delete() }
                }
                throw t
            }
            totalAtEnd = total
        }

        if (state.deletePackageAfterExtract) {
            runCatching { document.delete() }
        }
        store.delete(state.jobId)
        virtual.refresh()
        bus.publish(
            BackupJobUi(
                jobId = state.jobId,
                direction = BackupDirection.IMPORT,
                gameId = state.gameId,
                gameTitle = state.gameTitle,
                phase = BackupPhase.DONE,
                bytesDone = totalAtEnd,
                bytesTotal = totalAtEnd,
            ),
        )
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun preparingUi(state: JobState) = BackupJobUi(
        jobId = state.jobId,
        direction = BackupDirection.IMPORT,
        gameId = state.gameId,
        gameTitle = state.gameTitle,
        phase = BackupPhase.PREPARING,
        bytesTotal = state.totalBytes,
    )

    /**
     * Backup rápido dos saves/configurações ATUAIS (antes da importação
     * substituir), em UM ÚNICO arquivo dentro dos dados do app:
     * `filesDir/backup_saves/<prefixo>_saves_<data>.tar.gz` (com manifest
     * embutido, mesmo formato v3). Sem job/bus/notificação — roda dentro do
     * import. Best-effort: falha NÃO impede a importação.
     */
    private fun exportSavesBackupInline(manifest: BackupManifest) {
        val virtual = AppGraph.virtual
        val currentPkg = virtual.packageNameFor(manifest.gameId) ?: return
        val paths = virtual.gamePaths(currentPkg) ?: return
        val plan = GameSources.collect(
            gameId = manifest.gameId,
            gameTitle = manifest.gameName,
            packageName = currentPkg,
            versionName = virtual.infoFor(currentPkg)?.versionName,
            paths = paths,
            registryInlineJson = null,
            categories = setOf(BackupCategory.SAVES, BackupCategory.SETTINGS),
        )
        if (plan.isEmpty) return

        val dir = File(context.filesDir, "backup_saves").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now())
        val file = File(dir, "${slugFor(manifest.gameId)}_saves_$stamp${PACKAGE_EXTENSION}")

        FileOutputStream(file).use { out ->
            val writer = PackageWriter(out)
            try {
                val backupManifest = BackupManifest(
                    gameId = manifest.gameId,
                    gameName = manifest.gameName,
                    packageName = currentPkg,
                    appVersion = virtual.infoFor(currentPkg)?.versionName ?: "unknown",
                    createdAt = OffsetDateTime.now().toString(),
                    totalSizeExtracted = plan.totalBytes,
                    fileCount = plan.entries.size,
                    includes = listOf(BackupCategory.SAVES.id, BackupCategory.SETTINGS.id),
                )
                writer.addBytes(MANIFEST_ENTRY_NAME, LEVEL_BALANCED, BackupManifest.encode(backupManifest).toByteArray(Charsets.UTF_8))
                writer.commitEntry()
                plan.entries.forEach { entry ->
                    when {
                        entry.file != null -> java.io.FileInputStream(entry.file).use { input ->
                            writer.addFile(entry.tarPath, entry.bytes, levelFor(entry.tarPath, LEVEL_BALANCED), input)
                        }
                        entry.inlineBytes != null -> writer.addBytes(
                            entry.tarPath,
                            LEVEL_BALANCED,
                            entry.inlineBytes,
                        )
                    }
                    writer.commitEntry()
                }
                writer.finish()
            } catch (t: Throwable) {
                runCatching { writer.abort() }
                runCatching { file.delete() }
                // Backup de saves é best-effort: falha NÃO impede a importação.
                if (t is BackupException && t.kind == BackupErrorKind.CANCELED) throw t
            }
        }
    }

    private fun resolveDestination(paths: com.deivid22srk.portstore.virtual.VirtualGamePaths, tarPath: String, pendingApk: File?): File? =
        PackageDestinations.resolve(paths, tarPath, pendingApk)

    private fun tempApkName(pkg: String): String = "import_${pkg}_${System.currentTimeMillis()}.apk"

    /**
     * Espaço livre disponível para extrair: o MENOR entre o sandbox interno
     * (dados do Bcore) e o armazenamento externo do app (OBB/Android/data).
     * Long.MAX_VALUE quando não dá para determinar.
     */
    private fun freeSpaceBestEffort(): Long = runCatching {
        var free = Long.MAX_VALUE
        runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()?.let { free = it }
        context.getExternalFilesDir(null)?.let { ext ->
            runCatching { StatFs(ext.path).availableBytes }.getOrNull()?.let { extFree ->
                free = minOf(free, extFree)
            }
        }
        free
    }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        /** Alias para formatar bytes nas mensagens de erro. */
        object FormattersCompat {
            fun humanBytes(bytes: Long): String {
                if (bytes <= 0) return "0 MB"
                val mb = bytes / (1024.0 * 1024.0)
                return if (mb >= 1024.0) String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
                else String.format(java.util.Locale.ROOT, "%.0f MB", mb)
            }
        }
    }
}
