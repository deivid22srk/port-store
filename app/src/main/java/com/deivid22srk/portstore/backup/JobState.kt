package com.deivid22srk.portstore.backup

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Direção do job. */
enum class BackupDirection { EXPORT, IMPORT }

/**
 * Estado persistente de um job de exportação/importação — é o que permite
 * retomada após o app fechar (o WorkManager re-executa o worker e o estado
 * diz de onde continuar). Guardado em `filesDir/backup_jobs/<jobId>.json`.
 *
 * O pacote é UM ÚNICO ARQUIVO ([documentUri]); a retomada de cada direção:
 *
 * - Export: [nextFileIndex] = nº de entradas já escritas (manifest conta
 *   como 1) e [committedBytes] = offset comprimido do fim da última entrada
 *   commitada — a retomada trunca o arquivo nesse offset e continua;
 * - Import: [nextFileIndex] = nº de entradas já processadas na sequência do
 *   TAR (manifest = 0) — a retomada reabre o stream e pula as primeiras
 *   [nextFileIndex] entradas (só decodifica, não extrai de novo);
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
    /** Documento SAF do arquivo único do pacote (criado/escolhido no diálogo). */
    val documentUri: String,
    val prefix: String,
    /** Versão do formato do pacote que este job produz/consome (v3 = gzip). */
    val format: Int,
    val categories: List<String> = emptyList(),
    val compressionLevel: Int = LEVEL_BALANCED,
    val deletePackageAfterExtract: Boolean = false,
    val backupSavesFirst: Boolean = false,
    /** Denominador de progresso (import: total extraído do manifest). */
    val totalBytes: Long = 0L,
    val fileCount: Int = 0,
    val nextFileIndex: Int = 0,
    /** Offset comprimido do fim da última entrada commitada (retomada export). */
    val committedBytes: Long = 0L,
    val pendingApkName: String? = null,
    val registryPending: Boolean = false,
    /** SHA-256 do pacote completo, calculado no mesmo stream da escrita. */
    val packageSha256: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /**
     * Estados antigos não são retomáveis (v1 = partes/treeUri; v2 = zstd,
     * cuja leitura nem existe mais) — descartados no load/list.
     */
    val isLegacy: Boolean get() = documentUri.isBlank() || format != BACKUP_FORMAT_VERSION
}

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
    }.getOrNull()?.takeIf { !it.isLegacy }

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
