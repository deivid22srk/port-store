package com.deivid22srk.portstore.setup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.annotation.StringRes
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.db.AppDatabase
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.db.RepoCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Formato do arquivo gerado pela exportação do REPOSITÓRIO DE DADOS
 * COMPLEMENTARES (etapa 4 do setup): as fontes do catálogo e o cache local
 * dos seus `games.json` — que carregam os dados auxiliares dos jogos
 * (títulos, capas, descrições, categorias, links e metadados).
 *
 * Formato JSON com nome de schema e versão explícitos, para permitir
 * validação e evolução futura do formato.
 */
object ComplementaryDataFormat {
    const val SCHEMA_NAME = "portstore-complementary-data"
    const val CURRENT_VERSION = 1
    const val FILE_EXTENSION = "json"
    const val MIME_TYPE = "application/json"

    /** Nome sugerido no diálogo de exportação (SAF). */
    fun suggestedFileName(): String = "portstore-dados-complementares.$FILE_EXTENSION"
}

/** Modo de aplicação do arquivo importado. */
enum class ComplementaryImportMode { MERGE, REPLACE }

/** Erros tratados da importação/exportação (mensagens em strings.xml). */
enum class ComplementaryDataError(@StringRes val messageRes: Int) {
    INVALID_FILE(R.string.setup_data_error_invalid),
    INCOMPATIBLE_VERSION(R.string.setup_data_error_version),
    CORRUPTED_FILE(R.string.setup_data_error_corrupted),
    INSUFFICIENT_SPACE(R.string.setup_data_error_space),
    IO_ERROR(R.string.setup_data_error_io),
}

/** Falha tipada da validação (carregada pelo codec e pelo manager). */
class ComplementaryDataException(val error: ComplementaryDataError) :
    Exception(error.name)

@Serializable
data class ComplementaryRepoPayload(
    val id: String,
    val name: String,
    val url: String,
    val baseUrl: String = "",
    val siteBase: String = "",
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
    val gameCount: Int = 0,
    /** Corpo bruto do games.json em cache (dados complementares do repo). */
    val cachedBody: String? = null,
    val cachedEtag: String? = null,
    val cachedAt: Long = 0L,
)

@Serializable
data class ComplementaryDataFile(
    val schema: String,
    val version: Int,
    val exportedAt: Long,
    val itemCount: Int,
    val items: List<ComplementaryRepoPayload>,
)

/** Resumo validado mostrado ao usuário antes de confirmar a importação. */
data class ComplementarySummary(
    val version: Int,
    val itemCount: Int,
    val exportedAt: Long,
    val repoNames: List<String>,
)

/**
 * Codec puro (JVM-testável) do formato de exportação: valida schema, versão,
 * contagem declarada e integridade básica dos itens.
 */
object ComplementaryDataCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(exportedAt: Long, items: List<ComplementaryRepoPayload>): String {
        val file = ComplementaryDataFile(
            schema = ComplementaryDataFormat.SCHEMA_NAME,
            version = ComplementaryDataFormat.CURRENT_VERSION,
            exportedAt = exportedAt,
            itemCount = items.size,
            items = items,
        )
        return json.encodeToString(ComplementaryDataFile.serializer(), file)
    }

    /**
     * Decodifica e valida. Levanta [ComplementaryDataException] com o erro
     * correspondente: arquivo inválido, versão incompatível ou corrompido.
     */
    fun decode(text: String): ComplementaryDataFile {
        val file = try {
            json.decodeFromString(ComplementaryDataFile.serializer(), text)
        } catch (e: ComplementaryDataException) {
            throw e
        } catch (e: Exception) {
            throw ComplementaryDataException(ComplementaryDataError.INVALID_FILE)
        }
        when {
            file.schema != ComplementaryDataFormat.SCHEMA_NAME ->
                throw ComplementaryDataException(ComplementaryDataError.INVALID_FILE)
            file.version > ComplementaryDataFormat.CURRENT_VERSION ->
                throw ComplementaryDataException(ComplementaryDataError.INCOMPATIBLE_VERSION)
            file.version < 1 ->
                throw ComplementaryDataException(ComplementaryDataError.INVALID_FILE)
            file.itemCount != file.items.size ->
                throw ComplementaryDataException(ComplementaryDataError.CORRUPTED_FILE)
            file.items.any { it.id.isBlank() || it.name.isBlank() || it.url.isBlank() } ->
                throw ComplementaryDataException(ComplementaryDataError.CORRUPTED_FILE)
        }
        return file
    }
}

/**
 * Operações locais (SAF + Room) do repositório de dados complementares.
 * Interface para permitir testes de JVM do SetupViewModel com fakes.
 */
interface ComplementaryDataBackupOps {
    suspend fun exportTo(uri: Uri): Result<Int>
    suspend fun readSummary(uri: Uri): Result<ComplementarySummary>
    suspend fun importFrom(uri: Uri, mode: ComplementaryImportMode): Result<Int>
}

