package com.deivid22srk.portstore.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.installer.ApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Estado e fluxo da experiência de ABERTURA de um jogo pelo Game Launcher
 * (tela de carregamento estilo console).
 *
 * Sequência (Recurso 1):
 *  1. [launch] é chamado pelo card/detalhe do Launcher ANTES de abrir qualquer
 *     coisa — o overlay aparece imediatamente (entrada fade/escala);
 *  2. o trabalho real (motor virtual, resolve de pacote, startActivity) roda
 *     em segundo plano, atrás do overlay;
 *  3. o overlay SÓ sai quando o jogo está realmente na frente:
 *     - jogos virtuais: activity do jogo no topo da tarefa do próprio app
 *       (poll leve a cada 200 ms via ActivityManager.getRunningTasks — só
 *       enxerga as tarefas do próprio app, sem permissão);
 *     - jogos normais: o próprio Android cobre o Launcher — o sinal é o
 *       ON_STOP do host ([onHostActivityStopped], religado pelo LauncherRoot);
 *  4. tempos: mínimo [MIN_DISPLAY_MS] (evita flash) e máximo por tipo
 *     ([VIRTUAL_TIMEOUT_MS]/[NORMAL_TIMEOUT_MS]) — estourou, tela de erro com
 *     Tentar novamente / Voltar ao Launcher / (virtuais) Instalar no Android.
 *
 * Cancelamento: B/Voltar no Launcher chama [cancel] — se o motor já havia
 * iniciado o jogo mas ele ainda não apareceu, o processo virtual é parado.
 *
 * Sem alocação pesada por quadro: tudo que anima é alpha/escala; a capa com
 * blur é renderizada uma única vez (RenderEffect, API 31+).
 */
object GameExperienceController {

    enum class Phase {
        /** Antes de chamar o motor/resolve (motor subindo, verificação). */
        PREPARING,

        /** Chamada de abertura feita; esperando a activity do jogo. */
        LOADING,

        /** Jogo detectado na frente; só aguardando o tempo mínimo. */
        ALMOST,
    }

    enum class ErrorReason {
        /** Motor virtual indisponível mesmo após ensureEngine. */
        ENGINE,

        /** O motor resolveu, mas launchApk falhou (ou resolve sem pacote). */
        LAUNCH_FAILED,

        /** Jogo não apareceu dentro do tempo máximo. */
        TIMEOUT,

        /** Jogo normal não está mais instalado / sem intent de abertura. */
        RESOLVE,
    }

    sealed interface State {
        data object Idle : State

        data class Loading(
            val gameId: String,
            val title: String,
            val coverUrl: String?,
            val virtual: Boolean,
            val phase: Phase,
        ) : State

        data class Error(
            val gameId: String,
            val title: String,
            val coverUrl: String?,
            val virtual: Boolean,
            val reason: ErrorReason,
        ) : State
    }

    companion object {
        /** Tempo mínimo de exibição (evita "flash" quando o jogo abre rápido). */
        const val MIN_DISPLAY_MS = 1_300L

        /** Máximo para jogos virtuais (primeiro boot do motor pode ser lento). */
        const val VIRTUAL_TIMEOUT_MS = 15_000L

        /** Máximo para jogos normais (transição do próprio Android). */
        const val NORMAL_TIMEOUT_MS = 10_000L

        /** Intervalo de checagem de prontidão. */
        const val POLL_INTERVAL_MS = 200L

        /** Orientações que o overlay honra (evita rotação desnecessária). */
        private val HONORED_ORIENTATIONS = setOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
        )

        /** Cor de erro do tema (Theme.kt) para o overlay. */
        val ErrorColor = Color(0xFFFF5449)
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var activityRef: WeakReference<Activity>? = null
    private var lastGame: Game? = null
    private var currentVpkg: String? = null
    private var launchCalled = false
    private var gameReady = false
    private var startedAtMs = 0L
    private var hostCovered = false
    private var savedOrientation: Int? = null

    val isBusy: Boolean
        get() = _state.value !is State.Idle

    // =================================================================
    // Entrada
    // =================================================================

