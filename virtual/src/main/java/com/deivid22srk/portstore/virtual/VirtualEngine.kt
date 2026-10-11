package com.deivid22srk.portstore.virtual

import java.io.File

/**
 * Informações de um jogo instalado dentro do ambiente virtual do Port Store.
 *
 * [packageName] é o pacote do APK do jogo (dentro do ambiente virtual);
 * [installedAt] é o instante da instalação (epoch ms, do registro local);
 * [apkSizeBytes] é o tamanho do APK instalado no ambiente virtual;
 * [dataBytes] é o tamanho aproximado dos dados do jogo (null quando ainda
 * não calculado ou indisponível).
 */
data class VirtualAppInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val installedAt: Long,
    val apkSizeBytes: Long,
    val dataBytes: Long? = null,
)

/** Motivo de falha de uma instalação virtual — cada um vira mensagem clara na UI. */
enum class VirtualFailure {
    /** Motor não inicializado (modo foi ativado mas o app precisa reiniciar). */
    ENGINE_UNAVAILABLE,

    /** Arquivo APK não encontrado no disco. */
    FILE_MISSING,

    /** O APK é o próprio Port Store (recursão não permitida). */
    HOST_PACKAGE,

    /** APK sem arquitetura nativa compatível com o aparelho. */
    ARCH_INCOMPATIBLE,

    /** Hash SHA-256 do catálogo não confere (arquivo corrompido/trocado). */
    SHA_MISMATCH,

    /** Erro reportado pelo motor virtual. */
    ENGINE_ERROR,
}

/** Resultado de uma instalação virtual. */
sealed interface VirtualInstallStatus {
    data class Success(val packageName: String) : VirtualInstallStatus
    data class Failure(val reason: VirtualFailure, val detail: String? = null) : VirtualInstallStatus
}

/**
 * Interface interna que isola o motor de virtualização do resto do app.
 * A implementação atual usa o Bcore (NewBlackbox, Apache-2.0); trocar o
 * motor depois significa reimplementar apenas esta interface.
 */
interface VirtualEngine {
    /** Motor inicializado e pronto para uso (bootstrap já rodou). */
    val ready: Boolean

    fun install(apk: File): VirtualInstallStatus

    /** Desinstala o jogo virtual e apaga seus dados. */
    fun uninstall(packageName: String): Boolean

    /** Limpa os dados do jogo preservando a instalação (saves apagados). */
    fun clearData(packageName: String): Boolean

    /** Encerra o processo virtual do jogo (se em execução). */
    fun stop(packageName: String)

    fun isInstalled(packageName: String): Boolean

    /** Abre o jogo dentro do ambiente virtual. Retorna false em falha. */
    fun launch(packageName: String): Boolean

    /**
     * Abre o jogo RENDERIZANDO numa VirtualDisplay do hospedeiro (experiência
     * de jogo embedada: o jogo vira a “tela” do console no Game Launcher).
     * [displayId] é a display criada pelo hospedeiro e [socketName] o nome do
     * socket local onde a ponte de input vai atender. Fallback do motor:
     * retorna false e o chamador usa [launch] (tela cheia).
     */
    fun launchEmbedded(packageName: String, displayId: Int, socketName: String): Boolean = false

    fun listApps(): List<VirtualAppInfo>

    fun packageInfo(packageName: String): VirtualAppInfo?
}
