package com.deivid22srk.portstore.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

/**
 * Resumo do pacote para o diálogo de confirmação da importação.
 */
data class ImportInspect(
    val manifest: BackupManifest,
    val presentParts: Int,
    val missingParts: List<String>,
    val totalSizeCompressed: Long,
    val totalSizeExtracted: Long,
    val freeBytes: Long?,
    val problem: BackupErrorKind? = null,
) {
    val valid: Boolean get() = problem == null && missingParts.isEmpty()
}

/**
 * Importação dos dados de um pacote `.tar.zst` direto para o ambiente
 * virtual — streaming completo (`SAF -> hash -> zstd -> TAR -> destino`),
 * SEM arquivo descomprimido intermediário (nunca ocupa o dobro do espaço).
 *
 * - valida manifest (versão, gameId, partes presentes) antes de começar;
 * - verifica o SHA-256 de cada parte durante a leitura (sem reler);
 * - retomada por parte via [JobState] (partes concluídas são puladas);
 * - o APK base vai para o cache do Bcore e o instalador oficial do Bcore
 *   (`installPackageAsUser`) recria app dir + libs + package.conf —
 *   dados existentes (saves) são preservados;
 * - opcional: backup dos saves atuais antes de substituir; apagar cada
 *   parte após extrair.
 */
class ImportManager(private val context: Context) {

    private val store = JobStateStore(context)

    // =====================================================================
    // Inspeção (diálogo de confirmação)
    // =====================================================================

    /** Lê e valida o manifest.json na raiz da pasta escolhida. */
    fun inspect(treeUri: Uri): ImportInspect {
        val dir = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw BackupException(BackupErrorKind.MANIFEST_INVALID, "pasta inválida")
        val manifestFile = dir.findFile(MANIFEST_FILE_NAME) ?: throw BackupException(
            BackupErrorKind.MANIFEST_INVALID,
            "manifest.json não encontrado na pasta escolhida",
        )
        val manifest = runCatching {
            context.contentResolver.openInputStream(manifestFile.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }?.let { text -> BackupManifest.decode(text) }
        }.getOrNull() ?: throw BackupException(
            BackupErrorKind.MANIFEST_INVALID,
            "manifest.json corrompido ou em formato incompatível",
        )
        return inspectFor(dir, manifest)
    }

