package com.deivid22srk.portstore.gex

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.virtual.gex.DoubleSwipeDetector
import com.deivid22srk.portstore.virtual.gex.GameExperiencePrefs

/**
 * Tela do jogo EMBEDADO do Game Launcher (modo console): o jogo virtual
 * renderiza num SurfaceView ligado a uma VirtualDisplay e TODO o input
 * (toque + gamepad) é entregue ao jogo pela ponte local — o Launcher é que
 * tem o foco, então o menu rápido aqui é Compose nativo (sem views
 * desenhadas dentro do processo do jogo) e o botão Home do controle nunca
 * disputa com o sistema.
 *
 * Fallback: se o jogo não confirmar o embedding (sem HELLO) ou a conexão
 * cair, a tela cai para a abertura clássica em tela cheia
 * ([GameExperienceController] via [onFallback]).
 */
@Composable
fun GexGameScreen(
    gameId: String,
    onExit: () -> Unit,
    onFallback: (gameId: String) -> Unit,
) {
    val context = LocalContext.current
    val vpkg = remember(gameId) {
        AppGraph.virtual.packageNameFor(gameId)
            ?.takeIf { AppGraph.virtual.isInstalled(it) }
    }

    // Pacote inválido: fallback imediato (o caller resolve o Game no catálogo).
    LaunchedEffect(vpkg) {
        if (vpkg == null) onFallback(gameId)
    }
    if (vpkg == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    // Jogo rodando em modo clássico (tela cheia, sem sessão embedada): não
    // tenta embedar — abre pelo fluxo antigo direto.
    LaunchedEffect(vpkg) {
        AppGraph.virtual.refreshRunning()
        if (AppGraph.virtual.isRunning(vpkg) && GexGameSessionManager.sessionFor(vpkg) == null) {
            onFallback(gameId)
        }
    }

    val session = remember(vpkg) { GexGameSessionManager.getOrCreate(context, vpkg) }
    val phase by session.phase.collectAsState()

    val killGame = remember(vpkg) {
        {
            GexGameSessionManager.kill(vpkg)
            onExit()
        }
    }
    val menu = remember { GexMenuState() }
    val surfaceViewRef = remember { mutableStateOf<GexGameSurfaceView?>(null) }
    val router = remember(vpkg) {
        GexConsoleRouter(
            context = context,
            session = session,
            menu = menu,
            onExit = onExit,
            onKill = killGame,
        )
    }

    // Falha (sem embedding ou conexão perdida): encerra a sessão e cai no modo clássico.
    LaunchedEffect(phase) {
        if (phase == GexGameSessionManager.Phase.FAILED) {
            GexGameSessionManager.dispose(vpkg)
            onFallback(gameId)
        }
    }

    // Gesto de voltar do sistema: fecha o menu; fora dele sai da tela (o jogo continua rodando).
    BackHandler {
        if (menu.overlayOpen) menu.closeAll() else onExit()
    }

    // Devolve o foco ao SurfaceView quando o menu fecha (teclas voltam ao jogo).
    LaunchedEffect(menu.overlay) {
        if (menu.overlay == null) surfaceViewRef.value?.requestFocus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                GexGameSurfaceView(ctx).apply {
                    router = this@GexGameScreen.router
                    surfaceViewRef.value = this
                    onSurfaceReady = { surface, width, height ->
                        val dpi = ctx.resources.displayMetrics.densityDpi
                        if (session.ensureDisplay(surface, width, height, dpi)) {
                            Thread {
                                if (!session.launchIfNeeded()) session.markFailed()
                            }.start()
                        } else {
                            session.markFailed()
                        }
                    }
                    onSurfaceGone = { session.detachSurface() }
                    post { requestFocus() }
                }
            },
            update = { view -> view.router = router },
            modifier = Modifier.fillMaxSize(),
        )

        // Aguardando o jogo embedar (HELLO): fundo escuro + status.
        if (phase == GexGameSessionManager.Phase.WAITING) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = LauncherLime,
                        modifier = Modifier.size(42.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.gex_embed_status_starting),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gex_embed_status_video),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }
        }

        // Menu rápido / diálogo Home / confirmação de encerrar — Compose nativo
        // por cima da "tela" do jogo.
        GexMenuOverlays(menu, onGoHome = onExit, onKill = killGame)
    }
}

