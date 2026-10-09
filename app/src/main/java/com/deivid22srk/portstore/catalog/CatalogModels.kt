package com.deivid22srk.portstore.catalog

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Slugs de categoria -> rótulo em português. */
fun categoryLabel(slug: String): String = when (slug) {
    "acao" -> "Ação"
    "aventura" -> "Aventura"
    "plataforma" -> "Plataforma"
    "corrida" -> "Corrida"
    "rpg" -> "RPG"
    "esporte" -> "Esporte"
    "luta" -> "Luta"
    "tiro" -> "Tiro"
    "puzzle" -> "Puzzle"
    "estrategia" -> "Estratégia"
    "simulacao" -> "Simulação"
    else -> slug.replaceFirstChar { it.uppercase() }
}

/**
 * Aceita `packageName` como string única OU lista de strings (variantes),
 * além de null/ausente. Normaliza sempre para List<String>.
 */
object PackageNamesSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder ?: return emptyList()
        return when (val el = json.decodeJsonElement()) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(el.content.trim()).filter { it.isNotBlank() }
            is JsonArray -> el.mapNotNull { item ->
                (item as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }
}

object CatalogUrls {
    /** Legado (port-droid), usado apenas como fallback se o repo não informar base. */
    const val LEGACY_REPO_BASE = "https://raw.githubusercontent.com/deivid22srk/port-droid/main/"
    const val LEGACY_SITE_BASE = "https://deivid22srk.github.io/port-droid/"
    const val GITHUB_PORT_DB = "https://github.com/deivid22srk/port-db"
    const val DEFAULT_REPO_URL = GITHUB_PORT_DB
    const val DEFAULT_REPO_NAME = "Port DB (Hail Games)"
    const val YOUTUBE_CHANNEL = "https://www.youtube.com/@Hail-Games1"
    const val TELEGRAM = "https://t.me/hailgames2"
}

@Serializable
data class SiteInfo(
    val name: String = "",
    val tagline: String = "",
    val updated: String? = null,
    val youtube: String? = null,
    val telegram: String? = null,
    val github: String? = null,
)

@Serializable
data class Game(
    val id: String,
    val title: String,
    val port: String? = null,
    val original: String? = null,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val chipsets: List<String> = emptyList(),
    val performance: String? = null,
    val apkSize: String? = null,
    val storage: String? = null,
    val updated: String? = null,
    val dateAdded: String? = null,
    val status: String = "ativo",
    val statusLabel: String? = null,
    val featured: Boolean = false,
    val downloads: Long = 0,
    val cover: String? = null,
    val banner: String? = null,
    val screenshots: List<String> = emptyList(),
    val videoId: String? = null,
    val shortDescription: String? = null,
    val description: List<String> = emptyList(),
    val requirements: Requirements? = null,
    val chipsetRecommended: String? = null,
    @Serializable(with = PackageNamesSerializer::class)
    val packageName: List<String> = emptyList(),
    val controls: List<String> = emptyList(),
    val install: List<String> = emptyList(),
    val notes: String? = null,
    val links: GameLinks = GameLinks(),
    val credit: Credit? = null,
    val license: String? = null,
    val type: String = "android",
) {
    /** Base raw das imagens, preenchida pelo repositório de origem após o parse. */
    @Transient
    var repoBaseUrl: String = ""

    /** Base do site (GitHub Pages) para links "./..." de jogos web. */
    @Transient
    var siteBaseUrl: String = ""

    /** Repositório de dados de onde este jogo veio (informativo). */
    @Transient
    var sourceRepoName: String = ""

    val isWeb: Boolean get() = type == "web"
    val isSoon: Boolean get() = status == "em-breve"
    val isRemoved: Boolean get() = status == "removido"
    val isAvailable: Boolean get() = !isRemoved && (status == "ativo" || status == "web")

    val hasApk: Boolean get() = !isWeb && (links.download != null || links.github != null)

    val primaryPackage: String? get() = packageName.firstOrNull { it.isNotBlank() }

    val playableUrl: String?
        get() = links.play?.let { relative ->
            if (relative.startsWith("http")) {
                relative
            } else {
                val base = siteBaseUrl.ifBlank { CatalogUrls.LEGACY_SITE_BASE }
                base + relative.removePrefix("./").removePrefix("/")
            }
        }

    val coverUrl: String? get() = cover?.toImageUrl()
    val bannerUrl: String? get() = banner?.toImageUrl()
    val screenshotUrls: List<String> get() = screenshots.mapNotNull { it.toImageUrl() }
    val videoUrl: String?
        get() = videoId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
    val videoThumbUrl: String?
        get() = videoId?.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    /** "Leve" / "Médio" / "Pesado" ou null. */
    val performanceLabel: String?
        get() = when (performance) {
            "leve" -> "Leve"
            "medio" -> "Médio"
            "pesado" -> "Pesado"
            else -> null
        }

    private fun String.toImageUrl(): String? {
        val path = removePrefix("./").removePrefix("/")
        if (path.isBlank()) return null
        if (startsWith("http")) return this
        val base = repoBaseUrl.ifBlank { CatalogUrls.LEGACY_REPO_BASE }
        return base + path
    }
}

@Serializable
data class Requirements(
    val minimo: Map<String, String> = emptyMap(),
    val recomendado: Map<String, String> = emptyMap(),
)

@Serializable
data class GameLinks(
    val download: String? = null,
    val github: String? = null,
    val releases: String? = null,
    val play: String? = null,
    val tutorial: String? = null,
    val site: String? = null,
    val reference: String? = null,
)

@Serializable
data class Credit(
    val port: String? = null,
    val portUrl: String? = null,
    val original: String? = null,
    val originalUrl: String? = null,
    val game: String? = null,
)

@Serializable
data class Catalog(
    val site: SiteInfo = SiteInfo(),
    val games: List<Game> = emptyList(),
)
