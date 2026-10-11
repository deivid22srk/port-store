package com.deivid22srk.portstore.gex

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.deivid22srk.portstore.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ciclo de vida da experiência de jogo EMBEDADA no Game Launcher (modo
 * console): o jogo virtual renderiza numa [VirtualDisplay] cuja Surface é
 * um SurfaceView dentro da UI do Launcher, e todo o input (toque, gamepad)
 * é entregue ao jogo pela [GexInputClient].
 *
 * Estados por sessão ([Session.phase]):
 *  - [Phase.WAITING]: display criada, jogo lançado; aguardando o HELLO do
 *    processo do jogo (activity na VirtualDisplay + servidor de input no ar).
 *  - [Phase.EMBEDDED]: HELLO recebido — o jogo está renderizando dentro do
 *    console; input fluindo.
 *  - [Phase.FAILED]: orçamento de conexão estourou ou a conexão caiu — a
 *    tela do jogo faz fallback para a abertura clássica em tela cheia.
 *
 * A sessão SOBREVIVE à saída da tela (Voltar ao início): o jogo continua
 * rodando (áudio inclusive) e a reentrada religa a Surface. "Encerrar jogo"
 * (ou desinstalar) derruba de verdade via [kill].
 *
 * Socket determinístico por pacote ("portstore_gex_<vpkg>") — o nome viaja
 * no intent do jogo e o servidor sobe lá (GexEmbedding/GameInputServer).
 */
object GexGameSessionManager {

    enum class Phase { WAITING, EMBEDDED, FAILED }

    /** Máximo entre o launch e o HELLO do jogo (primeiro boot pode ser lento). */
    const val EMBED_TIMEOUT_MS = 12_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Escopo compartilhado acessível às sessões (classe aninhada, não inner). */
    internal val sessionScope: CoroutineScope get() = scope
    private val sessions = LinkedHashMap<String, Session>()

    fun sessionFor(vpkg: String): Session? = sessions[vpkg]

    /**
     * Cria (ou devolve) a sessão do jogo. Não abre nada: a tela do jogo
     * chama [Session.ensureDisplay] quando o SurfaceView fica pronto e
     * [Session.launchIfNeeded] na sequência.
     */
    fun getOrCreate(context: Context, vpkg: String): Session =
        synchronized(sessions) {
            sessions.getOrPut(vpkg) { Session(context.applicationContext, vpkg) }
        }

    /** Encerra o jogo de verdade (processo + display + socket) e apaga a sessão. */
    fun kill(vpkg: String) {
        val session = synchronized(sessions) { sessions.remove(vpkg) } ?: return
        scope.launch {
            runCatching { AppGraph.virtual.stop(vpkg) }
            AppGraph.virtual.refreshRunning()
        }
        session.dispose()
    }

    /** Descarta a sessão sem parar o jogo (usado ao cair para o modo clássico). */
    fun dispose(vpkg: String) {
        val session = synchronized(sessions) { sessions.remove(vpkg) } ?: return
        session.dispose()
    }