    fun inspectFor(dir: DocumentFile, manifest: BackupManifest): ImportInspect {
        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            throw BackupException(
                BackupErrorKind.MANIFEST_INVALID,
                "pacote mais novo que o app (formato ${manifest.formatVersion})",
            )
        }
        if (manifest.gameId.isBlank() || manifest.parts.isEmpty()) {
            throw BackupException(BackupErrorKind.MANIFEST_INVALID, "manifest sem gameId ou sem partes")
        }
        val prefix = slugFor(manifest.gameId)
        val missing = manifest.parts.filter { part ->
            val file = dir.findFile(part.name)
            file == null || !file.canRead()
        }.map { it.name }
        return ImportInspect(
            manifest = manifest,
            presentParts = manifest.parts.size - missing.size,
            missingParts = missing,
            totalSizeCompressed = manifest.totalSizeCompressed,
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

        val dir = DocumentFile.fromTreeUri(context, Uri.parse(state.treeUri)) ?: throw BackupException(
            BackupErrorKind.MANIFEST_INVALID,
            "pasta do pacote não acessível (permissão do SAF expirada?)",
        )
        val manifestFile = dir.findFile(MANIFEST_FILE_NAME) ?: throw BackupException(
            BackupErrorKind.MANIFEST_INVALID,
            "manifest.json não encontrado",
        )
        val manifest = runCatching {
            context.contentResolver.openInputStream(manifestFile.uri)?.use { input ->
                BackupManifest.decode(input.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull() ?: throw BackupException(BackupErrorKind.MANIFEST_INVALID, "manifest corrompido")

        val virtual = AppGraph.virtual
        if (!virtual.engineReady) {
            throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "motor virtual não está pronto")
        }
        val pkg = manifest.packageName.ifBlank { state.packageName }
        val paths = virtual.gamePaths(pkg)
            ?: throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "paths do ambiente virtual indisponíveis")

        val total = manifest.totalSizeExtracted
        var bytesDone = state.completedParts.sumOf { it.sizeExtracted }
        val parts = state.completedParts.toMutableList()

        fun publish(phase: BackupPhase) {
            bus.publish(
                BackupJobUi(
                    jobId = state.jobId,
                    direction = BackupDirection.IMPORT,
                    gameId = state.gameId,
                    gameTitle = state.gameTitle,
                    phase = phase,
                    bytesDone = bytesDone,
                    bytesTotal = total,
                    partsDone = parts.size,
                    partsTotal = manifest.parts.size,
                ),
            )
        }

        val speed = SpeedTracker(bytesDone)
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
                        partsDone = parts.size,
                        partsTotal = manifest.parts.size,
                    ),
                )
            }
        }

        // Espaço antes de começar (destinos no sandbox interno + externo do app).
        val free = freeSpaceBestEffort()
        if (free != Long.MAX_VALUE && total > 0 && free < total) {
            throw BackupException(
                BackupErrorKind.INSUFFICIENT_SPACE,
                "espaço insuficiente para extrair ${FormattersCompat.humanBytes(total)} (livre: ${FormattersCompat.humanBytes(free)})",
                total,
            )
        }

        publish(BackupPhase.PREPARING)

        // Backup dos saves atuais antes de substituir (rápido — saves são pequenos).
        if (state.backupSavesFirst) {
            runCatching { exportSavesBackupInline(manifest, dir) }
        }

        val reader = ChunkReader()
        val pendingApk = paths.engineCacheDir?.let { File(it, state.pendingApkName ?: tempApkName(pkg)) }
        var registryBytes: ByteArray? = null

        try {
            for (index in state.completedParts.size until manifest.parts.size) {
                if (canceled()) throw BackupException(BackupErrorKind.CANCELED, "cancelado pelo usuário")
                val part = manifest.parts[index]
                val partFile = dir.findFile(part.name) ?: throw BackupException(
                    BackupErrorKind.PART_MISSING,
                    "parte ausente: ${part.name}",
                    part.name,
                )
                val stream = context.contentResolver.openInputStream(partFile.uri) ?: throw BackupException(
                    BackupErrorKind.IO_FAILURE,
                    "não foi possível abrir: ${part.name}",
                )
                stream.use { input ->
                    reader.forEachEntry(input, part.sha256) { entry, data ->
                        if (canceled()) {
                            throw BackupException(BackupErrorKind.CANCELED, "cancelado em ${entry.name}")
                        }
                        val name = entry.name
                        if (name == GameSources.TAR_REGISTRY) {
                            registryBytes = data.readBytes()
                            bytesDone += entry.size
                            return@forEachEntry
                        }
                        val target = resolveDestination(paths, name, pendingApk)
                            ?: return@forEachEntry // entrada desconhecida: pula (formato evoluível)
                        val copied = ChunkReader.copyEntry(data, entry.size, target)
                        bytesDone += copied
                        publishThrottled(name)
                    }
                }
                parts += CompletedPart(
                    index = index + 1,
                    name = part.name,
                    sha256 = part.sha256,
                    sizeCompressed = part.sizeCompressed,
                    sizeExtracted = part.sizeExtracted,
                )
                if (state.deletePartsAfterExtract) {
                    runCatching { partFile.delete() }
                }
                store.save(
                    state.copy(
                        completedParts = parts.toList(),
                        pendingApkName = pendingApk?.name,
                        registryPending = registryBytes != null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                publish(BackupPhase.RUNNING)
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
            // Erro/cancelamento: partes concluídas e arquivos extraídos ficam;
            // a retomada refaz SÓ a parte interrompida (sobrescrevendo).
            if (t is BackupException && t.kind == BackupErrorKind.INSTALL_FAILED) {
                runCatching { pendingApk?.delete() }
            }
            throw t
        }

        store.delete(state.jobId)
        virtual.refresh()
        publish(BackupPhase.DONE)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Backup rápido dos saves/configurações ATUAIS (antes da importação
     * substituir), na própria pasta do pacote: `_saves_backup_<prefixo>_<data>/`.
     * Sem job/bus/notificação — roda dentro do import.
     */
    private fun exportSavesBackupInline(manifest: BackupManifest, packageDir: DocumentFile) {
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

        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.LocalDateTime.now())
        val backupDir = packageDir.createDirectory("_saves_backup_${manifest.gameId}_$stamp") ?: return
        val prefix = "${slugFor(manifest.gameId)}_saves"
        val writer = ChunkWriter(
            openPartStream = { index ->
                val name = partFileName(prefix, index)
                backupDir.findFile(name)?.delete()
                val file = backupDir.createFile("application/octet-stream", name)
                    ?: throw BackupException(BackupErrorKind.IO_FAILURE, "createFile falhou: $name")
                context.contentResolver.openOutputStream(file.uri)
                    ?: throw BackupException(BackupErrorKind.IO_FAILURE, "openOutputStream falhou: $name")
            },
            partSizeBytes = PART_SIZE_LARGE,
        )
        var extracted = 0L
        val parts = mutableListOf<CompletedPart>()
        try {
            plan.entries.forEach { entry ->
                when {
                    entry.file != null -> java.io.FileInputStream(entry.file).use { input ->
                        writer.addFile(entry.tarPath, entry.bytes, levelFor(entry.tarPath, LEVEL_BALANCED), input) {
                            extracted += it
                        }
                    }
                    entry.inlineBytes != null -> writer.addBytes(
                        entry.tarPath,
                        LEVEL_BALANCED,
                        entry.inlineBytes,
                    ) { extracted += it }
                }
                if (writer.shouldRollPart()) {
                    writer.closePart()?.let { r ->
                        parts += CompletedPart(r.index, partFileName(prefix, r.index), r.sha256, r.sizeCompressed, extracted)
                        extracted = 0L
                    }
                }
            }
            writer.closePart()?.let { r ->
                parts += CompletedPart(r.index, partFileName(prefix, r.index), r.sha256, r.sizeCompressed, extracted)
            }
            val backupManifest = BackupManifest(
                gameId = manifest.gameId,
                gameName = manifest.gameName,
                packageName = currentPkg,
                appVersion = virtual.infoFor(currentPkg)?.versionName ?: "unknown",
                createdAt = java.time.OffsetDateTime.now().toString(),
                partSizeBytes = PART_SIZE_LARGE,
                totalSizeCompressed = parts.sumOf { it.sizeCompressed },
                totalSizeExtracted = plan.totalBytes,
                parts = parts.map { BackupPart(it.name, it.sizeCompressed, it.sizeExtracted, it.sha256) },
                includes = listOf(BackupCategory.SAVES.id, BackupCategory.SETTINGS.id),
            )
            backupDir.findFile(MANIFEST_FILE_NAME)?.delete()
            val mf = backupDir.createFile("application/json", MANIFEST_FILE_NAME) ?: return
            context.contentResolver.openOutputStream(mf.uri)?.use { out ->
                out.write(BackupManifest.JSON.encodeToString(BackupManifest.serializer(), backupManifest).toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (t: Throwable) {
            writer.abortPart { index -> runCatching { backupDir.findFile(partFileName(prefix, index))?.delete() } }
            // Backup de saves é best-effort: falha NÃO impede a importação.
            if (t is BackupException && t.kind == BackupErrorKind.CANCELED) throw t
        }
    }

    /**
     * Mapeia o caminho no TAR para o destino final no ambiente virtual.
     * Retorna null para entradas desconhecidas (formato evoluível) — nunca
     * para caminhos inseguros (zip-slip é barrado antes).
     */
    private fun resolveDestination(paths: com.deivid22srk.portstore.virtual.VirtualGamePaths, tarPath: String, pendingApk: File?): File? {
        if (isUnsafePath(tarPath)) return null
        return when {
            tarPath == GameSources.TAR_APK -> pendingApk
                ?: throw BackupException(BackupErrorKind.IO_FAILURE, "cache do motor indisponível para o APK")
            tarPath.startsWith("${GameSources.TAR_OBB_PREFIX}/") -> joinUnder(paths.obbDir, tarPath, GameSources.TAR_OBB_PREFIX)
            tarPath.startsWith("${GameSources.TAR_EXTDATA_PREFIX}/") -> joinUnder(paths.externalDataDir, tarPath, GameSources.TAR_EXTDATA_PREFIX)
            tarPath.startsWith("${GameSources.TAR_DATA_PREFIX}/") -> joinUnder(paths.dataDir, tarPath, GameSources.TAR_DATA_PREFIX)
            tarPath.startsWith("${GameSources.TAR_SAVES_PREFIX}/") -> joinUnder(paths.databasesDir, tarPath, GameSources.TAR_SAVES_PREFIX)
            tarPath.startsWith("${GameSources.TAR_SETTINGS_PREFIX}/") -> joinUnder(paths.sharedPrefsDir, tarPath, GameSources.TAR_SETTINGS_PREFIX)
            else -> null
        }
    }

    /** Extrai o caminho relativo e junta sob a raiz (raiz null -> fora). */
    private fun joinUnder(root: File?, tarPath: String, prefix: String): File? {
        if (root == null) return null
        val rel = tarPath.removePrefix("$prefix/")
        return File(root, rel)
    }

    private fun isUnsafePath(path: String): Boolean {
        if (path.startsWith("/") || path.contains('\\')) return true
        return path.split('/').any { it.isEmpty() || it == "." || it == ".." }
    }

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