// =====================================================================
// Menu (drawer + diálogos) — estado navegável por gamepad
// =====================================================================

enum class GexOverlay { DRAWER, HOME_DIALOG, KILL_CONFIRM }

enum class GexMenuAction { GO_HOME, CONTINUE, CONFIRM_KILL, KILL, BACK_TO_HOME_DIALOG }

/** Estado do menu rápido do console; o router de input comanda por teclas. */
class GexMenuState {

    var overlay by mutableStateOf<GexOverlay?>(null)
        private set
    var selected by mutableIntStateOf(0)
        private set

    val overlayOpen: Boolean get() = overlay != null

    fun openDrawer() {
        selected = 0
        overlay = GexOverlay.DRAWER
    }

    fun openHomeDialog() {
        selected = 0
        overlay = GexOverlay.HOME_DIALOG
    }

    fun openKillConfirm() {
        selected = 0
        overlay = GexOverlay.KILL_CONFIRM
    }

    fun closeAll() {
        overlay = null
        selected = 0
    }

    /**
     * Navegação por gamepad com o menu aberto. Retorna a ação ativada (ou
     * null para navegação/fechamento). Teclas com menu aberto NUNCA chegam
     * ao jogo.
     */
    fun onKey(keyCode: Int, action: Int): GexMenuAction? {
        val current = overlay ?: return null
        if (action != KeyEvent.ACTION_DOWN) return null
        val count = optionsCount(current)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> if (count > 0) selected = (selected - 1 + count) % count

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> if (count > 0) selected = (selected + 1) % count

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> return applyActivate(current)

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B,
            -> when (current) {
                GexOverlay.KILL_CONFIRM -> openHomeDialog()
                else -> closeAll()
            }

            // Demais teclas ficam retidas no menu (nada vaza para o jogo).
        }
        return null
    }

    /** Toque direto num item (dedo/mouse). Retorna a ação ativada. */
    fun activateFromTap(index: Int): GexMenuAction? {
        selected = index
        val current = overlay ?: return null
        return applyActivate(current)
    }

    fun optionsCount(overlay: GexOverlay): Int = when (overlay) {
        GexOverlay.DRAWER -> 3
        GexOverlay.HOME_DIALOG -> 3
        GexOverlay.KILL_CONFIRM -> 2
    }

    /** Mapeia a opção selecionada para a ação (ordem por overlay). */
    fun activate(overlay: GexOverlay): GexMenuAction = when (overlay) {
        GexOverlay.DRAWER -> when (selected) {
            0 -> GexMenuAction.GO_HOME
            1 -> GexMenuAction.CONTINUE
            else -> GexMenuAction.CONFIRM_KILL
        }

        GexOverlay.HOME_DIALOG -> when (selected) {
            0 -> GexMenuAction.CONTINUE
            1 -> GexMenuAction.GO_HOME
            else -> GexMenuAction.CONFIRM_KILL
        }

        GexOverlay.KILL_CONFIRM -> if (selected == 0) GexMenuAction.KILL else GexMenuAction.BACK_TO_HOME_DIALOG
    }

    private fun applyActivate(overlay: GexOverlay): GexMenuAction {
        val action = activate(overlay)
        when (action) {
            GexMenuAction.CONTINUE,
            GexMenuAction.GO_HOME,
            -> closeAll()

            GexMenuAction.CONFIRM_KILL -> openKillConfirm()
            GexMenuAction.BACK_TO_HOME_DIALOG -> openHomeDialog()
            GexMenuAction.KILL -> Unit // router executa (encerra sessão + sai)
        }
        return action
    }
}

