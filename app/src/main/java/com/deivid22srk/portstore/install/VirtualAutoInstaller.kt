package com.deivid22srk.portstore.install

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.core.DlState
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.gamedata.toVirtualFailure
import com.deivid22srk.portstore.virtual.VirtualFailure
import com.deivid22srk.portstore.virtual.VirtualInstallPhase
import com.deivid22srk.portstore.virtual.VirtualInstallProgress
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Instalação automática no ambiente virtual (Modo Instalação Virtual).
 *
 * Observa o repositório de downloads: quando um download passa para
 * COMPLETED com o modo ativado, o APK é instalado no ambiente virtual SEM
 * clique do usuário, e o progresso (fases PREPARING → INSTALLING → DONE)
 * é publicado em [progress] — a tela de detalhes do Game Launcher mostra
 * ao vivo caso o usuário esteja nela.
 *
 * Regras de gatilho (espelham o CTA da tela de detalhes):
 * - nada instalado (nem no Android, nem no virtual) → instala;
 * - já existe instalação virtual → só instala se o APK baixado for
 *   REALMENTE mais novo (atualização);
 * - só existe instalação no Android → instala se o APK baixado for mais
 *   novo que a instalada (o destino do modo é o ambiente virtual);
 * - processo do jogo virtual vivo → pula (nunca reinstala por cima de um
 *   jogo em execução; o botão manual permanece para o usuário decidir);
 * - motor não sobe (precisa reiniciar o app) → DONE com
 *   ENGINE_UNAVAILABLE e o CTA manual permanece.
 *
 * A primeira emissão do fluxo de downloads é baseline (downloads que já
 * estavam COMPLETED quando o processo nasceu NÃO disparam instalação) —
 * só transições observadas em vivo instalam.
 *
 * Instalações MANUAIS também publicam seu progresso aqui (mesma UI na
 * tela de detalhes), via [report].
 */
class VirtualAutoInstaller(context: Context) {

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<Map<String, VirtualInstallProgress>>(emptyMap())

    /**
     * Progresso de instalação virtual por gameId do catálogo. Enquanto a
     * fase não é DONE há uma instalação em andamento; DONE com status
     * Failure fica visível por um tempo curto (feedback inline) e sai.
     */
    val progress: StateFlow<Map<String, VirtualInstallProgress>> = _progress.asStateFlow()

    /** gameIds com instalação em andamento (dedup do gatilho automático). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val started = object : Any() {
        @Volatile var value = false
    }

    /** Há instalação virtual em andamento para este jogo? (auto OU manual) */
    fun isRunning(gameId: String): Boolean =
        _progress.value[gameId]?.let { it.phase != VirtualInstallPhase.DONE } == true

    /** Publica/atualiza o progresso de um jogo (usado pelo fluxo automático e manual). */
    fun report(update: VirtualInstallProgress) {
        _progress.update { it + (update.gameId to update) }
    }

    /** Remove o progresso de um jogo (chamado pela UI após consumir DONE). */
    fun clear(gameId: String) {
        _progress.update { it - gameId }
    }

    /** Liga o observador de downloads (chamado uma vez, no AppGraph.init). */
    fun start() {
        synchronized(started) {
            if (started.value) return
            started.value = true
        }
        scope.launch {
            var previous: Map<String, DownloadItem> = emptyMap()
            var baseline = true
            AppGraph.downloads.items.collect { items ->
                val current = items.associateBy { it.id }
                if (baseline) {
                    previous = current
                    baseline = false
                    return@collect
                }
                for ((id, item) in current) {
                    val before = previous[id]
                    val justCompleted = item.state == DlState.COMPLETED &&
                        before?.state != DlState.COMPLETED
                    if (justCompleted) {
                        maybeAutoInstall(item)
                    }
                    // Novo download (ou retomada) para o mesmo jogo: limpa
                    // progresso antigo da instalação anterior, se houver.
                    if (item.state in DlState.ACTIVE) {
                        clear(item.gameId)
                    }
                }
                previous = current
            }
        }
    }

