package com.deivid22srk.portstore.virtual

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Fachada do modo Instalação Virtual usada pelo Port Store (via AppGraph).
 *
 * Responsabilidades:
 * - expor o estado (jogos virtuais instalados) como [StateFlow];
 * - rotear instalação/execução/desinstalação para a [VirtualEngine];
 * - manter o vínculo gameId do catálogo <-> pacote virtual;
 * - nunca derrubar o app hospedeiro se o motor falhar.
 *
 * O motor só é inicializado se o modo estiver ativado (ver [VirtualBootstrap]).
 * Com o modo desligado, todas as operações aqui retornam vazio/false barato.
 */
class VirtualManager(context: Context) {

    private val appContext = context.applicationContext
    private val registry = VirtualRegistry(appContext)
    private val engine = BlackboxVirtualEngine(appContext, registry)

    /** Motor pronto para uso (bootstrap rodou com o modo ativado). */
    val engineReady: Boolean
        get() = engine.ready

    /** Prontidão observável (a UI reage ao motor ficar pronto sem reinício). */
    private val _engineReadyFlow = MutableStateFlow(false)
    val engineReadyFlow: StateFlow<Boolean> = _engineReadyFlow.asStateFlow()

    private val _apps = MutableStateFlow<List<VirtualAppInfo>>(emptyList())

    /** Jogos virtuais instalados (fonte de verdade para toda a UI). */
    val apps: StateFlow<List<VirtualAppInfo>> = _apps.asStateFlow()

    /** Flag rápida (SharedPreferences) consultada no boot do processo. */
    val bootFlagEnabled: Boolean
        get() = VirtualBootstrap.isBootFlagEnabled(appContext)

    init {
        _engineReadyFlow.value = engine.ready
        refresh()
    }

    /** Recarrega a lista do motor (chamar ao abrir telas e em ON_RESUME). */
    fun refresh() {
        _engineReadyFlow.value = engine.ready
        _apps.value = if (engine.ready) {
            runCatching { engine.listApps() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    /**
     * Garante o motor pronto, inicializando-o sob demanda quando o modo foi
     * ligado nesta sessão (sem reinício do processo). Retorna [engineReady]
     * após a tentativa — falso se o motor mesmo assim não subiu.
     *
     * Bloqueante na primeira chamada (bootstrap do Bcore) — chamar fora da
     * thread principal.
     */
    fun ensureEngine(): Boolean {
        if (engine.ready) {
            _engineReadyFlow.value = true
            return true
        }
        VirtualBootstrap.ensureStarted(appContext)
        refresh()
        return engine.ready
    }

    fun isInstalled(packageName: String): Boolean =
        engine.ready && runCatching { engine.isInstalled(packageName) }.getOrDefault(false)

    fun infoFor(packageName: String): VirtualAppInfo? =
        _apps.value.firstOrNull { it.packageName == packageName }

    /** gameId do catálogo dono deste pacote virtual (se registrado). */
    fun gameIdFor(packageName: String): String? = registry.gameIdFor(packageName)

    /** Pacote virtual instalado para este jogo do catálogo (se houver). */
    fun packageNameFor(gameId: String): String? = registry.packageNameFor(gameId)

    /**
     * Instala um APK baixado no ambiente virtual, com verificação de
     * integridade (SHA-256 do catálogo quando disponível), compatibilidade de
     * arquitetura e proteção contra instalação recursiva do host.
     *
     * Bloqueante — chamar de Dispatchers.IO.
     */
    fun installFromApk(
        gameId: String,
        apk: File,
        expectedSha256: String? = null,
    ): VirtualInstallStatus {
        if (!engine.ready) return VirtualInstallStatus.Failure(VirtualFailure.ENGINE_UNAVAILABLE)

        // Integridade: o download em si já valida o hash no motor nativo;
        // aqui é a segunda camada (defesa em profundidade) para o arquivo
        // que chega ao ambiente virtual.
        if (expectedSha256?.isNotBlank() == true) {
            val actual = VirtualVerification.sha256(apk)
            if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
                return VirtualInstallStatus.Failure(VirtualFailure.SHA_MISMATCH)
            }
        }

        return when (val status = engine.install(apk)) {
            is VirtualInstallStatus.Success -> {
                registry.register(gameId, status.packageName, apk.length())
                refresh()
                status
            }
            is VirtualInstallStatus.Failure -> status
        }
    }

    /** Abre o jogo virtual. Retorna false se o pacote não existir ou falhar. */
    fun launch(packageName: String): Boolean {
        val ok = engine.ready && engine.launch(packageName)
        if (ok) refresh()
        return ok
    }

    /** Desinstala o jogo virtual (apaga dados). Retorna true se removeu. */
    fun uninstall(packageName: String): Boolean {
        val ok = engine.ready && engine.uninstall(packageName)
        refresh()
        return ok
    }

    /** Limpa os dados do jogo (saves são perdidos — UI pede confirmação). */
    fun clearData(packageName: String): Boolean {
        val ok = engine.ready && engine.clearData(packageName)
        refresh()
        return ok
    }

    /** Encerra o processo do jogo virtual (usado antes de desinstalar/atualizar). */
    fun stop(packageName: String) {
        if (engine.ready) runCatching { engine.stop(packageName) }
    }

    /** Espaço total ocupado pelo ambiente virtual (bytes). */
    fun storageUsageBytes(): Long =
        if (engine.ready) runCatching { engine.storageUsageBytes() }.getOrDefault(0L) else 0L

    /** Espaço ocupado por um jogo virtual específico (bytes). */
    fun appUsageBytes(packageName: String): Long =
        if (engine.ready) runCatching { engine.appUsageBytes(packageName) }.getOrDefault(0L) else 0L
}
