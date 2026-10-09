package com.deivid22srk.portstore.github

import com.deivid22srk.portstore.db.AppDatabase
import com.deivid22srk.portstore.db.VersionCacheEntity
import com.deivid22srk.portstore.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import android.os.Build
import java.io.IOException

/** Informações de versão de um port, vindas das releases do GitHub. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String?,
    val notes: String?,
    val publishedAt: String?,
    val assets: List<ApkAsset>,
)

/** Estado da resolução de versão exposto à UI. */
sealed interface VersionState {
    data object Loading : VersionState
    data class Resolved(val release: ReleaseInfo, val fromCache: Boolean) : VersionState
    data object Unavailable : VersionState
    data class RateLimited(val resetAtEpochSec: Long?) : VersionState
}

@Serializable
private data class VGRelease(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String? = null,
    val assets: List<VGAsset> = emptyList(),
)

@Serializable
private data class VGAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
    val digest: String? = null,
)

/**
 * Resolve a versão de cada jogo dinamicamente a partir do repositório do
 * autor (`links.github` -> releases). Consulta sob demanda, com cache no
 * Room (TTL), requisições condicionais (ETag) e respeito ao rate limit da
 * API do GitHub (403/429 -> usa cache e avisa; nunca entra em loop).
 */
class VersionResolver(
    private val client: OkHttpClient,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    companion object {
        const val TTL_MS: Long = 6L * 60 * 60 * 1000 // 6 horas
        private const val UA = "PortStore/1.0 (Android)"

        /** Extrai "dono/repo" de links.github / links.releases. */
        fun repoSlug(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return Regex("github\\.com/([^/]+)/([^/#?]+)").find(url)
                ?.destructured?.let { (o, r) -> "$o/${r.removeSuffix("/")}" }
        }

        /** Ordenação de assets por compatibilidade com o aparelho. */
        fun scoreForAbi(name: String, abis: Array<String>): Int {
            var score = when {
                name.contains("universal", true) -> 100
                else -> 20
            }
            abis.forEach { abi ->
                when (abi) {
                    "arm64-v8a" -> if (name.contains("arm64", true) || name.contains("v8a", true)) score = maxOf(score, 95)
                    "armeabi-v7a" -> if (name.contains("v7a", true)) score = maxOf(score, 60)
                    "x86_64" -> if (name.contains("x86_64", true)) score = maxOf(score, 40)
                }
            }
            if (name.contains("x86", true) && !name.contains("x86_64", true)) score = minOf(score, 30)
            return score
        }
    }

    /**
     * Resolve a versão mais recente do jogo. Usa cache com TTL e ETag;
     * em rate limit devolve o cache (ou RateLimited).
     */
    suspend fun resolve(gameId: String, githubUrl: String?, force: Boolean = false): VersionState =
        withContext(Dispatchers.IO) {
            val slug = repoSlug(githubUrl)
                ?: return@withContext VersionState.Unavailable
            val now = System.currentTimeMillis()
            val cached = db.versionCacheDao().get(gameId)

            if (!force && cached != null && now - cached.fetchedAt < TTL_MS) {
                return@withContext cached.toState(fromCache = true)
            }

            mutex.withLock {
                val cachedAgain = db.versionCacheDao().get(gameId)
                if (!force && cachedAgain != null && System.currentTimeMillis() - cachedAgain.fetchedAt < TTL_MS) {
                    return@withContext cachedAgain.toState(fromCache = true)
                }

                try {
                    val release = fetchLatest(slug) ?: run {
                        if (cachedAgain != null) return@withContext cachedAgain.toState(fromCache = true)
                        return@withContext VersionState.Unavailable
                    }
                    val assetsJson = json.encodeToString(
                        ListSerializer(ApkAssetDto.serializer()),
                        release.assets.map {
                            ApkAssetDto(it.name, it.url, it.size, it.sha256)
                        },
                    )
                    db.versionCacheDao().upsert(
                        VersionCacheEntity(
                            gameId = gameId,
                            repoSlug = slug,
                            tag = release.tag,
                            releaseName = release.title,
                            notes = release.notes,
                            publishedAt = release.publishedAt,
                            assetsJson = assetsJson,
                            etag = release.etag,
                            fetchedAt = System.currentTimeMillis(),
                        ),
                    )
                    VersionState.Resolved(release.info, fromCache = false)
                } catch (e: RateLimitException) {
                    if (cachedAgain != null) cachedAgain.toState(fromCache = true)
                    else VersionState.RateLimited(e.resetAt)
                } catch (_: Exception) {
                    if (cachedAgain != null) cachedAgain.toState(fromCache = true)
                    else VersionState.Unavailable
                }
            }
        }

    private class RateLimitException(val resetAt: Long?) : IOException("Rate limit da API do GitHub")

    @Serializable
    private data class ApkAssetDto(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String? = null,
    )

    private data class FetchedRelease(
        val tag: String?,
        val name: String?,
        val body: String?,
        val publishedAt: String?,
        val assets: List<ApkAsset>,
        val etag: String?,
    ) {
        val info: ReleaseInfo
            get() = ReleaseInfo(
                tag = tag ?: "",
                version = com.deivid22srk.portstore.util.VersionCompare.normalize(tag) ?: tag.orEmpty(),
                title = name,
                notes = body,
                publishedAt = publishedAt,
                assets = assets,
            )
    }

    private fun VersionCacheEntity.toState(fromCache: Boolean): VersionState {
        val assets = runCatching {
            json.decodeFromString(ListSerializer(ApkAssetDto.serializer()), assetsJson)
        }.getOrDefault(emptyList())
        return VersionState.Resolved(
            ReleaseInfo(
                tag = tag,
                version = com.deivid22srk.portstore.util.VersionCompare.normalize(tag) ?: tag,
                title = releaseName,
                notes = notes,
                publishedAt = publishedAt,
                assets = assets.map { ApkAsset(it.name, it.url, it.size, it.sha256) },
            ),
            fromCache = fromCache,
        )
    }

    private fun request(url: String, etag: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
        if (etag != null) builder.header("If-None-Match", etag)
        val token = settings.settings.value.githubToken
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    /** Consulta /releases/latest; 404 => /releases?per_page=5 (primeira não-draft). */
    private fun fetchLatest(slug: String): FetchedRelease? {
        // 1) latest
        client.newCall(request("https://api.github.com/repos/$slug/releases/latest", null)).execute().use { resp ->
            when {
                resp.code == 403 || resp.code == 429 ->
                    throw RateLimitException(resp.header("X-RateLimit-Reset")?.toLongOrNull())
                resp.code == 404 -> { /* segue para /releases */ }
                resp.isSuccessful -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val release = json.decodeFromString<VGRelease>(body)
                        if (!release.draft) return release.toFetched(resp.header("ETag"))
                    }
                }
                else -> throw IOException("HTTP ${resp.code} ao consultar releases")
            }
        }
        // 2) lista
        client.newCall(request("https://api.github.com/repos/$slug/releases?per_page=5", null)).execute().use { resp ->
            when {
                resp.code == 403 || resp.code == 429 ->
                    throw RateLimitException(resp.header("X-RateLimit-Reset")?.toLongOrNull())
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code} ao consultar releases")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val releases = json.decodeFromString<List<VGRelease>>(body)
            val allowPre = settings.settings.value.allowPrerelease
            val release = releases.firstOrNull { !it.draft && (allowPre || !it.prerelease) }
                ?: releases.firstOrNull { !it.draft }
                ?: return null
            return release.toFetched(resp.header("ETag"))
        }
    }

    private fun VGRelease.toFetched(etag: String?): FetchedRelease {
        val abis = Build.SUPPORTED_ABIS
        val mapped = assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .map {
                ApkAsset(
                    name = it.name,
                    url = it.browser_download_url,
                    size = it.size,
                    sha256 = it.digest?.takeIf { d -> d.startsWith("sha256:", true) }?.substringAfter(':')?.lowercase(),
                )
            }
            .sortedByDescending { scoreForAbi(it.name, abis) }
        return FetchedRelease(
            tag = tag_name,
            name = name,
            body = body,
            publishedAt = published_at,
            assets = mapped,
            etag = etag,
        )
    }
}
