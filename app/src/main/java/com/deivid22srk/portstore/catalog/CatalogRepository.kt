package com.deivid22srk.portstore.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class CatalogLoad(
    val catalog: Catalog,
    val fromCache: Boolean,
)

/**
 * Carrega o catálogo do port-droid com cache offline (arquivo local +
 * If-None-Match/ETag). Sem internet, abre com o último catálogo salvo.
 */
class CatalogRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mutex = Mutex()

    private val cacheFile: File get() = File(context.filesDir, "games_cache.json")
    private val etagFile: File get() = File(context.filesDir, "games_etag.txt")

    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()

    suspend fun load(force: Boolean = false): CatalogLoad = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = fetchOrCache(force)
            _catalog.value = result.catalog
            result
        }
    }

    private fun fetchOrCache(force: Boolean): CatalogLoad {
        val etag = runCatching {
            etagFile.takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull().takeUnless { it.isNullOrEmpty() }

        if (!force) {
            // Tenta rede primeiro; qualquer problema cai no cache local.
            try {
                val req = Request.Builder().url(CatalogUrls.CATALOG_JSON).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val parsed = json.decodeFromString<Catalog>(body)
                            runCatching {
                                cacheFile.writeText(body)
                                resp.header("ETag")?.let { etagFile.writeText(it) }
                            }
                            return CatalogLoad(parsed, fromCache = false)
                        }
                    }
                    // 304/erro HTTP: segue para o cache.
                }
            } catch (_: Exception) {
                // offline: segue para o cache
            }
        } else {
            try {
                val builder = Request.Builder().url(CatalogUrls.CATALOG_JSON)
                if (etag != null) builder.header("If-None-Match", etag)
                client.newCall(builder.build()).execute().use { resp ->
                    when {
                        resp.code == 304 -> { /* segue para o cache */ }
                        resp.isSuccessful -> {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                val parsed = json.decodeFromString<Catalog>(body)
                                runCatching {
                                    cacheFile.writeText(body)
                                    resp.header("ETag")?.let { etagFile.writeText(it) }
                                }
                                return CatalogLoad(parsed, fromCache = false)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // offline: segue para o cache
            }
        }

        val cached = readCache()
            ?: throw IOException("Sem conexão e nenhum catálogo em cache.")
        return CatalogLoad(cached, fromCache = true)
    }

    private fun readCache(): Catalog? = runCatching {
        if (!cacheFile.exists()) return null
        val body = cacheFile.readText()
        if (body.isBlank()) return null
        json.decodeFromString<Catalog>(body)
    }.getOrNull()
}
