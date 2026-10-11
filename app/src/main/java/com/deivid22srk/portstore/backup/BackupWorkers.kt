package com.deivid22srk.portstore.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deivid22srk.portstore.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mensagem legível (pt-BR) para cada erro previsível — usada na notificação
 * e no card da tela Downloads.
 */
fun backupErrorMessage(context: Context, error: BackupException): String = when (error.kind) {
    BackupErrorKind.ENGINE_UNAVAILABLE -> context.getString(R.string.backup_error_no_engine)
    BackupErrorKind.NOTHING_TO_EXPORT -> context.getString(R.string.backup_error_nothing)
    BackupErrorKind.MANIFEST_INVALID -> context.getString(R.string.backup_error_manifest)
    BackupErrorKind.PART_MISSING -> context.getString(R.string.backup_error_missing_part, error.arg?.toString() ?: "")
    BackupErrorKind.HASH_MISMATCH -> context.getString(R.string.backup_error_hash)
    BackupErrorKind.INSUFFICIENT_SPACE -> context.getString(R.string.backup_error_space)
    BackupErrorKind.IO_FAILURE -> context.getString(R.string.backup_error_io, error.message ?: "")
    BackupErrorKind.INSTALL_FAILED -> context.getString(R.string.backup_error_install, error.message ?: "")
    BackupErrorKind.CANCELED -> context.getString(R.string.backup_state_canceled)
}

/**
 * Corpo comum dos workers de backup: carrega o [JobState], sobe o foreground
 * (canal de downloads), mantém a notificação de progresso sincronizada com o
 * [BackupProgressBus] e mapeia erros para estados de UI. O trabalho pesado
 * roda em Dispatchers.IO; o cancelamento (flag do bus OU worker parado) é
 * cooperativo e o manager faz a limpeza antes de lançar.
 */
private suspend fun runBackupJob(
    worker: CoroutineWorker,
    params: WorkerParameters,
    block: (context: Context, state: JobState, canceled: () -> Boolean) -> Unit,
): Result {
    val context = worker.applicationContext
    val jobId = params.inputData.getString(BackupJobs.KEY_JOB_ID) ?: return Result.failure()
    val state = JobStateStore(context).load(jobId) ?: return Result.failure()
    val title = BackupNotifications.jobTitle(context, state.direction, state.gameTitle)

    runCatching { worker.setForeground(BackupNotifications.foregroundInfo(context, jobId, title, state.gameId)) }

    return try {
        coroutineScopeWithNotifier(context, jobId, title, state.gameId) {
            withContext(Dispatchers.IO) {
                block(context, state) {
                    BackupProgressBus.isCancelRequested(jobId) || worker.isStopped
                }
            }
        }
        val message = BackupNotifications.doneMessage(context, state.direction, state.gameTitle)
        BackupNotifications.done(context, title, state.gameId, message)
        Result.success()
    } catch (e: BackupException) {
        when (e.kind) {
            BackupErrorKind.CANCELED -> {
                BackupNotifications.cancel(context, jobId)
                Result.failure()
            }
            else -> {
                val message = backupErrorMessage(context, e)
                BackupProgressBus.publish(
                    (BackupProgressBus.job(jobId) ?: BackupJobUi(
                        jobId = jobId,
                        direction = state.direction,
                        gameId = state.gameId,
                        gameTitle = state.gameTitle,
                        phase = BackupPhase.RUNNING,
                    )).copy(
                        phase = BackupPhase.ERROR,
                        message = message,
                        canRetry = true,
                    ),
                )
                BackupNotifications.error(context, jobId, title, state.gameId, message)
                Result.failure()
            }
        }
    } catch (e: CancellationException) {
        BackupProgressBus.job(jobId)?.let { BackupProgressBus.publish(it.copy(phase = BackupPhase.CANCELED)) }
        BackupNotifications.cancel(context, jobId)
        throw e
    } finally {
        // Se concluiu, limpa a notificação de progresso (a de conclusão fica).
        val final = BackupProgressBus.job(jobId)
        if (final == null || final.phase == BackupPhase.DONE) {
            BackupNotifications.cancel(context, jobId)
        }
    }
}

/**
 * Observa o bus e atualiza a notificação de progresso enquanto o job roda.
 */
private suspend fun coroutineScopeWithNotifier(
    context: Context,
    jobId: String,
    title: String,
    gameId: String,
    block: suspend () -> Unit,
) {
    kotlinx.coroutines.coroutineScope {
        val notifier = launch(Dispatchers.Default) {
            while (isActive) {
                BackupProgressBus.job(jobId)?.let { ui ->
                    if (ui.isActive) BackupNotifications.update(context, jobId, title, gameId, ui)
                }
                delay(600)
            }
        }
        try {
            block()
        } finally {
            notifier.cancel()
        }
    }
}

/** Worker da exportação (Streaming TAR -> zstd -> partes via SAF). */
class ExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = params.inputData.getString(BackupJobs.KEY_JOB_ID).orEmpty()
        val state = JobStateStore(applicationContext).load(jobId)
        val title = BackupNotifications.jobTitle(
            applicationContext,
            BackupDirection.EXPORT,
            state?.gameTitle ?: "",
        )
        return BackupNotifications.foregroundInfo(applicationContext, jobId, title, state?.gameId ?: "")
    }

    override suspend fun doWork(): Result = runBackupJob(this, params) { context, state, canceled ->
        BackupManager(context).export(state, canceled)
    }
}

/** Worker da importação (hash -> zstd -> TAR -> destino direto). */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = params.inputData.getString(BackupJobs.KEY_JOB_ID).orEmpty()
        val state = JobStateStore(applicationContext).load(jobId)
        val title = BackupNotifications.jobTitle(
            applicationContext,
            BackupDirection.IMPORT,
            state?.gameTitle ?: "",
        )
        return BackupNotifications.foregroundInfo(applicationContext, jobId, title, state?.gameId ?: "")
    }

    override suspend fun doWork(): Result = runBackupJob(this, params) { context, state, canceled ->
        ImportManager(context).import(state, canceled)
    }
}