    /**
     * Abre [game] com a tela de carregamento. No-op se já houver uma abertura
     * em andamento (protege contra toques repetidos no card focado).
     */
    fun launch(activity: Activity, game: Game) {
        if (_state.value is State.Loading) return
        lastGame = game
        activityRef = WeakReference(activity)
        hostCovered = false
        launchCalled = false
        gameReady = false
        startedAtMs = System.currentTimeMillis()
        currentVpkg = AppGraph.virtual.packageNameFor(game.id)
            ?.takeIf { AppGraph.virtual.isInstalled(it) }

        prepareOrientation(activity, game)
        playLaunchFeedback(activity)

        _state.value = State.Loading(
            gameId = game.id,
            title = game.title,
            coverUrl = game.coverUrl,
            virtual = currentVpkg != null,
            phase = Phase.PREPARING,
        )
        job?.cancel()
        job = scope.launch {
            try {
                doLaunch(activity, game)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = State.Error(
                    gameId = game.id,
                    title = game.title,
                    coverUrl = game.coverUrl,
                    virtual = currentVpkg != null,
                    reason = ErrorReason.LAUNCH_FAILED,
                )
            }
        }
    }

    private suspend fun doLaunch(activity: Activity, game: Game) {
        val vpkg = currentVpkg
        if (vpkg != null) {
            // ===== Jogo virtual: garante o motor e abre no ambiente =====
            if (!AppGraph.virtual.engineReady) {
                setPhase(Phase.PREPARING)
                val ready = withContext(Dispatchers.IO) { AppGraph.virtual.ensureEngine() }
                if (!ready) {
                    fail(game, virtual = true, ErrorReason.ENGINE)
                    return
                }
            }
            setPhase(Phase.LOADING)
            val launched = withContext(Dispatchers.IO) { AppGraph.virtual.launch(vpkg) }
            if (!launched) {
                fail(game, virtual = true, ErrorReason.LAUNCH_FAILED)
                return
            }
            launchCalled = true
            awaitReadyOrTimeout(activity, game, virtual = true)
        } else {
            // ===== Jogo normal do Android: intent de abertura =====
            setPhase(Phase.PREPARING)
            val pm = activity.packageManager
            val pkg = game.packageName.firstOrNull {
                AppGraph.installedApps.checkPackage(pm, it) is
                    com.deivid22srk.portstore.install.InstallState.Installed
            } ?: game.primaryPackage
            val intent = pkg?.let { com.deivid22srk.portstore.install.InstalledAppsMonitor.launchIntent(activity, it) }
            if (intent == null) {
                fail(game, virtual = false, ErrorReason.RESOLVE)
                return
            }
            setPhase(Phase.LOADING)
            if (!runCatching { activity.startActivity(intent) }.isSuccess) {
                fail(game, virtual = false, ErrorReason.LAUNCH_FAILED)
                return
            }
            launchCalled = true
            awaitReadyOrTimeout(activity, game, virtual = false)
        }
    }

