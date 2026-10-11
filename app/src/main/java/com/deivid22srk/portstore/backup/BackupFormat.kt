package com.deivid22srk.portstore.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Formato v2 do pacote de dados de um jogo (Modo Desenvolvedor / Loja):
 *
 * **UM ÚNICO ARQUIVO** — escolhido via SAF (Criar documento):
 *
 * ```
 * <onde o usuário escolher>/<prefixo>_portstore.tar.zst
 * ```
 *
 * Estrutura interna (streaming puro, nunca materializada em disco):
 *
 * ```
 * arquivo = [frame zstd por entrada TAR, frames concatenados]
 * entrada 0: manifest.json        (primeira entrada — inspecionável barato)
 * entrada 1..N: dados do jogo     (install/, saves/, settings/, launcher/)
 * ```
 *
 * - Um FRAME Zstandard por entrada TAR: fronteira de retomada + nível de
 *   compressão por arquivo (conteúdo já comprimido usa nível mínimo);
 * - Cada frame carrega checksum XXH64 — validado automaticamente pelo
 *   ZstdInputStream na leitura (integridade sem hash externo);
 * - Frames concatenados: o leitor decodifica em sequência (comportamento
 *   validado por probe do zstd-jni 1.5.7-3);
 * - Retomada da exportação: trunca o arquivo no offset da última entrada
 *   commitada ([JobState.committedBytes]) e continua com novos frames;
 * - Retomada da importação: reabre o stream e pula entradas já extraídas
 *   por índice ([JobState.nextFileIndex]) — decodificar é barato, extrair não.
 */
const val BACKUP_FORMAT_VERSION = 2
const val BACKUP_COMPRESSION = "zstd"
const val MANIFEST_ENTRY_NAME = "manifest.json"

/** Extensão do arquivo único do pacote. */
const val PACKAGE_EXTENSION = ".tar.zst"

/** Níveis de compressão oferecidos na UI (Zstandard). */
const val LEVEL_FAST = 3
const val LEVEL_BALANCED = 6
const val LEVEL_MAX = 19

/** Conteúdo já comprimido (mp4/zip/obb...) é "quase armazenado": nível mínimo útil. */
const val LEVEL_STORED = 1

/** Buffer fixo de cópia (RAM constante mesmo com jogos de 20 GB+). */
const val COPY_BUFFER_BYTES = 256 * 1024

/** Categorias de conteúdo do pacote (checkboxes do diálogo de exportação). */
enum class BackupCategory(val id: String) {
    /** APK base + OBB + dados do ambiente virtual do jogo (arquivos grandes). */
    INSTALL("install"),

    /** Saves: bancos de dados do jogo (progresso). */
    SAVES("saves"),

    /** Configurações do jogo: shared_prefs (controles, gráficos etc.). */
    SETTINGS("settings"),

    /** Metadados no launcher: vínculo gameId <-> pacote virtual. */
    LAUNCHER("launcher"),
    ;

    companion object {
        fun fromId(id: String): BackupCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Manifest do pacote — embutido como PRIMEIRA entrada do TAR (o pacote
 * continua sendo um único arquivo). Campos do formato público (Loja);
 * leitores devem ignorar campos desconhecidos.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val gameId: String,
    val gameName: String,
    val packageName: String,
    val appVersion: String,
    val createdAt: String,
    val compression: String = BACKUP_COMPRESSION,
    val compressionLevel: Int = LEVEL_BALANCED,
    val totalSizeExtracted: Long = 0L,
    val fileCount: Int = 0,
    val includes: List<String> = emptyList(),
) {
    fun includesCategory(category: BackupCategory): Boolean = includes.contains(category.id)

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun decode(text: String): BackupManifest =
            JSON.decodeFromString(BackupManifest.serializer(), text)

        fun encode(manifest: BackupManifest): String =
            JSON.encodeToString(BackupManifest.serializer(), manifest)
    }
}

/** Prefixo do pacote (nome do arquivo), derivado do gameId. */
fun slugFor(gameId: String): String {
    val slug = gameId.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(40)
    return slug.ifEmpty { "game" }
}

/** Nome sugerido do arquivo único do pacote. Ex.: gta_iv_portstore.tar.zst */
fun packageFileName(prefix: String): String = "${prefix}_portstore$PACKAGE_EXTENSION"

/**
 * Conteúdo cujo formato já é comprimido: compressão forte é desperdício de
 * CPU sem ganho de tamanho. Nesses casos usamos o nível mínimo ("quase
 * armazenado") para economizar tempo.
 */
private val PRECOMPRESSED_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "avi", "mov", "wmv", "webm", "flv",
    "mp3", "m4a", "aac", "ogg", "oga", "opus", "wma", "flac",
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic",
    "zip", "apk", "obb", "jar", "7z", "rar", "gz", "xz", "bz2", "zst", "lz4",
    "pdf", "mpq", "vpp", "p3d", "rpf", "arc", "bnk", "ipf",
)

fun isPreCompressedName(tarPath: String): Boolean {
    val name = tarPath.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in PRECOMPRESSED_EXTENSIONS
}

/** Nível de compressão adequado para a entrada dada. */
fun levelFor(tarPath: String, userLevel: Int): Int =
    if (isPreCompressedName(tarPath)) LEVEL_STORED else userLevel