    private fun maybeAutoInstall(item: DownloadItem) {
        val virtual = AppGraph.virtual
        val gameId = item.gameId

        // Já há instalação (automática ou manual) em andamento para este jogo.
        if (gameId in inFlight || isRunning(gameId)) return

        // Modo desligado: o APK permanece disponível para o instalador do Android.
        if (!AppGraph.settings.settings.value.virtualInstallEnabled) return
        val apk = File(item.destPath)
        if (!apk.exists()) return
        val archive = InstalledAppsMonitor.archivePackageInfo(appContext, item.destPath) ?: return

        val game = AppGraph.catalog.catalog.value?.games?.firstOrNull { it.id == gameId }
        if (game?.isWeb == true) return

        val vpkg = virtual.packageNameFor(gameId)
        val vApp = vpkg?.let { pkg -> virtual.apps.value.firstOrNull { it.packageName == pkg } }
        val androidState = game?.let { AppGraph.installedApps.stateFor(it.packageName) }

        val shouldInstall = when {
            vApp != null -> archive.versionCodeCompat() > vApp.versionCode
            androidState is InstallState.Installed ->
                archive.versionCodeCompat() > androidState.versionCode
            else -> true
        }
        if (!shouldInstall) return

        // Jogo em execução: nunca reinstala por cima — o usuário decide depois.
        if (vApp != null) {
            virtual.refreshRunning()
            if (virtual.isRunning(vApp.packageName)) return
        }

        inFlight.add(gameId)
        scope.launch {
            try {
                runAutoInstall(item, apk)
            } catch (t: Throwable) {
                report(
                    VirtualInstallProgress(
                        gameId = gameId,
                        phase = VirtualInstallPhase.DONE,
                        status = VirtualInstallStatus.Failure(VirtualFailure.ENGINE_ERROR, t.message),
                    ),
                )
            } finally {
                inFlight.remove(gameId)
                scheduleDoneCleanup(gameId)
            }
        }
    }

    private suspend fun runAutoInstall(item: DownloadItem, apk: File) {
        val gameId = item.gameId
        val virtual = AppGraph.virtual

        report(VirtualInstallProgress(gameId, VirtualInstallPhase.PREPARING))
        if (!virtual.ensureEngine()) {
            report(
                VirtualInstallProgress(
                    gameId = gameId,
                    phase = VirtualInstallPhase.DONE,
                    status = VirtualInstallStatus.Failure(VirtualFailure.ENGINE_UNAVAILABLE),
                ),
            )
            return
        }

        val status = virtual.installFromApk(gameId = gameId, apk = apk)
        completeAfterApkInstall(gameId, status)
    }

    /**
     * Finaliza o fluxo pós-instalação do APK (automático OU manual): com o
     * repositório de dados ativado, os DADOS COMPLEMENTARES são baixados e
     * instalados agora — o progresso (DOWNLOADING_DATA → INSTALLING_DATA)
     * continua nesta mesma fonte, e DONE só sai quando os dados completarem
     * (a execução do jogo permanece bloqueada até lá, via gate na Home e na
     * tela de detalhes). Com o repositório desativado/sem entrada, DONE sai
     * imediatamente (comportamento anterior preservado).
     */
    suspend fun completeAfterApkInstall(gameId: String, status: VirtualInstallStatus) {
        if (status is VirtualInstallStatus.Failure) {
            report(
                VirtualInstallProgress(
                    gameId = gameId,
                    phase = VirtualInstallPhase.DONE,
                    status = status,
                ),
            )
            return
        }
        try {
            AppGraph.dataRepo.installForGame(gameId) { report(it) }
        } catch (e: com.deivid22srk.portstore.gamedata.GameDataException) {
            report(
                VirtualInstallProgress(
                    gameId = gameId,
                    phase = VirtualInstallPhase.DONE,
                    status = VirtualInstallStatus.Failure(e.toVirtualFailure(), e.message),
                ),
            )
            return
        } catch (t: Throwable) {
            report(
                VirtualInstallProgress(
                    gameId = gameId,
                    phase = VirtualInstallPhase.DONE,
                    status = VirtualInstallStatus.Failure(VirtualFailure.DATA_INSTALL_FAILED, t.message),
                ),
            )
            return
        }
        report(
            VirtualInstallProgress(
                gameId = gameId,
                phase = VirtualInstallPhase.DONE,
                status = status,
            ),
        )
    }

    /** DONE sai do fluxo sozinho após um tempo curto (a UI também pode limpar). */
    private fun scheduleDoneCleanup(gameId: String) {
        scope.launch {
            delay(DONE_VISIBLE_MS)
            _progress.update { map ->
                if (map[gameId]?.phase == VirtualInstallPhase.DONE) map - gameId else map
            }
        }
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private companion object {
        const val DONE_VISIBLE_MS = 15_000L
    }
}
