package com.deivid22srk.portstore.virtual.gex

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Overlays desenhados DENTRO do processo do jogo (":pN"), no visual do Game
 * Launcher (fundo escuro, cantos arredondados, destaque amarelo-limão):
 *
 *  - [showHomeDialog]: diálogo "Voltar ao início?" aberto pelo botão
 *    Home/Guide do gamepad (ou Select+Start). Foco inicial em "Continuar
 *    jogando" para evitar saídas acidentais.
 *  - [showQuickMenu]: menu lateral estilo Navigation Drawer, que entra
 *    deslizando pela esquerda (gesto de duas deslizadas, ou Home segurada).
 *
 * Tudo em Views clássicas: o processo do jogo não tem Compose montado e o
 * custo de abrir/fechar é desprezível. Sem SYSTEM_ALERT_WINDOW: o diálogo é
 * uma janela do próprio app e o menu é uma view no content do jogo.
 *
 * Gamepad: D-pad move o foco, A (ou Enter/D-pad-center) aciona, B fecha.
 * Touch: tocar num item aciona; tocar fora (scrim) fecha.
 */
internal class GameOverlayUi(
    private val activity: Activity,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Voltar ao início do Game Launcher (jogo fica suspenso em segundo plano). */
        fun onGoHomeToLauncher()

        /** Fechar o overlay e devolver o controle ao jogo. */
        fun onContinuePlaying()

        /** Encerrar o jogo de forma limpa (após confirmação). */
        fun onKillGame()
    }

    private var homeDialog: Dialog? = null
    private var killDialog: Dialog? = null
    private var drawerHost: ViewGroup? = null
    private val drawerItems = ArrayList<TextView>()

    val isOverlayOpen: Boolean
        get() = homeDialog?.isShowing == true || killDialog?.isShowing == true || drawerHost != null

    // =================================================================
    // Diálogo "Voltar ao início?"
    // =================================================================

    fun showHomeDialog() {
        if (homeDialog?.isShowing == true) return
        closeQuickMenu(animated = false)
        val dlg = Dialog(activity)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setCancelable(true)

        val root = GexStyle.panelColumn(activity)
        root.addView(GexStyle.title(activity, GexStyle.str(activity, R.string.gex_home_title)))
        root.addView(GexStyle.message(activity, GexStyle.str(activity, R.string.gex_home_message)))
        root.addView(GexStyle.spacer(activity, 10))

        val btnContinue = GexStyle.optionButton(activity, GexStyle.str(activity, R.string.gex_continue), destructive = false)
        val btnHome = GexStyle.optionButton(activity, GexStyle.str(activity, R.string.gex_go_home), destructive = false)
        val btnKill = GexStyle.optionButton(activity, GexStyle.str(activity, R.string.gex_kill_game), destructive = true)

        btnContinue.setOnClickListener {
            dismissHomeDialog()
            callbacks.onContinuePlaying()
        }
        btnHome.setOnClickListener {
            dismissHomeDialog()
            callbacks.onGoHomeToLauncher()
        }
        btnKill.setOnClickListener {
            dismissHomeDialog()
            showKillConfirmDialog()
        }
        // B/BACK no diálogo = continuar jogando (comportamento de cancelar).
        GexStyle.attachButtonKeys(btnContinue) { dismissHomeDialog() }
        GexStyle.attachButtonKeys(btnHome) { dismissHomeDialog() }
        GexStyle.attachButtonKeys(btnKill) { dismissHomeDialog() }

        root.addView(btnContinue)
        root.addView(GexStyle.spacer(activity, 6))
        root.addView(btnHome)
        root.addView(GexStyle.spacer(activity, 6))
        root.addView(btnKill)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.setOnDismissListener { if (homeDialog === dlg) homeDialog = null }
        dlg.show()
        homeDialog = dlg
        // Foco inicial em "Continuar jogando": o Home acidental não derruba o jogo.
        btnContinue.requestFocus()
    }

    private fun dismissHomeDialog() {
        homeDialog?.dismiss()
        homeDialog = null
    }

    private fun showKillConfirmDialog() {
        if (killDialog?.isShowing == true) return
        val dlg = Dialog(activity)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setCancelable(true)

        val root = GexStyle.panelColumn(activity)
        root.addView(GexStyle.title(activity, GexStyle.str(activity, R.string.gex_kill_confirm_title)))
        root.addView(GexStyle.message(activity, GexStyle.str(activity, R.string.gex_kill_confirm_message)))
        root.addView(GexStyle.spacer(activity, 10))

        val btnKill = GexStyle.optionButton(activity, GexStyle.str(activity, R.string.gex_kill_game), destructive = true)
        val btnCancel = GexStyle.optionButton(activity, GexStyle.str(activity, R.string.gex_cancel), destructive = false)
        btnKill.setOnClickListener {
            dismissKillDialog()
            callbacks.onKillGame()
        }
        btnCancel.setOnClickListener { dismissKillDialog() }
        GexStyle.attachButtonKeys(btnKill) { dismissKillDialog() }
        GexStyle.attachButtonKeys(btnCancel) { dismissKillDialog() }

        root.addView(btnCancel)
        root.addView(GexStyle.spacer(activity, 6))
        root.addView(btnKill)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.setOnDismissListener { if (killDialog === dlg) killDialog = null }
        dlg.show()
        killDialog = dlg
        // Foco inicial em Cancelar: encerrar é a ação destrutiva.
        btnCancel.requestFocus()
    }

    private fun dismissKillDialog() {
        killDialog?.dismiss()
        killDialog = null
    }

    // =================================================================
    // Menu rápido lateral (Navigation Drawer)
    // =================================================================

    fun showQuickMenu(gameLabel: String, icon: Drawable?) {
        if (drawerHost != null || homeDialog?.isShowing == true) return
        val ctx = activity

        val host = FrameLayout(ctx)
        host.isClickable = true
        host.isFocusable = true
        host.isFocusableInTouchMode = true

        val scrim = View(ctx)
        scrim.setBackgroundColor(GexStyle.SCRIM)
        scrim.setOnClickListener { closeQuickMenu(animated = true) }
        host.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val panelWidth = GexStyle.dp(ctx, 320f)
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GexStyle.PANEL)
        }
        host.addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
        )

        // Cabeçalho: ícone + nome do jogo em execução.
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.setPadding(GexStyle.dp(ctx, 20f), GexStyle.dp(ctx, 24f), GexStyle.dp(ctx, 20f), GexStyle.dp(ctx, 16f))
        if (icon != null) {
            val iconView = ImageView(ctx)
            iconView.setImageDrawable(icon)
            iconView.background = GexStyle.rounded(GexStyle.PANEL_HIGH, GexStyle.dp(ctx, 12f).toFloat())
            iconView.clipToOutline = true
            iconView.setPadding(GexStyle.dp(ctx, 4f), GexStyle.dp(ctx, 4f), GexStyle.dp(ctx, 4f), GexStyle.dp(ctx, 4f))
            header.addView(
                iconView,
                LinearLayout.LayoutParams(GexStyle.dp(ctx, 52f), GexStyle.dp(ctx, 52f)),
            )
            header.addView(GexStyle.spacer(activity, 12))
        }
        val nameColumn = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        nameColumn.addView(
            GexStyle.text(ctx, gameLabel, GexStyle.TEXT_PRIMARY, 16f, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        nameColumn.addView(
            GexStyle.text(ctx, GexStyle.str(ctx, R.string.gex_menu_running), GexStyle.TEXT_SECONDARY, 12f, bold = false),
        )
        header.addView(
            nameColumn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        drawerItems.clear()
        val items = listOf(
            GexStyle.str(ctx, R.string.gex_go_home) to { closeQuickMenu(animated = false); callbacks.onGoHomeToLauncher() },
            GexStyle.str(ctx, R.string.gex_continue) to { closeQuickMenu(animated = true); callbacks.onContinuePlaying() },
            GexStyle.str(ctx, R.string.gex_kill_game) to { closeQuickMenu(animated = false); showKillConfirmDialog() },
        )
        items.forEachIndexed { index, (label, action) ->
            val item = GexStyle.optionButton(ctx, label, destructive = index == 2)
            item.setOnClickListener { action() }
            // B nos itens fecha o menu; A aciona (attachButtonKeys cuida do B).
            GexStyle.attachButtonKeys(item) { closeQuickMenu(animated = true) }
            panel.addView(item)
            panel.addView(GexStyle.spacer(activity, 4))
            drawerItems.add(item)
        }

        // Dica de botões no rodapé, como no Launcher.
        val hint = GexStyle.text(ctx, GexStyle.str(ctx, R.string.gex_close_hint), GexStyle.TEXT_SECONDARY, 12f, bold = false)
        hint.setPadding(GexStyle.dp(ctx, 20f), GexStyle.dp(ctx, 12f), GexStyle.dp(ctx, 20f), GexStyle.dp(ctx, 20f))
        panel.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )

        // O host consome QUALQUER toque que sobre (nada vaza para o jogo).
        host.setOnTouchListener { _, _ -> true }

        activity.addContentView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        drawerHost = host

        // Entrada deslizante + fade do fundo (respeita a escala de animação).
        val reduced = GexStyle.animationsOff(ctx)
        scrim.alpha = if (reduced) 1f else 0f
        if (!reduced) {
            panel.translationX = -panelWidth.toFloat()
            scrim.animate().alpha(1f).setDuration(200).start()
            panel.animate()
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        drawerItems.firstOrNull()?.requestFocus()
    }

    fun closeQuickMenu(animated: Boolean) {
        val host = drawerHost ?: return
        drawerHost = null
        drawerItems.clear()
        val parent = host.parent as? ViewGroup
        if (parent == null || !animated || GexStyle.animationsOff(host.context)) {
            parent?.removeView(host)
            return
        }
        val panel = host.getChildAt(1)
        panel.animate().translationX(-panel.width.toFloat()).setDuration(200).withEndAction {
            (host.parent as? ViewGroup)?.removeView(host)
        }.start()
        host.getChildAt(0).animate().alpha(0f).setDuration(200).start()
    }

    /**
     * Roteamento de teclas com o menu aberto (chamado pelo hook, que consome
     * TODAS as teclas enquanto um overlay está aberto — nada chega ao jogo).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_DPAD_UP -> moveFocus(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_DPAD_DOWN -> moveFocus(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                drawerItems.firstOrNull { it.isFocused }?.performClick()
                    ?: closeQuickMenu(animated = false)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_DPAD_LEFT,
            -> closeQuickMenu(animated = true)
        }
        return true
    }

    /** Com overlays abertos, o analógico/gatilhos não vazam para o jogo. */
    fun shouldConsumeGenericMotion(): Boolean = isOverlayOpen

    private fun moveFocus(direction: Int) {
        val current = drawerItems.indexOfFirst { it.isFocused }
        val next = (current + direction).coerceIn(0, drawerItems.lastIndex)
        if (next != current) drawerItems[next].requestFocus()
    }
}

/**
 * Estilo visual dos overlays — réplica do tema do Game Launcher
 * (ui/theme/Theme.kt: Lime/BgDark/SurfaceDark etc.), em Views clássicas.
 */
internal object GexStyle {
    const val SCRIM = 0xB30F0F0F.toInt()
    const val PANEL = 0xF7141414.toInt()
    const val PANEL_HIGH = 0xFF232323.toInt()
    const val LIME = 0xFFDFFF00.toInt()
    const val ON_LIME = 0xFF171800.toInt()
    const val TEXT_PRIMARY = 0xFFEDEDED.toInt()
    const val TEXT_SECONDARY = 0xFFA8A8A8.toInt()
    const val DESTRUCTIVE = 0xFFFF5449.toInt()

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun str(ctx: Context, res: Int): String = ctx.getString(res)

    /** Escala global de animação desligada (acessibilidade) → versões estáticas. */
    fun animationsOff(ctx: Context): Boolean = try {
        val resolver = ctx.contentResolver
        android.provider.Settings.Global.getFloat(
            resolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f ||
            android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
    } catch (_: Throwable) {
        false
    }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    private fun column(ctx: Context, paddingH: Float, paddingV: Float): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, dp(ctx, 22f).toFloat())
            setPadding(dp(ctx, paddingH), dp(ctx, paddingV), dp(ctx, paddingH), dp(ctx, paddingV))
        }

    fun panelColumn(ctx: Context): LinearLayout = column(ctx, 26f, 24f)

    fun title(ctx: Context, text: String): TextView =
        text(ctx, text, TEXT_PRIMARY, 18f, bold = true).apply {
            setPadding(0, 0, 0, dp(ctx, 8f).toFloat())
        }

    fun message(ctx: Context, text: String): TextView =
        text(ctx, text, TEXT_SECONDARY, 14f, bold = false)

    fun text(ctx: Context, text: String, color: Int, sp: Float, bold: Boolean): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sp
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }

    fun spacer(ctx: Context, dpHeight: Int): View = View(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(0, dp(ctx, dpHeight.toFloat()))
    }

    /**
     * Botão/linha de opção no estilo do Launcher: pill arredondada, foco com
     * fundo amarelo-limão e texto escuro; mínimo 48 dp de altura (toque).
     */
    fun optionButton(ctx: Context, label: String, destructive: Boolean): TextView {
        val normal = if (destructive) 0x14FF5449.toInt() else PANEL_HIGH
        val focused = if (destructive) DESTRUCTIVE else LIME
        val normalText = if (destructive) DESTRUCTIVE else TEXT_PRIMARY
        val focusedText = ON_LIME
        return TextView(ctx).apply {
            text = label
            textSize = 15f
            minWidth = dp(ctx, 230f)
            minHeight = dp(ctx, 48f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 18f), dp(ctx, 12f), dp(ctx, 18f), dp(ctx, 12f))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focused ->
                val tv = v as TextView
                if (focused) {
                    tv.background = rounded(focused, dp(ctx, 14f).toFloat())
                    tv.setTextColor(focusedText)
                } else {
                    tv.background = rounded(normal, dp(ctx, 14f).toFloat())
                    tv.setTextColor(normalText)
                }
            }
            background = rounded(normal, dp(ctx, 14f).toFloat())
            setTextColor(normalText)
        }
    }

    /**
     * Teclas de gamepad num botão: A/D-pad-center/Enter acionam; B chama
     * [onBack] (fechar/cancelar). Consome o que trata — nada vaza.
     */
    fun attachButtonKeys(button: TextView, onBack: () -> Unit) {
        button.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
                return@setOnKeyListener keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                    keyCode == KeyEvent.KEYCODE_BACK
            }
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    v.performClick()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    onBack()
                    true
                }
                else -> false
            }
        }
    }
}
