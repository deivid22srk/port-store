package com.deivid22srk.portstore.backup

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.deivid22srk.portstore.AppGraph
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opções do diálogo de exportação. */
data class ExportRequest(
    val gameId: String,
    val gameTitle: String,
    val categories: Set<BackupCategory>,
    val compressionLevel: Int,
    val partSizeBytes: Long,
    val treeUri: Uri,
)

/** Opções do diálogo de importação. */
data class ImportRequest(
    val treeUri: Uri,
    val deletePartsAfterExtract: Boolean,
    val backupSavesFirst: Boolean,
)

/**
 * Fachada do Modo Desenvolvedor para a UI (LauncherViewModel). Encapsula
 * validação, criação de job, fila do WorkManager, cancelamento/retomada e
 * a retomada automática de jobs pendentes no boot do app.
 */
class BackupJobs(private val context: Context) {

    private val store = JobStateStore(context)
    private val manager = BackupManager(context)
    private val importer = ImportManager(context)

    /** Jobs visíveis na UI (tela Downloads). */
    val jobs: StateFlow<List<BackupJobUi>> get() = BackupProgressBus.jobs

    // =====================================================================
    // Diálogos (preview/inspeção)
    // =====================================================================

    /** Tamanhos por categoria do jogo (para os checkboxes do diálogo). */
    suspend fun previewExport(gameId: String): ExportPlan = withContext(Dispatchers.IO) {
        manager.previewExport(gameId)
    }

    /** Lê/valida o manifest da pasta escolhida (diálogo de importação). */
    suspend fun inspectImport(treeUri: Uri): ImportInspect = withContext(Dispatchers.IO) {
        importer.inspect(treeUri)
    }

    // =====================================================================
    // Execução
    // =====================================================================

    suspend fun startExport(request: ExportRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val virtual = AppGraph.virtual
            if (!virtual.engineReady) {
                throw BackupException(BackupErrorKind.ENGINE_UNAVAILABLE, "motor virtual não está pronto")
            }
            val packageName = virtual.packageNameFor(request.gameId)
                ?: throw BackupException(BackupErrorKind.NOTHING_TO_EXPORT, "jogo não é virtual")
            if (request.categories.isEmpty()) {
                throw BackupException(BackupErrorKind.NOTHING_TO_EXPORT, "nenhuma categoria escolhida")
            }
            val prefix = slugFor(request.gameId)
            val now = System.currentTimeMillis()
            val jobId = "exp_${prefix}_$now"
            val state = JobState(
                jobId = jobId,
                direction = BackupDirection.EXPORT,
                gameId = request.gameId,
                gameTitle = request.gameTitle,
                packageName = packageName,
                versionName = virtual.infoFor(packageName)?.versionName,
                treeUri = request.treeUri.toString(),
                packageDirName = packageFolderName(prefix),
                prefix = prefix,
                categories = request.categories.map { it.id },
                partSizeBytes = request.partSizeBytes,
                compressionLevel = request.compressionLevel,
                createdAt = now,
                updatedAt = now,
            )
            store.save(state)
            BackupProgressBus.publish(queuedUi(state))
            enqueue<ExportWorker>(jobId)
        }
    }

    suspend fun startImport(request: ImportRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val inspect = importer.inspect(request.treeUri) // lança MANIFEST_INVALID etc.
            if (inspect.missingParts.isNotEmpty()) {
                throw BackupException(
                    BackupErrorKind.PART_MISSING,
                    "partes ausentes: ${inspect.missingParts.joinToString()}",
                    inspect.missingParts.first(),
                )
            }
            val manifest = inspect.manifest
            val prefix = slugFor(manifest.gameId)
            val now = System.currentTimeMillis()
            val jobId = "imp_${prefix}_$now"
            val state = JobState(
                jobId = jobId,
                direction = BackupDirection.IMPORT,
                gameId = manifest.gameId,
                gameTitle = manifest.gameName,
                packageName = manifest.packageName,
                versionName = manifest.appVersion,
                treeUri = request.treeUri.toString(),
                packageDirName = "",
                prefix = prefix,
                categories = manifest.includes,
                partSizeBytes = manifest.partSizeBytes,
                compressionLevel = manifest.compressionLevel,
                deletePartsAfterExtract = request.deletePartsAfterExtract,
                backupSavesFirst = request.backupSavesFirst,
                createdAt = now,
                updatedAt = now,
            )
            store.save(state)
            BackupProgressBus.publish(queuedUi(state))
            enqueue<ImportWorker>(jobId)
        }
    }

    // =====================================================================
    // Controle dos jobs
    // =====================================================================

    /** Cancela (export apaga as partes geradas; import mantém o extraído). */
    fun cancel(jobId: String) {
        BackupProgressBus.requestCancel(jobId)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(jobId)) }
    }

    /** Retoma um job com erro (refaz só o que falta). */
    fun retry(jobId: String) {
        val state = store.load(jobId) ?: return
        if (BackupProgressBus.job(jobId)?.isActive == true) return
        BackupProgressBus.publish(queuedUi(state))
        when (state.direction) {
            BackupDirection.EXPORT -> enqueue<ExportWorker>(jobId)
            BackupDirection.IMPORT -> enqueue<ImportWorker>(jobId)
        }
    }

    /** Remove o card da lista (desiste do job — apaga o estado de retomada). */
    fun dismiss(jobId: String) {
        val job = BackupProgressBus.job(jobId)
        if (job?.isActive != true) {
            store.delete(jobId)
            BackupProgressBus.remove(jobId)
        }
    }

    /**
     * Retomada automática: jobs persistidos (em andamento quando o app
     * fechou) voltam à fila — o WorkManager mantém o enqueue, mas um job
     * interrompido sem worker vivo precisa de novo dispatch.
     */
    fun recoverPending() {
        store.list().forEach { state ->
            if (BackupProgressBus.job(state.jobId)?.isActive == true) return@forEach
            BackupProgressBus.publish(queuedUi(state))
            when (state.direction) {
                BackupDirection.EXPORT -> enqueue<ExportWorker>(state.jobId)
                BackupDirection.IMPORT -> enqueue<ImportWorker>(state.jobId)
            }
        }
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueue(jobId: String) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInputData(workDataOf(KEY_JOB_ID to jobId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun queuedUi(state: JobState) = BackupJobUi(
        jobId = state.jobId,
        direction = state.direction,
        gameId = state.gameId,
        gameTitle = state.gameTitle,
        phase = BackupPhase.QUEUED,
        partsTotal = state.completedParts.size.coerceAtLeast(1),
    )

    companion object {
        const val KEY_JOB_ID = "jobId"

        fun workName(jobId: String): String = "backup_$jobId"
    }
}
