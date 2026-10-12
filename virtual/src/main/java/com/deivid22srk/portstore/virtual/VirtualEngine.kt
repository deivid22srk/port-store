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

    /** Falha ao baixar os dados complementares do repositório de dados. */
    DATA_DOWNLOAD_FAILED,

    /** Pacote de dados corrompido (senha trocada no repositório, partes inválidas). */
    DATA_CORRUPT,

    /** Falha ao extrair/instalar os dados complementares no ambiente virtual. */
    DATA_INSTALL_FAILED,
}

/** Resultado de uma instalação virtual. */
sealed interface VirtualInstallStatus {
    data class Success(val packageName: String) : VirtualInstallStatus
    data class Failure(val reason: VirtualFailure, val detail: String? = null) : VirtualInstallStatus
}

/**
 * Fases observáveis da instalação de um APK no ambiente virtual. A UI do
 * Game Launcher mostra o progresso ao vivo na tela de detalhes enquanto a
 * instalação (automática pós-download ou manual) está em andamento.
 */
enum class VirtualInstallPhase {
    /** Inicializando o motor sob demanda (bootstrap tardio do Bcore). */
    PREPARING,

    /** Conferindo o SHA-256 do APK antes de instalar ([verifiedBytes]/[totalBytes]). */
    VERIFYING,

    /** Motor instalando o pacote (sem percentual — IPC opaco). */
    INSTALLING,

    /**
     * Baixando os dados complementares do jogo do repositório de dados
     * ([verifiedBytes]/[totalBytes] = bytes do pacote em partes).
     */
    DOWNLOADING_DATA,

    /**
     * Extraindo os dados complementares no ambiente virtual (decriptação
     * + TAR em streaming; [verifiedBytes]/[totalBytes] = bytes extraídos).
     */
    INSTALLING_DATA,

    /** Concluída — [status] traz Success/Failure. */
    DONE,
}

/**
 * Instantâneo do progresso de uma instalação virtual, indexado por gameId do
 * catálogo. Emitido por [VirtualManager.installFromApk] e pelo instalador
 * automático; a UI observa e renderiza (barra + fase).
 */
data class VirtualInstallProgress(
    val gameId: String,
    val phase: VirtualInstallPhase,
    val verifiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: VirtualInstallStatus? = null,
)

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

    fun listApps(): List<VirtualAppInfo>

    fun packageInfo(packageName: String): VirtualAppInfo?
}
