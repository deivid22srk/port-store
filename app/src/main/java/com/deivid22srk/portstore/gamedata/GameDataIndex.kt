package com.deivid22srk.portstore.gamedata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * index.json        índice de todos os jogos
 * ```
 *
 * Formato v2 (atual): o repositório NÃO hospeda os pacotes — a entrada do
 * jogo traz uma URL externa (ex.: página do Buzzheavier) e o app resolve o
 * link direto no motor nativo (Rust, `buzzheavier.rs`) antes de baixar.
 *
 * Formato v1 (legado): pacote criptografado em partes `.psenc.partNN` no
 * próprio repositório ( AES-256-CTR, senha PBKDF2 ) — ainda suportado para
 * repositórios antigos.
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
    /** v2 — pacote por LINK: PÁGINA do provedor (ex.: Buzzheavier).
     *  O app extrai o link direto no motor nativo antes de baixar. */
    val url: String? = null,
    /** Provedor do link (informativo, ex.: "buzzheavier"). */
    val source: String? = null,
    /** Compressão do pacote no link (ex.: "gzip"); null = auto-detecta. */
    val compression: String? = null,
    /** v1 (legado) — pacote criptografado em partes no repositório. */
    val `package`: RepoPackageSpec? = null,
) {
    /** v1 (legado): especificação do pacote em partes; null no formato v2. */
    val pkg: RepoPackageSpec? get() = `package`

    /** v2: entrada por LINK — o download sai da URL resolvida no Rust. */
    val isLink: Boolean get() = !url.isNullOrBlank()
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
    /** v2: não foi possível extrair o link direto do provedor (Rust). */
    LINK_RESOLVE_FAILED,
    /** v2: o provedor pediu verificação Cloudflare — o app abre um WebView
     *  real (mesmo motor do Chrome) para o usuário passar e devolve
     *  cf_clearance + UA ao motor nativo (ver GameDataChallengeDialog). */
    LINK_CHALLENGE,
    CANCELED,
    IO_FAILURE,
    INSTALL_FAILED,
}

class GameDataException(val kind: GameDataErrorKind, message: String) : Exception(message)

/** Busca o índice do repositório (raw, público — sem autenticação). */
class GameDataIndexFetcher(private val http: OkHttpClient) {

    suspend fun fetch(baseUrl: String): RepoIndex = withContext(Dispatchers.IO) {
        // ⚠️ Rede SEMPRE em IO: chamadores vêm de escopos Main (ex.: diálogo de
        // ativação) — execute() na thread principal lança
        // NetworkOnMainThreadException, que aparecia como "senha incorreta".
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
            return@withContext try {
                RepoIndexJson.decodeFromString(RepoIndex.serializer(), body)
            } catch (e: Exception) {
                throw GameDataException(GameDataErrorKind.INDEX_INVALID, "índice inválido: $e")
            }
        }
    }
}