    class Session internal constructor(
        private val appContext: Context,
        val vpkg: String,
    ) {

        val socketName: String = "portstore_gex_$vpkg"

        private val _phase = MutableStateFlow(Phase.WAITING)

        /** Estado observável da sessão (a tela do jogo reage a ele). */
        val phase: StateFlow<Phase> = _phase.asStateFlow()

        var displayId: Int = -1
            private set

        /** Resolução da VirtualDisplay em px (base do reescala do input). */
        var vdWidth: Int = 0
            private set

        var vdHeight: Int = 0
            private set

        var virtualDisplay: VirtualDisplay? = null
            private set

        private var launched = false
        private var timeoutJob: Job? = null
        private var clientJob: Job? = null
        private var client: GexInputClient? = null

        private fun onHello(@Suppress("UNUSED_PARAMETER") displayId: Int) {
            timeoutJob?.cancel()
            _phase.value = Phase.EMBEDDED
        }

        private fun onGiveUp() {
            if (_phase.value != Phase.EMBEDDED) return
            // Conexão caiu com o jogo já embedado (processo morreu): falha.
            _phase.value = Phase.FAILED
        }

        /**
         * Liga a [Surface] do SurfaceView do console à VirtualDisplay (cria a
         * display na primeira vez). Chamar na thread principal (surface
         * callbacks). Retorna false se a display não pôde ser criada.
         */
        fun ensureDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int): Boolean {
            virtualDisplay?.let { vd ->
                runCatching { vd.setSurface(surface) }
                return true
            }
            if (width <= 0 || height <= 0) return false
            val created = runCatching {
                val dm = appContext
                    .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    ?: return@runCatching null
                dm.createVirtualDisplay(
                    "portstore-gex-$vpkg",
                    width,
                    height,
                    densityDpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            }.getOrNull()
            if (created == null) {
                _phase.value = Phase.FAILED
                return false
            }
            virtualDisplay = created
            displayId = created.display.displayId
            vdWidth = width
            vdHeight = height
            return true
        }

        /** Jogo não está mais renderizando (saiu da tela): display fica em branco. */
        fun detachSurface() {
            runCatching { virtualDisplay?.setSurface(null) }
        }

        /** Marca falha (sem display, sem motor etc.) — a tela cai no fallback. */
        fun markFailed() {
            _phase.value = Phase.FAILED
        }

        /** Fatores de escala view do console -> espaço da VirtualDisplay. */
        fun scaleFactors(viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
            if (viewWidth <= 0 || viewHeight <= 0 || vdWidth <= 0 || vdHeight <= 0) {
                return 1f to 1f
            }
            return (vdWidth.toFloat() / viewWidth) to (vdHeight.toFloat() / viewHeight)
        }

        /** Entrega um toque/analógico ao jogo (nulo se a ponte não está no ar). */
        fun sendMotion(event: MotionEvent, scaleX: Float, scaleY: Float, generic: Boolean) {
            client?.sendMotion(event, scaleX, scaleY, generic)
        }

        /** Entrega uma tecla ao jogo (nulo se a ponte não está no ar). */
        fun sendKey(event: KeyEvent) {
            client?.sendKey(event)
        }

        /**
         * Lança o jogo na display (uma única vez por sessão) e abre o cliente
         * de input. Chamar em IO. Retorna false para fallback imediato.
         */
        fun launchIfNeeded(): Boolean {
            if (launched) return true
            if (displayId <= 0) return false
            val ok = runCatching {
                AppGraph.virtual.launchEmbedded(vpkg, displayId, socketName)
            }.getOrDefault(false)
            if (!ok) return false
            launched = true
            startClient(EMBED_TIMEOUT_MS)
            return true
        }

        /** Religa só o cliente de input (sessão de reentrada, jogo vivo). */
        fun reconnectClient(budgetMs: Long) {
            if (clientJob?.isActive == true) return
            startClient(budgetMs)
        }

        private fun startClient(budgetMs: Long) {
            timeoutJob?.cancel()
            clientJob?.cancel()
            client?.close()
            val newClient = GexInputClient(
                socketName,
                onHello = ::onHello,
                onGiveUp = ::onGiveUp,
            )
            client = newClient
            clientJob = GexGameSessionManager.sessionScope.launch {
                if (_phase.value == Phase.WAITING) {
                    timeoutJob = GexGameSessionManager.sessionScope.launch {
                        delay(budgetMs)
                        if (_phase.value == Phase.WAITING) _phase.value = Phase.FAILED
                    }
                }
                withContext(Dispatchers.IO) { newClient.connect(budgetMs) }
            }
        }

        /** Libera display e cliente (o jogo NÃO é parado aqui — ver [kill]). */
        fun dispose() {
            timeoutJob?.cancel()
            clientJob?.cancel()
            client?.close()
            client = null
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            displayId = -1
        }
    }
}
