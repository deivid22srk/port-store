package com.deivid22srk.portstore.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Fase atual de um job visível na UI. */
enum class BackupPhase { QUEUED, PREPARING, RUNNING, DONE, ERROR, CANCELED }

/**
 * Estado do job consumido pela tela Downloads (e pela notificação do
 * serviço foreground). [bytesTotal] é o total de bytes úteis (dados do
 * jogo) — o progresso é sobre os dados, não sobre os bytes comprimidos.
 */
data class BackupJobUi(
    val jobId: String,
    val direction: BackupDirection,
    val gameId: String,
    val gameTitle: String,
    val phase: BackupPhase,
    val message: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBps: Long = 0L,
    val etaSec: Long = 0L,
    val currentFile: String? = null,
    val canRetry: Boolean = false,
) {
    val progress: Float
        get() = if (bytesTotal > 0) (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat() else 0f

    val isActive: Boolean get() = phase == BackupPhase.QUEUED || phase == BackupPhase.PREPARING || phase == BackupPhase.RUNNING
}

/**
 * Barramento único de progresso dos jobs de backup: workers publicam, o
 * serviço foreground (notificação) e a tela Downloads consomem. Também
 * carrega a flag de cancelamento cooperativo pedida pela UI.
 */
object BackupProgressBus {

    private val _jobs = MutableStateFlow<List<BackupJobUi>>(emptyList())
    val jobs: StateFlow<List<BackupJobUi>> = _jobs.asStateFlow()

    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    /** Insere/atualiza o job na lista (preservando a ordem de início). */
    fun publish(job: BackupJobUi) {
        val current = _jobs.value.toMutableList()
        val index = current.indexOfFirst { it.jobId == job.jobId }
        if (index >= 0) current[index] = job else current.add(job)
        _jobs.value = current
    }

    fun remove(jobId: String) {
        _jobs.value = _jobs.value.filterNot { it.jobId == jobId }
    }

    fun job(jobId: String): BackupJobUi? = _jobs.value.firstOrNull { it.jobId == jobId }

    /** Cancelamento cooperativo: o worker consulta e interrompe com limpeza. */
    fun requestCancel(jobId: String) {
        cancelFlags[jobId] = true
        // Reflexo imediato na UI (o worker ainda vai publicar CANCELED).
        job(jobId)?.let { if (it.isActive) publish(it.copy(phase = BackupPhase.CANCELED, message = null)) }
    }

    fun isCancelRequested(jobId: String): Boolean = cancelFlags[jobId] == true

    /**
     * Limpa a flag de cancelamento (OBRIGATÓRIO antes de enfileirar/retomar:
     * "Tentar novamente" reusa o mesmo jobId — flag velha cancelaria o job
     * de novo instantaneamente).
     */
    fun clearCancel(jobId: String) {
        cancelFlags.remove(jobId)
    }
}

/** Erros previsíveis de backup — cada um vira mensagem clara na UI/notificação. */
enum class BackupErrorKind {
    ENGINE_UNAVAILABLE,
    NOTHING_TO_EXPORT,
    MANIFEST_INVALID,
    INSUFFICIENT_SPACE,
    IO_FAILURE,
    CANCELED,
    INSTALL_FAILED,
}

class BackupException(val kind: BackupErrorKind, message: String?, val arg: Any? = null) : Exception(message)