@Composable
private fun GexMenuOverlays(
    menu: GexMenuState,
    onGoHome: () -> Unit,
    onKill: () -> Unit,
) {
    val overlay = menu.overlay ?: return
    Box(Modifier.fillMaxSize()) {
        // Scrim: tocar fora fecha (o foco volta ao jogo).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { menu.closeAll() },
        )

        when (overlay) {
            GexOverlay.DRAWER -> AnimatedVisibility(
                visible = true,
                enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(220)),
                exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(180)),
            ) {
                GexDrawerPanel(menu, onGoHome, onKill)
            }

            GexOverlay.HOME_DIALOG -> GexCenteredPanel {
                GexTitle(stringResource(com.deivid22srk.portstore.virtual.R.string.gex_home_title))
                GexMessage(stringResource(com.deivid22srk.portstore.virtual.R.string.gex_home_message))
                Spacer(Modifier.height(10.dp))
                GexOption(menu, index = 0, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_continue), onGoHome = onGoHome, onKill = onKill)
                GexOption(menu, index = 1, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_go_home), onGoHome = onGoHome, onKill = onKill)
                GexOption(menu, index = 2, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_kill_game), destructive = true, onGoHome = onGoHome, onKill = onKill)
            }

            GexOverlay.KILL_CONFIRM -> GexCenteredPanel {
                GexTitle(stringResource(com.deivid22srk.portstore.virtual.R.string.gex_kill_confirm_title))
                GexMessage(stringResource(com.deivid22srk.portstore.virtual.R.string.gex_kill_confirm_message))
                Spacer(Modifier.height(10.dp))
                GexOption(menu, index = 0, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_kill_game), destructive = true, onGoHome = onGoHome, onKill = onKill)
                GexOption(menu, index = 1, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_cancel), onGoHome = onGoHome, onKill = onKill)
            }
        }
    }
}

@Composable
private fun GexDrawerPanel(menu: GexMenuState, onGoHome: () -> Unit, onKill: () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Color(0xE6141418))
            .padding(18.dp),
    ) {
        GexTitle(stringResource(com.deivid22srk.portstore.virtual.R.string.gex_menu_title))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(com.deivid22srk.portstore.virtual.R.string.gex_menu_running),
            style = MaterialTheme.typography.labelSmall,
            color = LauncherLime,
        )
        Spacer(Modifier.height(16.dp))
        GexOption(menu, index = 0, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_go_home), onGoHome = onGoHome, onKill = onKill)
        Spacer(Modifier.height(8.dp))
        GexOption(menu, index = 1, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_continue), onGoHome = onGoHome, onKill = onKill)
        Spacer(Modifier.height(8.dp))
        GexOption(menu, index = 2, label = stringResource(com.deivid22srk.portstore.virtual.R.string.gex_kill_game), destructive = true, onGoHome = onGoHome, onKill = onKill)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(com.deivid22srk.portstore.virtual.R.string.gex_close_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun GexCenteredPanel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(28.dp)
                .width(340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6141418))
                .padding(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun GexTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
}

@Composable
private fun GexMessage(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
}

