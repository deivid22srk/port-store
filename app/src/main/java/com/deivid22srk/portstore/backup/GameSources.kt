package com.deivid22srk.portstore.backup

import com.deivid22srk.portstore.virtual.VirtualGamePaths
import java.io.File

/**
 * Uma entrada a exportar: arquivo do ambiente virtual + onde ele vai no TAR.
 * [file] null + [inlineBytes] != null = conteúdo gerado (registry.json).
 */
data class SourceEntry(
    val category: BackupCategory,
    val tarPath: String,
    val file: File?,
    val bytes: Long,
    val inlineBytes: ByteArray? = null,
)

/** Resultado do plano de exportação (pré-cálculo rápido, só lstat). */
data class ExportPlan(
    val gameId: String,
    val gameTitle: String,
    val packageName: String,
    val versionName: String?,
    val entries: List<SourceEntry>,
    val totalBytes: Long,
    val sizeByCategory: Map<BackupCategory, Long>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Coleta as fontes de um jogo virtual para o pacote, com o mapeamento:
 *
 * | Categoria | Origem no ambiente virtual (Bcore)                  | Caminho no TAR        |
 * |-----------|-----------------------------------------------------|-----------------------|
 * | install   | data/app/<pkg>/base.apk                             | install/apk/base.apk  |
 * | install   | Android/obb/<pkg> (armazenamento externo virtual)   | install/obb/...       |
 * | install   | Android/data/<pkg> (armazenamento externo virtual)  | install/extdata/...   |
 * | install   | data/user/0/<pkg> MENOS shared_prefs/databases e    | install/data/...      |
 * |           | diretórios regeneráveis (cache/code_cache/lib)      |                       |
 * | saves     | data/user/0/<pkg>/databases                         | saves/databases/...   |
 * | settings  | data/user/0/<pkg>/shared_prefs                      | settings/shared_prefs/... |
 * | launcher  | vínculo gameId<->pacote (VirtualRegistry)           | launcher/registry.json|
 *
 * O APK base NÃO é restaurado diretamente na importação: ele vai para o
 * cache do Bcore e o instalador do Bcore recria app dir + libs +
 * package.conf (por isso lib/ e package.conf não são exportados).
 *
 * A varredura é determinística (ordem estável por caminho) — exigido pela
 * retomada: a lista reconstruída precisa ser idêntica à original.
 */
object GameSources {

    /**
     * Cache do último plano coletado (pelo diálogo de exportação). O worker
     * reusa o plano SEM revarrer a árvore inteira — a varredura dupla sobre
     * FUSE era a causa de "Preparando…" demorar minutos em jogos grandes.
     */
    private var cachedPlan: ExportPlan? = null

    fun remember(plan: ExportPlan) {
        cachedPlan = plan
    }

    /** Plano em cache do jogo, filtrado pelas categorias pedidas (ou null). */
    fun recall(gameId: String, categories: Set<BackupCategory>): ExportPlan? {
        val cached = cachedPlan?.takeIf { it.gameId == gameId } ?: return null
        val entries = cached.entries.filter { it.category in categories }
        if (entries.isEmpty()) return null
        return ExportPlan(
            gameId = cached.gameId,
            gameTitle = cached.gameTitle,
            packageName = cached.packageName,
            versionName = cached.versionName,
            entries = entries,
            totalBytes = entries.sumOf { it.bytes },
            sizeByCategory = entries.groupingBy { it.category }.fold(0L) { acc, e -> acc + e.bytes },
        )
    }

    /** Diretórios de primeira ordem do data dir que NÃO vão em install/data. */
    private val DATA_DIR_EXCLUDED = setOf(
        "shared_prefs", "databases", // categorias próprias
        "cache", "code_cache", "lib", // regeneráveis/instalados pelo motor
    )

    fun collect(
        gameId: String,
        gameTitle: String,
        packageName: String,
        versionName: String?,
        paths: VirtualGamePaths,
        registryInlineJson: String?,
        categories: Set<BackupCategory>,
    ): ExportPlan {
        val entries = ArrayList<SourceEntry>()

        if (BackupCategory.INSTALL in categories) {
            paths.baseApk?.takeIf { it.exists() && it.isFile }?.let { apk ->
                entries.add(SourceEntry(BackupCategory.INSTALL, TAR_APK, apk, apk.length()))
            }
            addTree(entries, BackupCategory.INSTALL, paths.obbDir, TAR_OBB_PREFIX)
            addTree(entries, BackupCategory.INSTALL, paths.externalDataDir, TAR_EXTDATA_PREFIX)
            paths.dataDir?.takeIf { it.exists() && it.isDirectory }?.let { data ->
                data.listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name !in DATA_DIR_EXCLUDED }
                    .forEach { sub -> addTree(entries, BackupCategory.INSTALL, sub, "$TAR_DATA_PREFIX/${sub.name}") }
                // Arquivos soltos na raiz do data dir (raro, mas real em ports).
                data.listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .forEach { f ->
                        entries.add(
                            SourceEntry(BackupCategory.INSTALL, "$TAR_DATA_PREFIX/${f.name}", f, f.length()),
                        )
                    }
            }
        }

        if (BackupCategory.SAVES in categories) {
            addTree(entries, BackupCategory.SAVES, paths.databasesDir, TAR_SAVES_PREFIX)
        }

        if (BackupCategory.SETTINGS in categories) {
            addTree(entries, BackupCategory.SETTINGS, paths.sharedPrefsDir, TAR_SETTINGS_PREFIX)
        }

        if (BackupCategory.LAUNCHER in categories && registryInlineJson != null) {
            entries.add(
                SourceEntry(
                    BackupCategory.LAUNCHER,
                    TAR_REGISTRY,
                    file = null,
                    bytes = registryInlineJson.toByteArray(Charsets.UTF_8).size.toLong(),
                    inlineBytes = registryInlineJson.toByteArray(Charsets.UTF_8),
                ),
            )
        }

        val sorted = entries.sortedBy { it.tarPath }
        return ExportPlan(
            gameId = gameId,
            gameTitle = gameTitle,
            packageName = packageName,
            versionName = versionName,
            entries = sorted,
            totalBytes = sorted.sumOf { it.bytes },
            sizeByCategory = sorted.groupingBy { it.category }.fold(0L) { acc, entry -> acc + entry.bytes },
        )
    }

    private fun addTree(out: MutableList<SourceEntry>, category: BackupCategory, root: File?, tarPrefix: String) {
        root?.takeIf { it.exists() } ?: return
        root.walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedBy { it.path }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                out.add(SourceEntry(category, "$tarPrefix/$rel", f, f.length()))
            }
    }

    // ---- Nomes das entradas no TAR (o importador resolve por prefixo) ----
    const val TAR_APK = "install/apk/base.apk"
    const val TAR_OBB_PREFIX = "install/obb"
    const val TAR_EXTDATA_PREFIX = "install/extdata"
    const val TAR_DATA_PREFIX = "install/data"
    const val TAR_SAVES_PREFIX = "saves/databases"
    const val TAR_SETTINGS_PREFIX = "settings/shared_prefs"
    const val TAR_REGISTRY = "launcher/registry.json"

    /** JSON do vínculo do launcher (categoria launcher) para embutir no pacote. */
    fun registryJson(gameId: String, packageName: String, apkSizeBytes: Long, installedAt: Long): String {
        val obj = org.json.JSONObject()
            .put("gameId", gameId)
            .put("packageName", packageName)
            .put("apkSizeBytes", apkSizeBytes)
            .put("installedAt", installedAt)
        return obj.toString(2)
    }
}
