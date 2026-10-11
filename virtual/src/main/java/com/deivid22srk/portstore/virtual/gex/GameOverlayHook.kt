package com.deivid22srk.portstore.virtual.gex

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackbox.app.GameExperienceBridge

/**
 * Hook da experiência de jogo: implementa [GameExperienceBridge.Hook] nos
 * processos virtuais ":pN" e conecta o dispatcher de entrada do motor à UI
 * dos overlays ([GameOverlayUi]).
 *
 * Mapeamento de botões (ver docs/GAME_EXPERIENCE.md):
 *  - Home/Guide do gamepad (KEYCODE_BUTTON_MODE): toque curto abre o diálogo
 *    "Voltar ao início?"; segurado ~600 ms abre o menu rápido. SEMPRE
 *    consumido — nunca chega ao jogo.
 *  - Select + Start segurados ~1 s: alternativa ao botão Home (aparelhos e
 *    controles em que o sistema captura o Guide). Select/Start em si NÃO são
 *    consumidos — o jogo continua recebendo os dois botões normalmente.
 *  - Toda outra tecla: devolvida ao jogo sem tocar em nada.
 *
 * Gesto do menu rápido: duas deslizadas rápidas da esquerda para a direita
 * ([DoubleSwipeDetector]) — observadas sem atrasar o jogo; ao disparar, os
 * toques em andamento são cancelados (ACTION_CANCEL) e o menu abre.
 */
