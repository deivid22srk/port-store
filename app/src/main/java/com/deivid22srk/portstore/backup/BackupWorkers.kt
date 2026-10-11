package com.deivid22srk.portstore.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.deivid22srk.portstore.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mensagem legível (pt-BR) para cada erro previsível — usada na notificação
 * e no card da tela Downloads.
 */
fun backupErrorMessage(context: Context, error: BackupException): String = when (error.kind) {
    BackupErrorKind.ENGINE_UNAVAILABLE -> context.getString(R.string.backup_error_no_engine)
    BackupErrorKind.NOTHING_TO_EXPORT -> context.getString(R.string.backup_error_nothing)
    BackupErrorKind.MANIFEST_INVALID -> context.getString(R.string.backup_error_manifest)
    BackupErrorKind.INSUFFICIENT_SPACE -> context.getString(R.string.backup_error_space)
    BackupErrorKind.IO_FAILURE -> context.getString(R.string.backup_error_io, error.message ?: "")
    BackupErrorKind.INSTALL_FAILED -> context.getString(R.string.backup_error_install, error.message ?: "")
    BackupErrorKind.CANCELED -> context.getString(R.string.backup_state_canceled)
}

/**
 * Corpo comum dos workers de backup: carrega o [JobState], sobe o serviço
 * foreground ([BackupForegroundService] — notificação de progresso viva
 * mesmo se o worker morrer) e executa o trabalho pesado em Dispatchers.IO.
 *
 * O cancelamento (flag do bus OU worker parado) é cooperativo e o manager
 * faz a limpeza antes de lançar. QUALQUER Throwable não previsto é mapeado
 * para ERROR com mensagem — o bus NUNCA fica preso em "Preparando…".
 */
private suspend fun runBackupJob(
    worker: CoroutineWorker,
    workerParams: WorkerParameters,
    block: (context: Context, state: JobState, canceled: () -> Boolean) -> Unit,
): ListenableWorker.Result {
    val context = worker.applicationContext
    val jobId = workerParams.inputData.getString(BackupJobs.KEY_JOB_ID)
        ?: return ListenableWorker.Result.failure()
    val state = JobStateStore(context).load(jobId) ?: return ListenableWorker.Result.failure()
    val title = BackupNotifications.jobTitle(context, state.direction, state.gameTitle)

    // Garantia dupla: o BackupJobs.launch já sobe o serviço ao enfileirar;
    // re_Start é barato e cobre retomadas despachadas direto pelo WorkManager.
    BackupForegroundService.start(context)

    return try {
        withContext(Dispatchers.IO) {
            block(context, state) {
                BackupProgressBus.isCancelRequested(jobId) || worker.isStopped
            }
        }
        val message = BackupNotifications.doneMessage(context, state.direction, state.gameTitle)
        BackupNotifications.done(context, title, state.gameId, message)
        ListenableWorker.Result.success()
    } catch (e: BackupException) {
        when (e.kind) {
            BackupErrorKind.CANCELED -> {
                BackupProgressBus.job(jobId)?.let {
                    BackupProgressBus.publish(it.copy(phase = BackupPhase.CANCELED))
                }
                BackupNotifications.cancel(context, jobId)
                ListenableWorker.Result.failure()
            }
            else -> publishError(context, jobId, state, title, backupErrorMessage(context, e))
        }
    } catch (e: CancellationException) {
        BackupProgressBus.job(jobId)?.let { BackupProgressBus.publish(it.copy(phase = BackupPhase.CANCELED)) }
        BackupNotifications.cancel(context, jobId)
        throw e
    } catch (e: Throwable) {
        // Rede de segurança: erro inesperado (SAF, gzip, TAR, OOM leve…)
        // vira estado visível — nunca deixa o card em "Preparando…" eterno.
        publishError(
            context,
            jobId,
            state,
            title,
            context.getString(R.string.backup_error_io, e.message ?: e.javaClass.simpleName),
        )
    } finally {
        // Encerra o serviço quando NENHUM job segue ativo no bus.
        if (BackupProgressBus.jobs.value.none { it.isActive }) {
            BackupForegroundService.stop(context)
        }
    }
}

private fun publishError(
    context: Context,
    jobId: String,
    state: JobState,
    title: String,
    message: String,
): ListenableWorker.Result {
    val current = BackupProgressBus.job(jobId)
    BackupProgressBus.publish(
        (current ?: BackupJobUi(
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
    BackupNotifications.error(context, title, state.gameId, message)
    return ListenableWorker.Result.failure()
}

/** Worker da exportação (streaming TAR -> gzip -> arquivo único via SAF). */
class ExportWorker(
    context: Context,
    private val workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): ListenableWorker.Result =
        runBackupJob(this, workerParams) { context, state, canceled ->
            BackupManager(context).export(state, canceled)
        }
}

/** Worker da importação (arquivo único -> gzip -> TAR -> destino direto). */
class ImportWorker(
    context: Context,
    private val workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): ListenableWorker.Result =
        runBackupJob(this, workerParams) { context, state, canceled ->
            ImportManager(context).import(state, canceled)
        }
}