/**
 * Operações locais (SAF + Room) do repositório de dados complementares:
 * exportar para um arquivo escolhido pelo usuário (ACTION_CREATE_DOCUMENT é
 * acionado pela UI) e importar com validação + resumo + mesclar/substituir.
 *
 * Usa SAF, então NÃO exige permissão ampla de armazenamento.
 */
class ComplementaryDataBackupManager(
    private val context: Context,
    private val db: AppDatabase,
) : ComplementaryDataBackupOps {

    // ------------------------------------------------------------------
    // Exportação
    // ------------------------------------------------------------------

    /** Serializa o estado atual (repos + cache) e grava no [uri] escolhido. */
    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val repoDao = db.dataRepoDao()
            val cacheDao = db.repoCacheDao()
            val repos = repoDao.all()
            val payloads = repos.map { repo ->
                val cached = cacheDao.get(repo.id)
                ComplementaryRepoPayload(
                    id = repo.id,
                    name = repo.name,
                    url = repo.url,
                    baseUrl = repo.baseUrl,
                    siteBase = repo.siteBase,
                    enabled = repo.enabled,
                    sortIndex = repo.sortIndex,
                    gameCount = repo.gameCount,
                    cachedBody = cached?.body,
                    cachedEtag = cached?.etag,
                    cachedAt = cached?.fetchedAt ?: 0L,
                )
            }
            val text = ComplementaryDataCodec.encode(System.currentTimeMillis(), payloads)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw ComplementaryDataException(ComplementaryDataError.IO_ERROR)
            payloads.size
        }.recoverCatching { e ->
            throw if (e is ComplementaryDataException) e else ComplementaryDataException(ComplementaryDataError.IO_ERROR)
        }
    }

    // ------------------------------------------------------------------
    // Importação
    // ------------------------------------------------------------------

    /** Lê e valida o arquivo, retornando o resumo para confirmação do usuário. */
    suspend fun readSummary(uri: Uri): Result<ComplementarySummary> = withContext(Dispatchers.IO) {
        decodeFrom(uri).mapCatching { file ->
            ComplementarySummary(
                version = file.version,
                itemCount = file.itemCount,
                exportedAt = file.exportedAt,
                repoNames = file.items.map { it.name },
            )
        }
    }

    /**
     * Aplica o arquivo importado. Em [ComplementaryImportMode.REPLACE] a base
     * local (repos + cache) é apagada antes; em MERGE os itens são mesclados
     * por id (upsert). Antes de gravar verifica espaço livre aproximado.
     */
    suspend fun importFrom(uri: Uri, mode: ComplementaryImportMode): Result<Int> =
        withContext(Dispatchers.IO) {
            decodeFrom(uri).mapCatching { file ->
                checkFreeSpace(file)
                val repoDao = db.dataRepoDao()
                val cacheDao = db.repoCacheDao()
                if (mode == ComplementaryImportMode.REPLACE) {
                    repoDao.all().forEach { repoDao.delete(it.id) }
                    cacheDao.all().forEach { cacheDao.delete(it.repoId) }
                }
                file.items.forEach { item ->
                    repoDao.upsert(
                        DataRepoEntity(
                            id = item.id,
                            name = item.name,
                            url = item.url,
                            baseUrl = item.baseUrl,
                            siteBase = item.siteBase,
                            enabled = item.enabled,
                            sortIndex = item.sortIndex,
                            lastUpdated = if (item.cachedAt > 0) item.cachedAt else 0L,
                            etag = null, // ETag antiga não vale para outro dispositivo
                            gameCount = item.gameCount,
                            status = if (item.cachedBody != null) "ok" else "pendente",
                            lastError = null,
                        ),
                    )
                    if (item.cachedBody != null) {
                        cacheDao.upsert(
                            RepoCacheEntity(
                                repoId = item.id,
                                body = item.cachedBody,
                                etag = null,
                                fetchedAt = item.cachedAt,
                            ),
                        )
                    }
                }
                file.itemCount
            }
        }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private fun decodeFrom(uri: Uri): Result<ComplementaryDataFile> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw ComplementaryDataException(ComplementaryDataError.IO_ERROR)
        if (text.isBlank()) throw ComplementaryDataException(ComplementaryDataError.CORRUPTED_FILE)
        ComplementaryDataCodec.decode(text)
    }.recoverCatching { e ->
        throw when (e) {
            is ComplementaryDataException -> e
            is IOException -> ComplementaryDataException(ComplementaryDataError.IO_ERROR)
            else -> ComplementaryDataException(ComplementaryDataError.CORRUPTED_FILE)
        }
    }

    /**
     * Espaço insuficiente: a importação grava no Room (armazenamento interno).
     * Exige que exista ao menos 2x o tamanho do arquivo em espaço livre.
     */
    private fun checkFreeSpace(file: ComplementaryDataFile) {
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            val available = stat.availableBytes
            val needed = file.items.sumOf { (it.cachedBody?.length ?: 0) * 2L } + 1L
            if (available < needed) {
                throw ComplementaryDataException(ComplementaryDataError.INSUFFICIENT_SPACE)
            }
        }.onFailure { e ->
            if (e is ComplementaryDataException) throw e
            // StatFs indisponível: segue o fluxo (a gravação em si pode falhar).
        }
    }
}
