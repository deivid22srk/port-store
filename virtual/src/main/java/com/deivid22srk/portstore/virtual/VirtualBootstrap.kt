package com.deivid22srk.portstore.virtual

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Bootstrap preguiçoso do motor virtual.
 *
 * O Bcore precisa inicializar no [Application.attachBaseContext]; para não
 * custar memória/tempo a quem não usa o modo, a inicialização só acontece se
 * a flag rápida ("virtual_boot") estiver ativa. A flag é espelho em
 * SharedPreferences da preferência DataStore, porque DataStore não tem
 * leitura síncrona segura nesse ponto do ciclo de vida.
 */
object VirtualBootstrap {

    /** Espelho síncrono da preferência "Instalação Virtual". */
    fun isBootFlagEnabled(context: Context): Boolean =
        context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Escrito quando o usuário liga/desliga o modo nas configurações. */
    fun setBootFlag(context: Context, enabled: Boolean) {
        context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @Volatile
    var bootstrapped: Boolean = false
        private set

    /**
     * Chamar obrigatoriamente do [Application.attachBaseContext]. Nunca
     * propaga exceção: se o motor falhar, o app segue funcionando sem o
     * modo virtual (a UI informa que o motor está indisponível).
     */
    fun attachBaseContext(application: Application) {
        if (bootstrapped) return
        bootstrapped = runCatching {
            if (!isBootFlagEnabled(application)) return@runCatching false
            val core = BlackBoxCore.get()
            core.closeCodeInit()
            core.doAttachBaseContext(
                application,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = application.packageName

                    // Recursos de sistema sensíveis permanecem desligados:
                    // nada de esconder root, rede VPN, anti-FlagSecure ou
                    // daemon persistente — o objetivo é só rodar os jogos.
                    override fun isHideRoot(): Boolean = false

                    override fun isEnableDaemonService(): Boolean = false

                    override fun isUseVpnNetwork(): Boolean = false

                    override fun isDisableFlagSecure(): Boolean = false

                    override fun requestInstallPackage(file: java.io.File?, userId: Int): Boolean =
                        false
                },
            )
            true
        }.onFailure { it.printStackTrace() }.getOrDefault(false)
    }

    /** Chamar do [Application.onCreate] após [attachBaseContext]. */
    fun onCreate(application: Application) {
        if (!bootstrapped) return
        runCatching { BlackBoxCore.get().doCreate() }
            .onFailure { it.printStackTrace() }
    }

    private const val BOOT_PREFS = "virtual_boot"
    private const val KEY_ENABLED = "engine_enabled"
}
