package com.deivid22srk.portstore.backup

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Direção do job. */
enum class BackupDirection { EXPORT, IMPORT }

/** Uma parte concluída (hash verificado na exportação; conferido na importação). */
@Serializable
data class CompletedPart(
    val index: Int,
    val name: String,
    val sha256: String,
    val sizeCompressed: Long,
    val sizeExtracted: Long,
)

/**
 * Estado persistente de um job de exportação/importação — é o que permite
 * retomada após o app fechar (o WorkManager re-executa o worker e o estado
 * diz de onde continuar). Guardado em `filesDir/backup_jobs/<jobId>.json`.
 *
 * - Export: [completedParts] já gravadas no SAF e [nextFileIndex] = índice
 *   (0-based) do próximo arquivo da lista determinística de fontes.
 * - Import: [completedParts] já verificadas (hash ok) e extraídas;
 *   [pendingApkName] é o APK base extraído no cache do Bcore aguardando o
 *   registro no motor ao final do job.
 */
@Serializable
data class JobState(
    val jobId: String,
    val direction: BackupDirection,
    val gameId: String,
    val gameTitle: String,
    val packageName: String,
    val versionName: String? = null,
    val treeUri: String,
    val packageDirName: String,
    val prefix: String,
    val categories: List<String> = emptyList(),
    val partSizeBytes: Long = PART_SIZE_DEFAULT,
    val compressionLevel: Int = LEVEL_BALANCED,
    val deletePartsAfterExtract: Boolean = false,
    val backupSavesFirst: Boolean = false,
    val completedParts: List<CompletedPart> = emptyList(),
    val nextFileIndex: Int = 0,
    val pendingApkName: String? = null,
    val registryPending: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** Persistência dos estados de job (arquivos JSON pequenos em filesDir). */
class JobStateStore(context: Context) {

    private val dir: File = File(context.filesDir, "backup_jobs").apply { mkdirs() }

    fun save(state: JobState) {
        val file = File(dir, "${state.jobId}.json")
        val tmp = File(dir, "${state.jobId}.tmp")
        tmp.writeText(JSON.encodeToString(JobState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    fun load(jobId: String): JobState? = runCatching {
        val file = File(dir, "$jobId.json")
        if (!file.exists()) return null
        JSON.decodeFromString(JobState.serializer(), file.readText())
    }.getOrNull()

    fun delete(jobId: String) {
        File(dir, "$jobId.json").delete()
        File(dir, "$jobId.tmp").delete()
    }

    fun list(): List<JobState> = runCatching {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f ->
                runCatching { JSON.decodeFromString(JobState.serializer(), f.readText()) }
                    .getOrNull()
            }
    }.getOrDefault(emptyList())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
