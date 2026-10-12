package com.deivid22srk.portstore.gamedata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import com.deivid22srk.portstore.backup.BackupManifest

/**
 * Repositório de DADOS dos jogos (segundo tipo de repositório do Port
 * Store — o primeiro é o repositório de APPS da Loja).
 *
 * URL padrão embutida no app; o repositório vem DESATIVADO e é ativado com
 * a senha do repositório (verificada localmente contra [RepoPasswordCheck]).
 *
 * Layout do repositório (branch principal):
 *
 * ```
 * index.json                             índice de todos os jogos
 * games/<gameId>/manifest.json           manifest v3 legível (sem senha)
 * games/<gameId>/<pkg>.tar.gz.psenc.partNN  partes criptografadas (<= 95 MiB)
 * ```
 */

/** URL padrão do repositório de dados (raw do branch principal). */
const val DEFAULT_DATA_REPO_URL: String =
    "https://raw.githubusercontent.com/deivid22srk/port-store-data/main"

/** Identificador do formato do índice. */
const val REPO_FORMAT: String = "port-store-data"

@Serializable
data class RepoPasswordCheck(
    val kdf: String = "PBKDF2WithHmacSHA256",
    val salt: String,
    val iterations: Int = 180_000,
    val verifier: String,
)

@Serializable
data class RepoPart(
    /** Caminho no repositório (relativo à raiz), ex.: games/pt-android/x.part00. */
    val name: String,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
)

@Serializable
data class RepoPackageSpec(
    val file: String,
    val sizeBytes: Long = 0L,
    val cipher: String = "AES/CTR/NoPadding",
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = 180_000,
    val salt: String,
    val iv: String,
    val headerBytes: Int = GameDataCrypto.HEADER_BYTES,
    val plainSizeBytes: Long = 0L,
    val plainSha256: String = "",
    val parts: List<RepoPart> = emptyList(),
)

@Serializable
data class RepoGame(
    val gameId: String,
    val packageName: String,
    val gameName: String = "",
    val appVersion: String? = null,
    /** Manifest v3 embutido (mesmo formato do exportar/importar). */
    val manifest: BackupManifest? = null,
    val `package`: RepoPackageSpec,
) {
    val pkg: RepoPackageSpec get() = `package`
}

@Serializable
data class RepoIndex(
    val format: String = REPO_FORMAT,
    val version: Int = 1,
    val passwordCheck: RepoPasswordCheck? = null,
    val games: List<RepoGame> = emptyList(),
) {
    /** Entrada de dados para um jogo, por gameId OU packageName. */
    fun entryFor(gameId: String?, packageNames: List<String>): RepoGame? =
        games.firstOrNull { it.gameId == gameId }
            ?: games.firstOrNull { repo ->
                packageNames.any { it.isNotBlank() && it.equals(repo.packageName, ignoreCase = true) }
            }
}

/** JSON do índice — campos desconhecidos ignorados (formato evoluível). */
val RepoIndexJson: Json = Json { ignoreUnknownKeys = true }

/** Exceções do repositório de dados — cada uma vira mensagem clara na UI. */
enum class GameDataErrorKind {
    REPO_UNREACHABLE,
    INDEX_INVALID,
    PASSWORD_WRONG,
    ENTRY_MISSING,
    INSUFFICIENT_SPACE,
    PART_CORRUPT,
    PACKAGE_CORRUPT,
    CANCELED,
    IO_FAILURE,
    INSTALL_FAILED,
}

class GameDataException(val kind: GameDataErrorKind, message: String) : Exception(message)

/** Busca o índice do repositório (raw, público — sem autenticação). */
class GameDataIndexFetcher(private val http: OkHttpClient) {

    suspend fun fetch(baseUrl: String): RepoIndex {
        val url = baseUrl.trimEnd('/') + "/index.json"
        val request = Request.Builder().url(url).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw GameDataException(GameDataErrorKind.REPO_UNREACHABLE, "repositório indisponível: $e")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw GameDataException(
                    GameDataErrorKind.REPO_UNREACHABLE,
                    "repositório respondeu HTTP ${resp.code}",
                )
            }
            val body = resp.body?.string()
                ?: throw GameDataException(GameDataErrorKind.INDEX_INVALID, "índice vazio")
            return try {
                RepoIndexJson.decodeFromString(RepoIndex.serializer(), body)
            } catch (e: Exception) {
                throw GameDataException(GameDataErrorKind.INDEX_INVALID, "índice inválido: $e")
            }
        }
    }
}