internal class GameOverlayHook(private val appContext: android.content.Context) :
    GameExperienceBridge.Hook {

    companion object {
        /** Activity inicial do hospedeiro (início do Game Launcher). */
        const val HOST_MAIN_ACTIVITY = "com.deivid22srk.portstore.MainActivity"

        /** Segurar Home/Guide por este tempo abre o menu rápido (ms). */
        const val HOLD_MENU_MS = 600L

        /** Segurar Select+Start por este tempo abre o diálogo Home (ms). */
        const val COMBO_HOME_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ui: GameOverlayUi? = null
    private val swipe = DoubleSwipeDetector {
        mainHandler.post { openQuickMenu() }
    }

    /** Activity do jogo em primeiro plano (alimentada em onGameResumed). */
    @Volatile
    private var lastActivity: Activity? = null

    // Estado do botão Home/Guide (curto = diálogo; segurado = menu).
    private var homeDownAt = 0L
    private var homeMenuFired = false
    private val homeHoldRunnable = Runnable {
        if (homeDownAt > 0L && !homeMenuFired) {
            homeMenuFired = true
            openQuickMenu()
        }
    }

    // Estado da combinação Select+Start.
    private var selectDown = false
    private var startDown = false
    private val comboRunnable = Runnable {
        if (selectDown && startDown) {
            showHomeDialog()
        }
    }

    private val gestureEnabled: Boolean
        get() = GameExperiencePrefs.isQuickMenuGestureEnabled(appContext)

    // =================================================================
    // GameExperienceBridge.Hook
    // =================================================================

    override fun onGameKeyEvent(activity: Activity, event: KeyEvent): Boolean {
        val overlay = ui
        if (overlay != null && overlay.isOverlayOpen) {
            // Menu/diálogo aberto: as teclas ficam com o overlay (nada vaza).
            return overlay.onKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    homeDownAt = SystemClock.uptimeMillis()
                    homeMenuFired = false
                    mainHandler.postDelayed(homeHoldRunnable, HOLD_MENU_MS)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    mainHandler.removeCallbacks(homeHoldRunnable)
                    val held = SystemClock.uptimeMillis() - homeDownAt
                    homeDownAt = 0L
                    if (!homeMenuFired && held in 1 until HOLD_MENU_MS) {
                        showHomeDialog()
                    }
                }
                // Down e Up sempre consumidos: o jogo nunca vê o Guide.
                return true
            }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                selectDown = event.action == KeyEvent.ACTION_DOWN
                updateCombo()
                return false // Select pertence ao jogo
            }

            KeyEvent.KEYCODE_BUTTON_START -> {
                startDown = event.action == KeyEvent.ACTION_DOWN
                updateCombo()
                return false // Start pertence ao jogo
            }

            else -> return false
        }
    }

    override fun onGameTouchEvent(activity: Activity, event: MotionEvent): Boolean {
        val overlay = ui
        if (overlay != null && overlay.isOverlayOpen) {
            // Delega: o menu lateral é a view de topo e consome os toques
            // (scrim fecha, itens acionam). O jogo está sob o scrim.
            return false
        }
        if (!gestureEnabled) return false
        val handled = swipe.onTouchEvent(event, activity.resources.displayMetrics.widthPixels)
        if (handled) {
            // O jogo já recebeu DOWN/MOVE da 2ª deslizada: envia ACTION_CANCEL
            // para não disparar ação acidental dentro do jogo.
            cancelTouchesInGame(activity, event)
        }
        return handled
    }

    override fun onGameGenericMotionEvent(activity: Activity, event: MotionEvent): Boolean {
        val overlay = ui
        return overlay != null && overlay.shouldConsumeGenericMotion()
    }

    override fun onGameResumed(activity: Activity) {
        lastActivity = activity
        // Uma única UI por processo; religa a cada activity do jogo.
        if (ui == null) {
            ui = GameOverlayUi(activity, callbacks)
        }
        swipe.reset()
        homeDownAt = 0L
        homeMenuFired = false
    }

    override fun onGamePaused(activity: Activity) {
        mainHandler.removeCallbacks(homeHoldRunnable)
        mainHandler.removeCallbacks(comboRunnable)
        selectDown = false
        startDown = false
        swipe.reset()
        // Jogo perdeu a frente: nada de overlay pendurado nele.
        if (lastActivity === activity) {
            mainHandler.post {
                ui?.closeQuickMenu(animated = false)
            }
        }
    }

    // =================================================================
    // Ações dos overlays
    // =================================================================

    private val callbacks = object : GameOverlayUi.Callbacks {
        override fun onGoHomeToLauncher() {
            goHomeToLauncher()
        }

        override fun onContinuePlaying() = Unit

        override fun onKillGame() {
            killCurrentGame()
        }
    }

    /**
     * Traz a MainActivity (início do Game Launcher) para a frente. O jogo
     * fica ABAIXO dela, suspenso (onStop do SDL), e pode ser retomado
     * abrindo o jogo de novo no Launcher.
     */
    private fun goHomeToLauncher() {
        runCatching {
            val activity = lastActivity ?: return
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName(activity.packageName, HOST_MAIN_ACTIVITY)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            activity.startActivity(intent)
        }.onFailure { it.printStackTrace() }
    }

    /** Encerra o processo virtual do jogo (limpo) e volta ao Launcher. */
    private fun killCurrentGame() {
        runCatching {
            val pkg = BActivityThread.getAppPackageName()
            if (!pkg.isNullOrBlank()) {
                BlackBoxCore.getBPackageManager().stopPackage(pkg, 0)
            }
        }.onFailure { it.printStackTrace() }
        mainHandler.postDelayed({ goHomeToLauncher() }, 300)
    }

    private fun showHomeDialog() {
        mainHandler.post {
            ui?.showHomeDialog()
        }
    }

    private fun openQuickMenu() {
        val overlay = ui ?: return
        val activity = lastActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val label = runCatching {
            activity.applicationInfo.loadLabel(activity.packageManager).toString()
        }.getOrDefault(activity.packageName)
        val icon = runCatching {
            activity.applicationInfo.loadIcon(activity.packageManager)
        }.getOrNull()
        overlay.showQuickMenu(label, icon)
    }

    private fun cancelTouchesInGame(activity: Activity, original: MotionEvent) {
        runCatching {
            val cancel = MotionEvent.obtain(
                original.downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL,
                original.x,
                original.y,
                original.metaState,
            )
            activity.window?.decorView?.dispatchTouchEvent(cancel)
            cancel.recycle()
        }.onFailure { it.printStackTrace() }
    }
}
