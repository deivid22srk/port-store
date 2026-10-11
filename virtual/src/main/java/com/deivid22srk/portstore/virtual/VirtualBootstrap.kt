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

    @Volatile
    private var created: Boolean = false

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
        if (!bootstrapped || created) return
        created = runCatching { BlackBoxCore.get().doCreate() }
            .onFailure { it.printStackTrace() }
            .getOrDefault(false)
    }

    /**
     * Inicializa o motor sob demanda, DEPOIS do app já estar de pé.
     *
     * Cobre o caso do usuário ligar o modo nesta sessão: a flag de boot só é
     * lida no [attachBaseContext] do processo, então sem isto o motor ficaria
     * indisponível até a próxima reinicialização. Roda a mesma sequência do
     * boot (attach + create) — o Bcore aceita Context qualquer; se algo falhar,
     * a exceção é engolida e o chamador informa que é preciso reiniciar.
     */
    fun ensureStarted(application: Application): Boolean {
        if (bootstrapped && created) return true
        return synchronized(this) {
            if (bootstrapped && created) {
                true
            } else {
                runCatching {
                    attachBaseContext(application)
                    if (bootstrapped) onCreate(application)
                    bootstrapped && created
                }.getOrDefault(false)
            }
        }
    }

    private const val BOOT_PREFS = "virtual_boot"
    private const val KEY_ENABLED = "engine_enabled"
}
