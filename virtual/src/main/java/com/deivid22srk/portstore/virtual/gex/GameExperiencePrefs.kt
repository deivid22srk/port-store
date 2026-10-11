package com.deivid22srk.portstore.virtual.gex

import android.content.Context

/**
 * Espelho em SharedPreferences das preferências da experiência de jogo.
 *
 * O processo do motor (:pN) NÃO inicializa o AppGraph (Room/DataStore ficam
 * no processo principal — ver PortStoreApp.isEngineProcess), e DataStore não
 * é seguro para leitura multiprocesso. O mesmo padrão do VirtualBootstrap
 * (flag "virtual_boot"): o processo principal escreve aqui quando o usuário
 * muda a preferência; o processo do motor só lê.
 */
object GameExperiencePrefs {

    private const val PREFS = "game_experience"
    private const val KEY_QUICK_MENU_GESTURE = "quick_menu_gesture"
    private const val KEY_LAUNCH_FEEDBACK = "launch_feedback"

    /**
     * Gesto de duas deslizadas (esquerda -> direita) que abre o menu rápido
     * dentro do jogo. Ligado por padrão.
     */
    fun isQuickMenuGestureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUICK_MENU_GESTURE, true)

    fun setQuickMenuGestureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_MENU_GESTURE, enabled).apply()
    }

    /** Som curto + vibração leve ao abrir um jogo pelo Launcher. Desligado por padrão. */
    fun isLaunchFeedbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAUNCH_FEEDBACK, false)

    fun setLaunchFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAUNCH_FEEDBACK, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
