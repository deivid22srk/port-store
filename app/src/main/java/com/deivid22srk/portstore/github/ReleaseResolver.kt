package com.deivid22srk.portstore.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Um APK (asset) de um release. */
data class ApkAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

@Serializable
private data class GHRelease(
    val tag_name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GHAsset> = emptyList(),
)

@Serializable
private data class GHAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
    val digest: String? = null,
)

/**
 * Resolve `links.download` para uma URL de APK direta:
 *  - URL direta (.apk) => usada como está;
 *  - releases do GitHub => consulta a API, lista assets .apk (com digest);
 *  - página do MediaFire => tenta extrair o link direto do HTML;
 *  - qualquer outra => devolve a própria URL (o app abre no navegador).
 */
class ReleaseResolver(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val githubRepoRegex = Regex("github\\.com/([^/]+)/([^/]+)")

    suspend fun resolve(url: String?): Result<List<ApkAsset>> = withContext(Dispatchers.IO) {
        runCatching {
            if (url.isNullOrBlank()) throw IOException("Este port não tem link de download.")

            val looksDirect = url.endsWith(".apk", ignoreCase = true) ||
                Regex("/releases/download/[^/]+/[^/]+\\.apk", RegexOption.IGNORE_CASE).containsMatchIn(url)
            if (looksDirect) return@runCatching listOf(ApkAsset(fileNameOf(url), url, 0L, null))

            if (url.contains("mediafire.com", ignoreCase = true)) {
                // Scrape rápido no Kotlin (atalho); se falhar, NÃO é beco sem
                // saída: devolve a própria página — o motor Rust (core/src/
                // mediafire.rs) extrai o link direto com UA de desktop, camadas
                // de fallback e retries antes de baixar.
                val direct = scrapeMediafire(url)
                if (direct != null) {
                    return@runCatching listOf(ApkAsset(fileNameOf(direct), direct, 0L, null))
                }
                return@runCatching listOf(ApkAsset(mediafirePageName(url), url, 0L, null))
            }

            val gh = githubRepoRegex.find(url)
            if (gh != null) {
                val (owner, repo) = gh.destructured
                return@runCatching resolveGitHub(owner, repo)
            }

            // Desconhecido: devolve a URL como "asset único" direto.
            listOf(ApkAsset(fileNameOf(url), url, 0L, null))
        }
    }

    private fun resolveGitHub(owner: String, repo: String): List<ApkAsset> {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases"
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PortStore/1.0 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 403 || resp.code == 429 ->
                    throw IOException("Limite de requisições da API do GitHub atingido. Tente novamente em alguns minutos.")
                !resp.isSuccessful ->
                    throw IOException("Falha ao consultar releases do GitHub (HTTP ${resp.code}).")
            }
            val body = resp.body?.string().orEmpty()
            val releases = json.decodeFromString<List<GHRelease>>(body)
            val release = releases.firstOrNull { !it.draft }
                ?: throw IOException("Nenhum release publicado neste repositório.")
            val assets = release.assets
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .map { ApkAsset(it.name, it.browser_download_url, it.size, digestSha256(it.digest)) }
            if (assets.isEmpty()) throw IOException("Nenhum APK encontrado no release ${release.tag_name ?: ""}.")
            return assets.sortedByDescending { score(it.name) }
        }
    }

    private fun scrapeMediafire(pageUrl: String): String? {
        val req = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val html = resp.body?.string().orEmpty()
                Regex("https://download[0-9]+\\.mediafire\\.com/[^\"'\\s]+", RegexOption.IGNORE_CASE)
                    .find(html)?.value
            }
        }.getOrNull()
    }

    /**
     * Nome amigável a partir da página do MediaFire, para o caminho em que o
     * scrape falha e a própria página é entregue ao motor Rust:
     * `https://www.mediafire.com/file/CHAVE/nome.apk/file` → `nome.apk`.
     * O nome é apenas exibição — o arquivo de destino usa o id do jogo.
     */
    private fun mediafirePageName(pageUrl: String): String = runCatching {
        val path = pageUrl.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        val afterType = segments.dropWhile { it !in setOf("file", "download") }.drop(1)
        val name = afterType
            .firstOrNull { it != "file" && it.contains('.') }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: "download.apk"
        if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"
    }.getOrDefault("download.apk")

    private fun digestSha256(digest: String?): String? =
        digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.lowercase()

    /** universal/arm64 primeiro, x86 depois. */
    private fun score(name: String): Int = when {
        name.contains("universal", true) -> 100
        name.contains("arm64", true) -> 90
        name.contains("aarch64", true) -> 85
        name.contains("v8a", true) && !name.contains("x86", true) -> 80
        name.contains("v7a", true) -> 60
        name.contains("x86_64", true) || name.contains("x86", true) -> 40
        else -> 20
    }

    private fun fileNameOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "apk" }
}
