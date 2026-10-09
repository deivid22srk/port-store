package com.deivid22srk.portstore.catalog

import android.content.Context
import com.deivid22srk.portstore.db.AppDatabase
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.db.RepoCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Fases da tela de carregamento pós-setup. */
enum class LoadPhase { BUSCANDO, BAIXANDO, PROCESSANDO, IMAGENS, PRONTO }

/** Estado de um repositório na tela de carregamento. */
data class RepoLoadStatus(
    val repoId: String,
    val name: String,
    val state: String, // aguardando | baixando | lendo | ok | falhou
    val error: String? = null,
)

/** Progresso geral da carga do catálogo (tela de carregamento). */
data class LoadProgress(
    val phase: LoadPhase = LoadPhase.BUSCANDO,
    val overall: Float = 0f,
    val currentRepo: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val repos: List<RepoLoadStatus> = emptyList(),
    val anyFailed: Boolean = false,
    val allFailed: Boolean = false,
    val hasCache: Boolean = false,
    val totalGames: Int = 0,
)

data class RefreshResult(
    val okRepos: Int,
    val failedRepos: Int,
    val totalGames: Int,
    val hasCache: Boolean,
) {
    val allFailed: Boolean get() = okRepos == 0 && failedRepos > 0
    val anyFailed: Boolean get() = failedRepos > 0
}

data class RepoVerification(
    val gameCount: Int,
    val suggestedName: String,
    val catalogUrl: String,
    val baseUrl: String,
)

/**
 * Fonte de dados do catálogo: um ou mais repositórios (o padrão é o port-db).
 * Baixa `games.json` de cada repositório ativo (com ETag), valida, mescla por
 * prioridade e mantém cache offline no Room.
 */
class CatalogRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val db: AppDatabase,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val mutex = Mutex()
    private var refreshing = false

    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()

    private val _loadProgress = MutableStateFlow<LoadProgress?>(null)
    val loadProgress: StateFlow<LoadProgress?> = _loadProgress.asStateFlow()

    val repoDao get() = db.dataRepoDao()
    val cacheDao get() = db.repoCacheDao()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultRepo()
            loadFromCache()
        }
    }

    /** Cadastra o repositório padrão (port-db) se a tabela estiver vazia. */
    private suspend fun ensureDefaultRepo() {
        mutex.withLock {
            if (repoDao.count() == 0) {
                val resolved = resolveRepoInput(CatalogUrls.DEFAULT_REPO_URL).getOrNull()
                repoDao.upsert(
                    DataRepoEntity(
                        id = "port-db",
                        name = CatalogUrls.DEFAULT_REPO_NAME,
                        url = resolved?.catalogUrl ?: "https://raw.githubusercontent.com/deivid22srk/port-db/HEAD/data/games.json",
                        baseUrl = resolved?.baseUrl ?: "https://raw.githubusercontent.com/deivid22srk/port-db/HEAD/",
                        siteBase = "",
                        enabled = true,
                        sortIndex = 0,
                        lastUpdated = 0L,
                        etag = null,
                        gameCount = 0,
                        status = "pendente",
                        lastError = null,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Resolução de URL do repositório
    // ------------------------------------------------------------------

    /**
     * Aceita:
     *  1. URL de JSON direto (…/data/games.json);
     *  2. URL de repositório GitHub (https://github.com/<dono>/<repo>) —
     *     localiza `data/games.json` na branch padrão (raw …/HEAD/…).
     */
    fun resolveRepoInput(input: String): Result<ResolvedRepo> {
        val url = input.trim().removeSuffix("/")
        if (url.isBlank()) return Result.failure(IOException("Informe a URL do repositório."))
        if (!url.startsWith("https://")) {
            return Result.failure(IOException("Por segurança, use apenas URLs https://"))
        }

        if (url.endsWith(".json", ignoreCase = true)) {
            val base = jsonBaseFor(url)
            return Result.success(ResolvedRepo(catalogUrl = url, baseUrl = base))
        }

        val gh = Regex("^https://github\\.com/([^/]+)/([^/]+)/?$").find(url)
        if (gh != null) {
            val (owner, repo) = gh.destructured
            val slug = "$owner/$repo"
            return Result.success(
                ResolvedRepo(
                    catalogUrl = "https://raw.githubusercontent.com/$slug/HEAD/data/games.json",
                    baseUrl = "https://raw.githubusercontent.com/$slug/HEAD/",
                    slug = slug,
                ),
            )
        }

        return Result.failure(
            IOException("URL não reconhecida. Use um link de repositório GitHub (https://github.com/dono/repo) ou o link direto do games.json."),
        )
    }

    /** Base de imagens para um JSON direto: sobe até a raiz "data/" (ou a pasta do arquivo). */
    private fun jsonBaseFor(url: String): String {
        val after = url.removePrefix("https://")
        val pathStart = after.indexOf('/') + 1
        val path = after.substring(pathStart)
        val dir = path.substringBeforeLast('/')
        val root = if (dir == "data" || dir.endsWith("/data")) dir.removeSuffix("/data").removeSuffix("data") else dir
        return "https://" + after.substring(0, pathStart) + root + "/"
    }

    data class ResolvedRepo(
        val catalogUrl: String,
        val baseUrl: String,
        val slug: String? = null,
    )

    fun repoIdFor(resolved: ResolvedRepo): String {
        resolved.slug?.let { return it.replace('/', '_') }
        val md = MessageDigest.getInstance("SHA-256").digest(resolved.catalogUrl.toByteArray())
        return "raw-" + md.take(8).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Verificação / CRUD de repositórios
    // ------------------------------------------------------------------

    /** Baixa e valida o JSON, sem salvar. Retorna contagem de jogos. */
    suspend fun verifyRepo(input: String): Result<RepoVerification> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveRepoInput(input).getOrElse { throw it }
            val body = downloadLimited(resolved.catalogUrl, null)
            val parsed = json.decodeFromString<Catalog>(body)
            val games = parsed.games.filter { it.id.isNotBlank() && it.title.isNotBlank() }
            if (games.isEmpty()) throw IOException("O JSON não contém jogos válidos (precisa de \"games\" com id e título).")
            RepoVerification(
                gameCount = games.size,
                suggestedName = parsed.site.name.ifBlank { resolved.slug ?: "Repositório" },
                catalogUrl = resolved.catalogUrl,
                baseUrl = resolved.baseUrl,
            )
        }
    }

    suspend fun addRepo(name: String, input: String): Result<DataRepoEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolveRepoInput(input).getOrElse { throw it }
            val id = repoIdFor(resolved)
            val existing = repoDao.get(id)
            val maxIndex = repoDao.all().maxOfOrNull { it.sortIndex } ?: -1
            val entity = DataRepoEntity(
                id = id,
                name = name.ifBlank { resolved.slug ?: "Repositório" },
                url = resolved.catalogUrl,
                baseUrl = resolved.baseUrl,
                siteBase = existing?.siteBase ?: "",
                enabled = true,
                sortIndex = existing?.sortIndex ?: (maxIndex + 1),
                lastUpdated = existing?.lastUpdated ?: 0L,
                etag = existing?.etag,
                gameCount = existing?.gameCount ?: 0,
                status = existing?.status ?: "pendente",
                lastError = null,
            )
            repoDao.upsert(entity)
            entity
        }
    }

    suspend fun updateRepo(id: String, name: String, input: String): Result<DataRepoEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolved = resolveRepoInput(input).getOrElse { throw it }
                val existing = repoDao.get(id) ?: throw IOException("Repositório não encontrado.")
                val newId = repoIdFor(resolved)
                val entity = existing.copy(
                    id = newId,
                    name = name.ifBlank { existing.name },
                    url = resolved.catalogUrl,
                    baseUrl = resolved.baseUrl,
                )
                if (newId != existing.id) {
                    cacheDao.delete(existing.id)
                    repoDao.delete(existing.id)
                }
                repoDao.upsert(entity)
                entity
            }
        }

    suspend fun removeRepo(id: String) = withContext(Dispatchers.IO) {
        cacheDao.delete(id)
        repoDao.delete(id)
        rebuildCatalogFromCache()
    }

    suspend fun setRepoEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        repoDao.setEnabled(id, enabled, System.currentTimeMillis())
        rebuildCatalogFromCache()
    }

    suspend fun reorderRepo(id: String, up: Boolean) = withContext(Dispatchers.IO) {
        val repos = repoDao.all()
        val index = repos.indexOfFirst { it.id == id }
        if (index < 0) return@withContext
        val target = if (up) index - 1 else index + 1
        if (target !in repos.indices) return@withContext
        val a = repos[index]
        val b = repos[target]
        repoDao.setSortIndex(a.id, b.sortIndex)
        repoDao.setSortIndex(b.id, a.sortIndex)
        rebuildCatalogFromCache()
    }

    suspend fun observeRepos() = repoDao.observeAll()

    // ------------------------------------------------------------------
    // Carga do catálogo
    // ------------------------------------------------------------------

    /** Reconstrói o catálogo apenas a partir dos caches (abertura offline). */
    suspend fun loadFromCache(): Catalog? = withContext(Dispatchers.IO) {
        val repos = repoDao.all().filter { it.enabled }
        val merged = mergeFromCache(repos)
        _catalog.value = merged
        merged
    }

    private suspend fun mergeFromCache(repos: List<DataRepoEntity>): Catalog {
        val mergedGames = LinkedHashMap<String, Game>()
        var site = SiteInfo()
        for (repo in repos) {
            val cached = cacheDao.get(repo.id) ?: continue
            val parsed = runCatching { json.decodeFromString<Catalog>(cached.body) }.getOrNull() ?: continue
            if (site.name.isBlank() && parsed.site.name.isNotBlank()) site = parsed.site
            applyContext(parsed, repo)
            for (g in parsed.games) {
                if (g.id.isBlank() || g.title.isBlank()) continue
                if (!mergedGames.containsKey(g.id)) mergedGames[g.id] = g
            }
        }
        return Catalog(site = site, games = mergedGames.values.toList())
    }

    private suspend fun rebuildCatalogFromCache() {
        val repos = repoDao.all().filter { it.enabled }
        _catalog.value = mergeFromCache(repos)
    }

    /** Aplica base de imagens/site do repositório em cada jogo. */
    private fun applyContext(catalog: Catalog, repo: DataRepoEntity) {
        val siteBase = catalog.site.github
            ?.let { gh -> Regex("github\\.com/([^/]+)/([^/]+)").find(gh)?.destructured }
            ?.let { (owner, repoName) -> "https://$owner.github.io/$repoName/" }
            ?: repo.siteBase
        if (repo.siteBase != siteBase) {
            CoroutineScope(Dispatchers.IO).launch {
                repoDao.updateTargets(repo.id, repo.name, repo.url, repo.baseUrl, siteBase)
            }
        }
        catalog.games.forEach { g ->
            g.repoBaseUrl = repo.baseUrl
            g.siteBaseUrl = siteBase
            g.sourceRepoName = repo.name
        }
    }

    /**
     * Atualiza todos os repositórios ativos (paralelo, com ETag), gravando
     * cache e publicando progresso para a tela de carregamento.
     */
    suspend fun refreshAll(force: Boolean = false): RefreshResult = mutex.withLock {
        if (refreshing) {
            return@withLock RefreshResult(okRepos = 1, failedRepos = 0, totalGames = _catalog.value?.games?.size ?: 0, hasCache = true)
        }
        refreshing = true
        try {
            withContext(Dispatchers.IO) {
                val repos = repoDao.all().filter { it.enabled }
                _loadProgress.value = LoadProgress(
                    phase = LoadPhase.BUSCANDO,
                    repos = repos.map { RepoLoadStatus(it.id, it.name, "aguardando") },
                    hasCache = cacheDao.all().isNotEmpty(),
                )

                // Fase 1-2: download paralelo de cada repositório.
                val bodies = mutableMapOf<String, Pair<String, String?>>() // repoId -> body/etag
                val errors = mutableMapOf<String, String>()
                var doneCount = 0
                var bytesTotal = 0L
                var bytesDone = 0L

                kotlinx.coroutines.coroutineScope {
                    repos.forEach { repo ->
                        launch {
                            val cached = cacheDao.get(repo.id)
                            try {
                                _loadProgress.value = progressFor(repos, repo.id, "baixando")
                                val (body, etag, size) = fetchRepoBody(repo, cached?.etag, force)
                                bytesTotal += size
                                bytesDone += size
                                bodies[repo.id] = body to etag
                            } catch (e: Exception) {
                                // 304 => usa o cache
                                if (e is NotModifiedException && cached != null) {
                                    bodies[repo.id] = cached.body to cached.etag
                                } else {
                                    errors[repo.id] = e.message ?: "Falha desconhecida"
                                }
                            }
                            doneCount++
                            _loadProgress.value = _loadProgress.value?.copy(
                                overall = (doneCount.toFloat() / repos.size.coerceAtLeast(1)) * 0.9f,
                                downloadedBytes = bytesDone,
                                totalBytes = bytesTotal.takeIf { it > 0 } ?: 0,
                            )
                        }
                    }
                }

                // Fase 3: parse + gravação.
                _loadProgress.value = _loadProgress.value?.copy(phase = LoadPhase.PROCESSANDO)
                var totalGames = 0
                var okCount = 0
                val statusList = repos.map { repo ->
                    val body = bodies[repo.id]
                    when {
                        body != null -> {
                            try {
                                val parsed = json.decodeFromString<Catalog>(body.first)
                                val games = parsed.games.filter { it.id.isNotBlank() && it.title.isNotBlank() }
                                if (games.isEmpty()) throw IOException("Nenhum jogo válido no JSON.")
                                val siteBase = parsed.site.github
                                    ?.let { gh -> Regex("github\\.com/([^/]+)/([^/]+)").find(gh)?.destructured }
                                    ?.let { (owner, repoName) -> "https://$owner.github.io/$repoName/" }
                                    ?: ""
                                cacheDao.upsert(
                                    RepoCacheEntity(
                                        repoId = repo.id,
                                        body = body.first,
                                        etag = body.second ?: repo.etag,
                                        fetchedAt = System.currentTimeMillis(),
                                    ),
                                )
                                repoDao.updateStatus(repo.id, "ok", null, System.currentTimeMillis(), games.size, body.second ?: repo.etag)
                                if (repo.siteBase != siteBase) {
                                    repoDao.updateTargets(repo.id, repo.name, repo.url, repo.baseUrl, siteBase)
                                }
                                okCount++
                                totalGames += games.size
                                RepoLoadStatus(repo.id, repo.name, "ok")
                            } catch (e: Exception) {
                                repoDao.updateStatus(repo.id, "erro", e.message, System.currentTimeMillis(), 0, repo.etag)
                                errors[repo.id] = e.message ?: "JSON inválido"
                                RepoLoadStatus(repo.id, repo.name, "falhou", e.message)
                            }
                        }
                        else -> RepoLoadStatus(repo.id, repo.name, "falhou", errors[repo.id])
                    }
                }

                // Recarrega as linhas (status atualizado) e reconstrói o catálogo.
                val freshRepos = repoDao.all().filter { it.enabled }
                val merged = mergeFromCache(freshRepos)
                _catalog.value = merged

                val anyFailed = statusList.any { it.state == "falhou" }
                val allFailed = statusList.isNotEmpty() && statusList.all { it.state == "falhou" }
                _loadProgress.value = LoadProgress(
                    phase = LoadPhase.PROCESSANDO,
                    overall = 0.95f,
                    repos = statusList,
                    anyFailed = anyFailed,
                    allFailed = allFailed,
                    hasCache = cacheDao.all().isNotEmpty(),
                    totalGames = merged.games.size,
                )
                RefreshResult(
                    okRepos = okCount,
                    failedRepos = repos.size - okCount,
                    totalGames = merged.games.size,
                    hasCache = cacheDao.all().isNotEmpty(),
                )
            }
        } finally {
            refreshing = false
        }
    }

    /** Fase 4 — pré-carga leve de capas via Coil (chamada pela UI após PROCESSANDO). */
    suspend fun markImagesPhase() {
        _loadProgress.value = _loadProgress.value?.copy(phase = LoadPhase.IMAGENS, overall = 0.97f)
    }

    suspend fun markDone() {
        _loadProgress.value = _loadProgress.value?.copy(phase = LoadPhase.PRONTO, overall = 1f)
    }

    fun clearProgress() {
        _loadProgress.value = null
    }

    private fun progressFor(repos: List<DataRepoEntity>, repoId: String, state: String): LoadProgress {
        val current = _loadProgress.value
        val updated = (current?.repos ?: repos.map { RepoLoadStatus(it.id, it.name, "aguardando") }).map {
            if (it.repoId == repoId) it.copy(state = state) else it
        }
        return LoadProgress(
            phase = LoadPhase.BAIXANDO,
            overall = current?.overall ?: 0f,
            currentRepo = repos.firstOrNull { it.id == repoId }?.name,
            repos = updated,
            hasCache = current?.hasCache ?: false,
        )
    }

    private class NotModifiedException : IOException("Não modificado (304)")

    /** Baixa com limite de 10 MB e suporte a If-None-Match. Retorna (body, etag, size). */
    private fun fetchRepoBody(repo: DataRepoEntity, cachedEtag: String?, force: Boolean): Triple<String, String?, Long> {
        val builder = Request.Builder().url(repo.url)
        if (!force && cachedEtag != null) builder.header("If-None-Match", cachedEtag)
        client.newCall(builder.build()).execute().use { resp ->
            when {
                resp.code == 304 -> throw NotModifiedException()
                resp.code == 404 -> throw IOException("games.json não encontrado neste repositório (404).")
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code} ao acessar o repositório.")
            }
            val bytes = readLimited(resp.body?.byteStream() ?: throw IOException("Resposta vazia."), MAX_BODY_BYTES)
            val body = bytes.toString(Charsets.UTF_8)
            if (body.isBlank()) throw IOException("Resposta vazia do repositório.")
            return Triple(body, resp.header("ETag"), bytes.size.toLong())
        }
    }

    private fun downloadLimited(url: String, cachedEtag: String?): String {
        val builder = Request.Builder().url(url)
        if (cachedEtag != null) builder.header("If-None-Match", cachedEtag)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ao acessar $url")
            return readLimited(resp.body?.byteStream() ?: throw IOException("Resposta vazia."), MAX_BODY_BYTES)
                .toString(Charsets.UTF_8)
        }
    }

    private fun readLimited(stream: java.io.InputStream, limit: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Arquivo maior que ${limit / (1024 * 1024)} MB — recusado por segurança.")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    companion object {
        const val MAX_BODY_BYTES: Long = 10L * 1024 * 1024
    }
}
