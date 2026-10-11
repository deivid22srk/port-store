package com.deivid22srk.portstore.virtual.gex

import android.content.Context
import top.niunaijun.blackbox.app.GameExperienceBridge
import top.niunaijun.blackbox.utils.Slog

/**
 * Instala o hook da experiência de jogo no processo corrente.
 *
 * Chamado apenas nos processos de jogo (:p0..:p49) a partir do
 * PortStoreApp.onCreate — é ali que as activities dos jogos vivem e onde o
 * dispatcher do Window.Callback (Bcore) procura o hook. Idempotente.
 */
object GameOverlayInstaller {

    private const val TAG = "GexHook"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            GameExperienceBridge.hook = GameOverlayHook(context.applicationContext)
            installed = true
            Slog.i(TAG, "hook instalado no processo ${context.applicationInfo?.processName ?: "?"}")
        }
    }
}