@Composable
private fun GexOption(
    menu: GexMenuState,
    index: Int,
    label: String,
    onGoHome: () -> Unit,
    onKill: () -> Unit,
    destructive: Boolean = false,
) {
    val selected = menu.overlayOpen && menu.selected == index
    val labelColor = if (destructive) LauncherRed else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .scale(if (selected) 1.03f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LauncherLime.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) LauncherLime else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable {
                when (menu.activateFromTap(index)) {
                    GexMenuAction.GO_HOME -> onGoHome()
                    GexMenuAction.KILL -> onKill()
                    else -> Unit
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
    }
}

// =====================================================================
// SurfaceView + router de input
// =====================================================================

/** SurfaceView do console: captura toque/teclas/analógico e entrega ao router. */
internal class GexGameSurfaceView(context: Context) : SurfaceView(context, null, 0) {

    var router: GexConsoleRouter? = null
    var onSurfaceReady: ((Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceReady?.invoke(holder.surface, width, height)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    onSurfaceReady?.invoke(holder.surface, width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceGone?.invoke()
            }
        })
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        router?.onTouch(event, width, height)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        router?.onKey(keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        router?.onKey(keyCode, event) ?: super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        router?.onGeneric(event, width, height)
        return true
    }
}

/**
 * Router de input do console: decide, para cada evento, se vai para o menu
 * (Home do gamepad, gesto, teclas com overlay aberto) ou para o jogo via
 * ponte local. As coordenadas de toque são reescaladas para o espaço da
 * VirtualDisplay antes do envio.
 */
internal class GexConsoleRouter(
    private val context: Context,
    private val session: GexGameSessionManager.Session,
    private val menu: GexMenuState,
    private val onExit: () -> Unit,
    private val onKill: () -> Unit,
) {

    private companion object {
        const val HOLD_MENU_MS = 600L
        const val COMBO_HOME_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val swipe = DoubleSwipeDetector { menu.openDrawer() }

    private var homeDownAt = 0L
    private var homeMenuFired = false
    private val homeHoldRunnable = Runnable {
        if (homeDownAt > 0L && !homeMenuFired) {
            homeMenuFired = true
            menu.openDrawer()
        }
    }

    private var selectDown = false
    private var startDown = false
    private val comboRunnable = Runnable {
        if (selectDown && startDown) menu.openHomeDialog()
    }

    private fun updateCombo() {
        mainHandler.removeCallbacks(comboRunnable)
        if (selectDown && startDown) mainHandler.postDelayed(comboRunnable, COMBO_HOME_MS)
    }

    private val gestureEnabled: Boolean
        get() = GameExperiencePrefs.isQuickMenuGestureEnabled(context)

    fun onTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (menu.overlayOpen) return // overlay Compose consome os toques
        if (gestureEnabled && swipe.onTouchEvent(event, viewWidth)) {
            return // gesto disparou: menu abre; o toque consumido não vai ao jogo
        }
        val (sx, sy) = session.scaleFactors(viewWidth, viewHeight)
        session.sendTouch(event, sx, sy)
    }

    fun onGeneric(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (menu.overlayOpen) return
        val (sx, sy) = session.scaleFactors(viewWidth, viewHeight)
        session.sendGenericMotion(event, sx, sy)
    }

    fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (menu.overlayOpen) {
            val action = menu.onKey(keyCode, event.action)
            when (action) {
                GexMenuAction.GO_HOME -> onExit()
                GexMenuAction.KILL -> onKill()
                else -> Unit
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                // Curto = diálogo Home; segurado = menu rápido. Nunca vai ao jogo.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    homeDownAt = SystemClock.uptimeMillis()
                    homeMenuFired = false
                    mainHandler.postDelayed(homeHoldRunnable, HOLD_MENU_MS)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    mainHandler.removeCallbacks(homeHoldRunnable)
                    val held = SystemClock.uptimeMillis() - homeDownAt
                    homeDownAt = 0L
                    if (!homeMenuFired && held in 1 until HOLD_MENU_MS) menu.openHomeDialog()
                }
                return true
            }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                selectDown = event.action == KeyEvent.ACTION_DOWN
                updateCombo()
                session.sendKey(event)
                return true
            }

            KeyEvent.KEYCODE_BUTTON_START -> {
                startDown = event.action == KeyEvent.ACTION_DOWN
                updateCombo()
                session.sendKey(event)
                return true
            }

            else -> {
                session.sendKey(event)
                return true
            }
        }
    }
}

/** Cores da experiência (mesma linguagem do Launcher: limão sobre escuro). */
val LauncherLime = Color(0xFFDFFF00)
val LauncherRed = Color(0xFFFF5449)

// =====================================================================
// Extensões da sessão usadas pelo router (cliente de input é interno)
// =====================================================================

internal fun GexGameSessionManager.Session.sendTouch(event: MotionEvent, scaleX: Float, scaleY: Float) {
    sendMotion(event, scaleX, scaleY, generic = false)
}

internal fun GexGameSessionManager.Session.sendGenericMotion(event: MotionEvent, scaleX: Float, scaleY: Float) {
    sendMotion(event, scaleX, scaleY, generic = true)
}