    /** Espera o jogo aparecer (poll) respeitando mínimo/máximo de exibição. */
    private suspend fun awaitReadyOrTimeout(activity: Activity, game: Game, virtual: Boolean) {
        val timeoutMs = if (virtual) VIRTUAL_TIMEOUT_MS else NORMAL_TIMEOUT_MS
        while (scope.isActive) {
            val elapsed = System.currentTimeMillis() - startedAtMs
            if (elapsed >= timeoutMs) {
                fail(game, virtual, ErrorReason.TIMEOUT)
                return
            }
            if (!gameReady && (hostCovered || isGameTopmost(activity))) {
                gameReady = true
                setPhase(Phase.ALMOST)
            }
            if (gameReady && elapsed >= MIN_DISPLAY_MS) {
                finish()
                return
            }
            // Jogo cobriu o host antes do tempo mínimo: nada a exibir — encerra
            // em silêncio (o overlay já está invisível sob o jogo).
            if (hostCovered && elapsed >= MIN_DISPLAY_MS) {
                finish()
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun setPhase(phase: Phase) {
        _state.update { (it as? State.Loading)?.copy(phase = phase) ?: it }
    }

    private fun fail(game: Game, virtual: Boolean, reason: ErrorReason) {
        _state.value = State.Error(
            gameId = game.id,
            title = game.title,
            coverUrl = game.coverUrl,
            virtual = virtual,
            reason = reason,
        )
    }

    private fun finish() {
        _state.value = State.Idle
        restoreOrientation()
        activityRef = null
    }

    // =================================================================
    // Ações da UI
    // =================================================================

    /** B/Voltar durante o carregamento: aborta (e para o jogo virtual que ainda não apareceu). */
    fun cancel() {
        val loading = _state.value as? State.Loading ?: return
        job?.cancel()
        job = null
        if (loading.virtual) {
            currentVpkg?.let { vpkg ->
                if (launchCalled && !gameReady) {
                    // Jogo abrindo mas ainda não visível: encerra de forma limpa.
                    scope.launch { AppGraph.virtual.stop(vpkg) }
                }
            }
            AppGraph.virtual.refreshRunning()
        }
        finish()
    }

    /** Botão "Tentar novamente" da tela de erro. */
    fun retry() {
        val activity = activityRef?.get() ?: return
        val game = lastGame ?: return
        _state.value = State.Idle
        launch(activity, game)
    }

    /** Botão "Voltar ao Launcher" da tela de erro. */
    fun dismissError() {
        if (_state.value is State.Error) {
            _state.value = State.Idle
            restoreOrientation()
            activityRef = null
        }
    }

    /**
     * Botão "Instalar normalmente no Android" (só para jogos virtuais): usa o
     * APK base que o ambiente virtual já tem em disco.
     */
    fun installNormally(context: Context): Boolean {
        val error = _state.value as? State.Error ?: return false
        val vpkg = currentVpkg
        val apk = vpkg?.let { AppGraph.virtual.baseApkFile(it) }
        val ok = apk != null && ApkInstaller.installApk(context, apk.absolutePath)
        if (ok) dismissError()
        return ok
    }

    // =================================================================
    // Sinais de ciclo de vida do host (religados pelo LauncherRoot)
    // =================================================================

    /** ON_STOP do MainActivity: algo cobriu o Launcher (jogo normal aberto). */
    fun onHostActivityStopped() {
        if (_state.value is State.Loading) {
            hostCovered = true
        }
    }

    /** ON_RESUME do MainActivity: por segurança, limpa estado órfão. */
    fun onHostActivityResumed() {
        // Se o overlay ainda está exibindo carregamento quando o host volta
        // (jogo fechou sem cobrir o host), o timeout resolve; nada a fazer.
    }

    // =================================================================
    // Internos
    // =================================================================

    /**
     * A activity do jogo está no topo de uma tarefa DO PRÓPRIO APP? O stub
     * dos jogos virtuais é uma activity do pacote hospedeiro, então
     * getRunningTasks (limitado às próprias tarefas — sem permissão) revela
     * quando o jogo ganhou a tela. MainActivity conta como "não pronto".
     */
    private fun isGameTopmost(activity: Activity): Boolean {
        return runCatching {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val main = activity.javaClass.name
            am.getRunningTasks(15).orEmpty().any { task ->
                val top = task.topActivity ?: return@any false
                top.packageName == activity.packageName && top.className != main
            }
        }.getOrDefault(false)
    }

    /** Aplica a orientação do jogo ANTES do overlay (sem rotação no meio da animação). */
    private fun prepareOrientation(activity: Activity, game: Game) {
        val orientation = runCatching {
            val vpkg = currentVpkg
            if (vpkg != null) {
                AppGraph.virtual.launchScreenOrientation(vpkg)
            } else {
                val pkg = game.primaryPackage ?: return
                val info = activity.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                info.activities?.firstOrNull()?.screenOrientation
            }
        }.getOrNull()?.takeIf { it in HONORED_ORIENTATIONS }
        if (orientation != null) {
            savedOrientation = activity.requestedOrientation
            activity.requestedOrientation = orientation
        }
    }

    private fun restoreOrientation() {
        val activity = activityRef?.get()
        val saved = savedOrientation
        if (activity != null && saved != null) {
            runCatching { activity.requestedOrientation = saved }
        }
        savedOrientation = null
    }

    /** Som curto + vibração leve (opcional, desligado por padrão). */
    private fun playLaunchFeedback(activity: Activity) {
        val enabled = runCatching {
            AppGraph.settings.settings.value.launchFeedbackEnabled
        }.getOrDefault(false)
        if (!enabled) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            android.os.Handler(activity.mainLooper).postDelayed({ tone.release() }, 400)
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

}
