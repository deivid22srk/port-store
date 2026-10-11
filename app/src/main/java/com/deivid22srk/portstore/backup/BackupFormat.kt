package com.deivid22srk.portstore.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Formato do pacote de dados de um jogo (Modo Desenvolvedor / Loja):
 *
 * ```
 * <pasta escolhida>/<prefixo>_portstore/
 *   manifest.json
 *   <prefixo>.part001.tar.zst
 *   <prefixo>.part002.tar.zst
 *   ...
 * ```
 *
 * Cada parte contém um TAR COMPLETO (o corte entre partes acontece sempre
 * na fronteira de uma entrada TAR — um arquivo nunca é dividido). Assim,
 * cada parte pode ser extraída sozinha (retomada) e o conjunto, lido em
 * ordem, reproduz todos os arquivos. Compressão Zstandard (frame por parte,
 * nível escolhido por arquivo: conteúdo já comprimido usa nível mínimo).
 */
const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_COMPRESSION = "zstd"
const val MANIFEST_FILE_NAME = "manifest.json"

/** Extensão das partes. */
const val PART_EXTENSION = ".tar.zst"

/** Níveis de compressão oferecidos na UI (Zstandard). */
const val LEVEL_FAST = 3
const val LEVEL_BALANCED = 6
const val LEVEL_MAX = 19

/** Conteúdo já comprimido (mp4/zip/obb...) é "quase armazenado": nível mínimo útil. */
const val LEVEL_STORED = 1

/** Tamanhos de parte oferecidos na UI (bytes). */
const val PART_SIZE_SMALL: Long = 500L * 1024 * 1024
const val PART_SIZE_DEFAULT: Long = 1024L * 1024 * 1024
const val PART_SIZE_LARGE: Long = 2L * 1024 * 1024 * 1024

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

/** Uma parte do pacote, conforme registrada no manifest. */
@Serializable
data class BackupPart(
    val name: String,
    val sizeCompressed: Long,
    val sizeExtracted: Long,
    val sha256: String,
)

/**
 * Manifest do pacote. Campos do formato público (Loja) + campos extras
 * do próprio launcher (packageName/compressionLevel/partSizeBytes) —
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
    val partSizeBytes: Long = PART_SIZE_DEFAULT,
    val totalSizeCompressed: Long = 0L,
    val totalSizeExtracted: Long = 0L,
    val parts: List<BackupPart> = emptyList(),
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
    }
}

/** Prefixo do pacote (nome dos arquivos de parte), derivado do gameId. */
fun slugFor(gameId: String): String {
    val slug = gameId.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(40)
    return slug.ifEmpty { "game" }
}

/** Nome do arquivo da parte N (1-based). Ex.: gta_iv.part001.tar.zst */
fun partFileName(prefix: String, index: Int): String =
    String.format(Locale.ROOT, "%s.part%03d%s", prefix, index, PART_EXTENSION)

/** Subpasta do pacote dentro da pasta escolhida no SAF. */
fun packageFolderName(prefix: String): String = "${prefix}_portstore"

/** Extrai o índice (1-based) de um nome de parte deste prefixo; null se não for parte. */
fun partIndex(name: String, prefix: String): Int? {
    if (!name.startsWith("$prefix.part") || !name.endsWith(PART_EXTENSION)) return null
    val number = name.removePrefix("$prefix.part").removeSuffix(PART_EXTENSION)
    return number.toIntOrNull()
}

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
