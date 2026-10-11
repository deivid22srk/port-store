package com.deivid22srk.portstore.virtual.gex

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.WindowManager
import black.android.app.BRActivity
import top.niunaijun.blackbox.app.GameExperienceBridge
import top.niunaijun.blackbox.utils.Slog

/**
 * Lado do jogo (processo ":pN") da experiência de jogo EMBEDADA.
 *
 * Quando o Game Launcher abre o jogo numa VirtualDisplay (Surface dentro da
 * UI do console), o lançamento chega com os extras [EXTRA_DISPLAY_ID] e
 * [EXTRA_SOCKET_NAME]. Este hook roda ANTES do onCreate da activity do jogo:
 *
 *  1. verifica em qual display a janela do jogo vai nascer. O caminho
 *     esperado é a própria activity já estar na VirtualDisplay (o stub foi
 *     lançado com ActivityOptions.setLaunchDisplayId e a display acompanha o
 *     token da activity). Se por algum motivo o sistema não propagou, a
 *     janela é REDIRECIONADA com Window.setWindowManager para a WindowManager
 *     da VirtualDisplay — antes do primeiro addView (que só acontece no
 *     resume), então não há janela órfã na display principal;
 *  2. liga o [GameInputServer] no socket determinístico do pacote, para o
 *     hospedeiro entregar toque/gamepad e receber o HELLO de confirmação.
 *
 * Qualquer falha aqui é engolida com log: o jogo continua abrindo em tela
 * cheia (comportamento clássico) e o hospedeiro cai no modo de fallback.
 */
object GexEmbedding : GameExperienceBridge.DisplayHook {

    private const val TAG = "GexEmbedding"

    /** Extra do intent de lançamento: id da VirtualDisplay do hospedeiro. */
    const val EXTRA_DISPLAY_ID = "gex_display_id"

    /** Extra do intent de lançamento: nome do socket de input (abstract). */
    const val EXTRA_SOCKET_NAME = "gex_socket_name"

    @Volatile
    private var currentActivity: Activity? = null

    /** Display de embedding da activity atual (-1 = modo clássico). */
    @Volatile
    var embeddedDisplayId: Int = -1
        private set

    override fun onGameActivityCreating(gameActivity: Activity) {
        val intent = gameActivity.intent ?: return
        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        if (displayId <= 0) {
            // Lançamento clássico (tela cheia): nada a fazer.
            return
        }
        currentActivity = gameActivity
        embeddedDisplayId = displayId

        ensureDisplay(gameActivity, displayId)

        val socketName = intent.getStringExtra(EXTRA_SOCKET_NAME)
        if (!socketName.isNullOrBlank()) {
            GameInputServer.start(socketName, displayId)
        }
        Slog.i(TAG, "embedded game activity: display=$displayId socket=$socketName")
    }

    /**
     * Activity do jogo em condições de receber input (não finalizando). O
     * servidor usa isso para despachar os eventos do hospedeiro.
     */
    fun currentGameActivity(): Activity? =
        currentActivity?.takeIf { !it.isFinishing && !it.isDestroyed }

    /**
     * Garante que a janela do jogo nasça na VirtualDisplay [displayId]. Se a
     * propagação automática (pelo token do stub) já acertou, é um no-op.
     */
    private fun ensureDisplay(activity: Activity, displayId: Int) {
        val current = runCatching {
            @Suppress("DEPRECATION")
            activity.window.windowManager.defaultDisplay.displayId
        }.getOrNull()
        if (current == displayId) {
            Slog.i(TAG, "display propagada automaticamente ($current)")
            return
        }
        Slog.w(TAG, "display da janela é $current, esperado $displayId — redirecionando")
        try {
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return
            val display = dm.getDisplay(displayId)
            if (display == null) {
                Slog.w(TAG, "VirtualDisplay $displayId não visível neste processo")
                return
            }
            val targetWm = activity.createDisplayContext(display)
                .getSystemService(WindowManager::class.java)
                ?: return
            val token: IBinder? = runCatching {
                BRActivity.get(activity).mToken()
            }.getOrNull() ?: activity.window.attributes.token
            if (token == null) {
                Slog.w(TAG, "token da activity indisponível — sem redirecionamento")
                return
            }
            activity.window.setWindowManager(
                targetWm,
                token,
                activity.componentName?.className ?: activity.localClassName,
            )
            Slog.i(TAG, "janela redirecionada para a display $displayId")
        } catch (t: Throwable) {
            Slog.w(TAG, "redirecionamento de display falhou: ${t.message}")
        }
    }

    /** Encerra o estado (processo do jogo morrendo / sessão reiniciada). */
    fun reset() {
        currentActivity = null
        embeddedDisplayId = -1
    }
}
