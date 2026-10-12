package com.deivid22srk.portstore.gamedata

import android.content.Context
import android.os.StatFs
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.backup.BackupCategory
import com.deivid22srk.portstore.backup.MANIFEST_ENTRY_NAME
import com.deivid22srk.portstore.backup.PackageDestinations
import com.deivid22srk.portstore.backup.PackageReader
import com.deivid22srk.portstore.backup.GameSources
import com.deivid22srk.portstore.core.NativeDownloader
import com.deivid22srk.portstore.virtual.VirtualGamePaths
import com.deivid22srk.portstore.virtual.VirtualInstallPhase
import com.deivid22srk.portstore.virtual.VirtualInstallProgress
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import com.deivid22srk.portstore.virtual.VirtualFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Resultado de [GameDataInstaller.installForGame] (falha = exceção). */
enum class GameDataResult {
    /** Repositório desativado, sem entrada para o jogo ou dados já presentes. */
    SKIPPED,

    /** Dados baixados, decriptados e extraídos no ambiente virtual. */
    INSTALLED,
}

/**
 * Instalador dos DADOS COMPLEMENTARES do jogo a partir do repositório de
 * dados (`port-store-data`) — o segundo tipo de repositório do Port Store.
 *
 * Pipeline (Modo Instalação Virtual ativo + repositório ativado por senha):
 *
 * 1. o APK é baixado pela Loja e instalado no ambiente virtual
 *    (instalação automática existente);
 * 2. [installForGame] localiza a entrada do jogo no `index.json`;
 * 3. baixa o pacote — dois formatos suportados:
 *    - **v2 (atual, por LINK)**: o índice traz a PÁGINA do provedor (ex.
 *      Buzzheavier); o link direto é extraído no motor nativo em Rust
 *      (`buzzheavier.rs`) e o pacote é baixado com retomada por Range;
 *    - **v1 (legado)**: partes criptografadas no próprio repositório,
 *      cada uma validada por SHA-256 com retomada por parte;
 * 4. extrai EM STREAMING direto no ambiente virtual (mesmo mapeamento do
 *    importar) — nunca existe pacote descomprimido em disco;
 * 5. marca o jogo como "dados completos" — a execução fica BLOQUEADA
 *    enquanto o download/instalação não termina (gate na Home e na tela
 *    de detalhes).
 *
 * O progresso é publicado nas fases [VirtualInstallPhase.DOWNLOADING_DATA] /
 * [VirtualInstallPhase.INSTALLING_DATA] do mesmo tracker usado pela
 * instalação do APK — a tela de detalhes do Game Launcher mostra tudo ao vivo.
 */
class GameDataInstaller(
    private val context: Context,
    private val http: okhttp3.OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetcher = GameDataIndexFetcher(http)

    /** gameIds com pipeline em andamento (dedup entre gatilho auto e manual). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Cache do índice (TTL curto — evita requisição a cada consulta de gate). */
    @Volatile
    private var indexCache: Pair<Long, RepoIndex>? = null

    /** Marcadores "dados completos" (em memória + persistidos). */
    private val markers = ConcurrentHashMap<String, InstalledDataMarker>()

    private val markerJson = Json { ignoreUnknownKeys = true }

    init {
        loadMarkers()
    }

    // =====================================================================
    // Ativação (Configurações → senha do repositório)
    // =====================================================================

    /**
     * Valida a senha do repositório contra o verificador PBKDF2 do índice.
     * Lança [GameDataException] (PASSWORD_WRONG / REPO_UNREACHABLE / …) e
     * nunca salva nada — a UI persiste senha+flag só no sucesso.
     *
     * Roda inteiro em Dispatchers.IO: chamadores vêm de escopos Main e
     * tanto a rede quanto o PBKDF2 (180k iterações) não podem travar a UI.
     */
    suspend fun activate(password: String): RepoIndex = withContext(Dispatchers.IO) {
        val index = fetcher.fetch(DEFAULT_DATA_REPO_URL)
        val check = index.passwordCheck
            ?: throw GameDataException(GameDataErrorKind.INDEX_INVALID, "índice sem verificador de senha")
        if (check.kdf != "PBKDF2WithHmacSHA256") {
            throw GameDataException(GameDataErrorKind.INDEX_INVALID, "KDF desconhecido: ${check.kdf}")
        }
        val derived = GameDataCrypto.passwordVerifier(
            password,
            GameDataCrypto.hexToBytes(check.salt),
            check.iterations,
        )
        if (!GameDataCrypto.verifiersMatch(derived, check.verifier)) {
            throw GameDataException(GameDataErrorKind.PASSWORD_WRONG, "senha do repositório incorreta")
        }
        indexCache = System.currentTimeMillis() to index
        index
    }

    // =====================================================================
    // Gate: a execução do jogo fica bloqueada até os dados completarem
    // =====================================================================

    /** Estado do gate de execução para um jogo do catálogo. */
    enum class Gate {
        /** Pode abrir (sem dados pendentes — ou nada a fazer). */
        OPEN,

        /** Download/instalação dos dados em andamento — aguarde. */
        RUNNING,

        /** Dados disponíveis no repositório e ainda não instalados. */
        PENDING,
    }

    /**
     * Consulta BARATA (só memória/arquivo local — nunca rede): usada pela
     * Home e pela tela de detalhes antes de abrir o jogo. Sem índice em
     * cache o gate falha ABERTO (não prender o usuário por uma consulta).
     */
    fun gateFor(gameId: String): Gate {
        if (inFlight.contains(gameId)) return Gate.RUNNING
        val update = AppGraph.autoInstall.progress.value[gameId]
        if (update != null && update.phase != VirtualInstallPhase.DONE) return Gate.RUNNING
        if (!AppGraph.settings.settings.value.dataRepoEnabled) return Gate.OPEN
        val game = AppGraph.catalog.catalog.value?.games?.firstOrNull { it.id == gameId }
            ?: return Gate.OPEN
        if (game.isWeb) return Gate.OPEN
        val virtual = AppGraph.virtual
        val vpkg = virtual.packageNameFor(gameId) ?: return Gate.OPEN
        if (!virtual.isInstalled(vpkg)) return Gate.OPEN
        val entry = cachedEntry(gameId, game.packageName) ?: return Gate.OPEN
        val marker = markers[gameId]
        if (marker != null && marker.versionKey == versionKey(entry)) return Gate.OPEN
        return Gate.PENDING
    }

    /**
     * Dispara o pipeline em background (gate manual: usuário tentou abrir
     * um jogo com dados pendentes). O progresso sai no tracker do
     * [AppGraph.autoInstall] — a tela de detalhes mostra ao vivo.
     */
    fun startInstall(gameId: String) {
        if (!inFlight.add(gameId)) return
        scope.launch {
            try {
                // O slot em inFlight já é deste escopo — usa o pipeline direto.
                installLocked(gameId) { AppGraph.autoInstall.report(it) }
                AppGraph.autoInstall.report(
                    VirtualInstallProgress(
                        gameId = gameId,
                        phase = VirtualInstallPhase.DONE,
                        status = VirtualInstallStatus.Success(
                            AppGraph.virtual.packageNameFor(gameId).orEmpty(),
                        ),
                    ),
                )
            } catch (e: GameDataException) {
                AppGraph.autoInstall.report(
                    VirtualInstallProgress(
                        gameId = gameId,
                        phase = VirtualInstallPhase.DONE,
                        status = VirtualInstallStatus.Failure(e.toVirtualFailure(), e.message),
                    ),
                )
            } catch (t: Throwable) {
                AppGraph.autoInstall.report(
                    VirtualInstallProgress(
                        gameId = gameId,
                        phase = VirtualInstallPhase.DONE,
                        status = VirtualInstallStatus.Failure(VirtualFailure.ENGINE_ERROR, t.message),
                    ),
                )
            } finally {
                inFlight.remove(gameId)
            }
        }
    }

    // =====================================================================
    // Pipeline principal
    // =====================================================================

    /**
     * Baixa + decripta + extrai os dados complementares do jogo. Só emite
     * as fases de dados (o caller emite DONE). Bloqueante — Dispatchers.IO.
     *
     * @throws GameDataException em qualquer falha (partes permanecem em
     *   cache para a próxima tentativa).
     */
    suspend fun installForGame(
        gameId: String,
        onProgress: (VirtualInstallProgress) -> Unit = {},
    ): GameDataResult {
        if (!inFlight.add(gameId)) return GameDataResult.SKIPPED
        try {
            return installLocked(gameId, onProgress)
        } finally {
            inFlight.remove(gameId)
        }
    }

    private suspend fun installLocked(gameId: String, onProgress: (VirtualInstallProgress) -> Unit): GameDataResult {
        // NOTA: chamado pelo startInstall (slot em inFlight já garantido)
        // e pelo instalador automático via installForGame (que garante o slot).
        if (!AppGraph.settings.settings.value.dataRepoEnabled) return GameDataResult.SKIPPED
        val game = AppGraph.catalog.catalog.value?.games?.firstOrNull { it.id == gameId }
            ?: return GameDataResult.SKIPPED
        if (game.isWeb) return GameDataResult.SKIPPED

        val entry = cachedEntry(gameId, game.packageName) ?: return GameDataResult.SKIPPED
        val spec = entry.pkg
        if (!entry.isLink && (spec == null || spec.parts.isEmpty())) {
            throw GameDataException(GameDataErrorKind.INDEX_INVALID, "entrada sem url e sem partes")
        }

        val marker = markers[gameId]
        if (marker != null && marker.versionKey == versionKey(entry)) return GameDataResult.SKIPPED

        val vpkg = AppGraph.virtual.packageNameFor(gameId) ?: entry.packageName
        val paths = AppGraph.virtual.gamePaths(vpkg)
            ?: throw GameDataException(GameDataErrorKind.INSTALL_FAILED, "paths do ambiente virtual indisponíveis")

        val manifest = entry.manifest
        val totalExtract = (manifest?.totalSizeExtracted ?: 0L).takeIf { it > 0 }
            ?: spec?.plainSizeBytes?.takeIf { it > 0 }
            ?: 0L // link sem tamanho no índice: progresso indeterminado na extração
        if (!entry.isLink && totalExtract <= 0L) {
            throw GameDataException(GameDataErrorKind.INDEX_INVALID, "entrada sem tamanho total")
        }

        var lastPublish = 0L
        val publish: (VirtualInstallPhase, Long, Long, Boolean) -> Unit = { phase, bytes, total, force ->
            val now = System.currentTimeMillis()
            if (force || now - lastPublish >= 300) {
                lastPublish = now
                onProgress(VirtualInstallProgress(gameId, phase, bytes, total))
            }
        }

        // Espaço: extração + margem (no fluxo por partes o pico de uso extra
        // é ~uma parte; no fluxo por link o pacote baixado também ocupa espaço
        // e a checagem é refeita depois do download).
        if (totalExtract > 0) {
            val free = freeSpaceBestEffort()
            if (free != Long.MAX_VALUE && free < totalExtract + SPACE_MARGIN) {
                throw GameDataException(
                    GameDataErrorKind.INSUFFICIENT_SPACE,
                    "espaço insuficiente para os dados do jogo",
                )
            }
        }

        return if (entry.isLink) {
            installFromLink(gameId, entry, vpkg, paths, totalExtract, publish)
        } else {
            installFromParts(gameId, entry, spec!!, vpkg, paths, totalExtract, publish)
        }
    }

    /**
     * Fluxo v1 (legado): partes criptografadas no repositório de dados
     * (AES-256-CTR, chave derivada da senha — ver [GameDataCrypto]).
     */
    private suspend fun installFromParts(
        gameId: String,
        entry: RepoGame,
        spec: RepoPackageSpec,
        vpkg: String,
        paths: VirtualGamePaths,
        totalExtract: Long,
        publish: (VirtualInstallPhase, Long, Long, Boolean) -> Unit,
    ): GameDataResult {
        val virtual = AppGraph.virtual
        val password = AppGraph.settings.settings.value.dataRepoPassword
        if (password.isBlank()) {
            throw GameDataException(GameDataErrorKind.PASSWORD_WRONG, "repositório ativado sem senha")
        }
        if (spec.kdf != "PBKDF2WithHmacSHA256") {
            throw GameDataException(GameDataErrorKind.INDEX_INVALID, "KDF desconhecido: ${spec.kdf}")
        }
        val key = GameDataCrypto.deriveKey(
            password,
            GameDataCrypto.hexToBytes(spec.salt),
            spec.iterations,
        )
        val iv = GameDataCrypto.hexToBytes(spec.iv)

        // ---- Fase 1: DOWNLOADING_DATA (com retomada por parte) ----
        val partsDir = File(context.cacheDir, "gamedata/$gameId").apply { mkdirs() }
        val totalPartsBytes = spec.parts.sumOf { it.sizeBytes }
        var doneBytes = partsDir.listFiles().orEmpty()
            .filter { isVerifiedPart(it, spec) }
            .sumOf { it.length() }
        spec.parts.forEachIndexed { index, part ->
            val target = File(partsDir, part.name.substringAfterLast('/'))
            if (!isVerifiedPart(target, spec)) {
                var lastError: Exception? = null
                var attempt = 0
                var ok = false
                while (attempt < DOWNLOAD_ATTEMPTS && !ok) {
                    attempt++
                    try {
                        downloadPart(part, target) { read ->
                            publish(
                                VirtualInstallPhase.DOWNLOADING_DATA,
                                doneBytes + read,
                                totalPartsBytes,
                                false,
                            )
                        }
                        if (!isVerifiedPart(target, spec)) {
                            target.delete()
                            throw GameDataException(
                                GameDataErrorKind.PART_CORRUPT,
                                "SHA-256 não confere: ${part.name}",
                            )
                        }
                        ok = true
                    } catch (e: GameDataException) {
                        target.delete()
                        lastError = e
                        if (attempt < DOWNLOAD_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
                    } catch (e: Exception) {
                        target.delete()
                        lastError = GameDataException(GameDataErrorKind.IO_FAILURE, "falha ao baixar parte: $e")
                        if (attempt < DOWNLOAD_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
                    }
                }
                if (!ok) throw (lastError ?: GameDataException(GameDataErrorKind.IO_FAILURE, "falha ao baixar parte"))
            }
            doneBytes += part.sizeBytes
            publish(VirtualInstallPhase.DOWNLOADING_DATA, doneBytes, totalPartsBytes, true)
        }

        // ---- Fase 2: INSTALLING_DATA (decripta + extrai em streaming) ----
        publish(VirtualInstallPhase.INSTALLING_DATA, 0L, totalExtract, true)
        val apkPending = pendingApkFor(vpkg, paths)
        var registryBytes: ByteArray? = null
        var extracted = 0L
        val partStreams = spec.parts.map { part ->
            DeletingFileInputStream(File(partsDir, part.name.substringAfterLast('/')))
        }
        val concat: InputStream = SequenceInputStream(Collections.enumeration<InputStream>(partStreams))
        try {
            GameDataCrypto
                .decryptingStream(concat, key, iv, spec.headerBytes)
                .let { java.util.zip.GZIPInputStream(it, com.deivid22srk.portstore.backup.COPY_BUFFER_BYTES) }
                .let { TarArchiveInputStream(it, "UTF-8") }
                .use { tar ->
                    extractPackage(tar, entry, paths, apkPending) {
                        publish(VirtualInstallPhase.INSTALLING_DATA, it, totalExtract, false)
                    }.also { (ex, reg) ->
                        extracted = ex
                        registryBytes = reg
                    }
                }
        } catch (e: GameDataException) {
            throw e
        } catch (e: Exception) {
            // CRC32 do GZIP ou TAR inválido — senha trocada no repositório ou
            // pacote corrompido. Partes permanecem p/ diagnóstico; a próxima
            // tentativa revalida por SHA-256.
            throw GameDataException(
                GameDataErrorKind.PACKAGE_CORRUPT,
                "pacote de dados corrompido (senha do repositório mudou?): $e",
            )
        }
        publish(VirtualInstallPhase.INSTALLING_DATA, extracted, totalExtract, true)

        // ---- Finalização: APK pendente (fluxo manual sem instalação prévia) ----
        finalizeInstall(gameId, entry, vpkg, apkPending, registryBytes)

        // ---- Sucesso: marca, limpa as partes e atualiza o launcher ----
        markers[gameId] = InstalledDataMarker(
            versionKey = versionKey(entry),
            installedAt = System.currentTimeMillis(),
        )
        saveMarkers()
        partsDir.deleteRecursively()
        virtual.refresh()
        return GameDataResult.INSTALLED
    }

    // =====================================================================
    // Fluxo v2: pacote por LINK (ex.: página do Buzzheavier)
    // =====================================================================

    /**
     * Fluxo v2: a entrada do índice traz a PÁGINA do provedor (ex.
     * `https://buzzheavier.com/<id>`).
     *
     * 1. resolve o link direto no motor nativo (Rust — `buzzheavier.rs`:
     *    página → token → `Hx-Redirect` → `/d/…`, com espelhos e fallback
     *    `alt=true`);
     * 2. baixa o pacote em cache com retomada por `Range` (o arquivo
     *    parcial `.part` sobrevive a falhas e é continuado na próxima
     *    tentativa);
     * 3. extrai EM STREAMING direto no ambiente virtual (mesmo mapeamento
     *    do importar — o formato é o backup v3 do app: `manifest.json` +
     *    `install/apk` + `install/extdata`).
     *
     * O pacote comprimido é apagado após a extração; nunca existe pacote
     * descomprimido em disco.
     */
    private fun installFromLink(
        gameId: String,
        entry: RepoGame,
        vpkg: String,
        paths: VirtualGamePaths,
        totalExtract: Long,
        publish: (VirtualInstallPhase, Long, Long, Boolean) -> Unit,
    ): GameDataResult {
        val virtual = AppGraph.virtual
        val pageUrl = entry.url.orEmpty()

        // ---- Resolução do link direto (motor nativo) ----
        publish(VirtualInstallPhase.DOWNLOADING_DATA, 0L, 0L, true)
        val direct = resolveDirectLink(pageUrl)

        val partsDir = File(context.cacheDir, "gamedata/$gameId").apply { mkdirs() }
        val pkgFile = File(partsDir, "package.bin")
        var success = false
        try {
            // ---- Fase 1: DOWNLOADING_DATA (retomada por Range) ----
            downloadPackage(direct, pkgFile) { read, total ->
                publish(VirtualInstallPhase.DOWNLOADING_DATA, read, total, false)
            }

            // Espaço pós-download: o pacote já está em disco; a extração
            // precisa do total + margem ainda livres.
            if (totalExtract > 0) {
                val free = freeSpaceBestEffort()
                if (free != Long.MAX_VALUE && free < totalExtract + SPACE_MARGIN) {
                    throw GameDataException(
                        GameDataErrorKind.INSUFFICIENT_SPACE,
                        "espaço insuficiente para extrair os dados do jogo",
                    )
                }
            }

            // ---- Fase 2: INSTALLING_DATA (extração em streaming) ----
            publish(VirtualInstallPhase.INSTALLING_DATA, 0L, totalExtract, true)
            val apkPending = pendingApkFor(vpkg, paths)
            val raw = PushbackInputStream(FileInputStream(pkgFile), 2)
            val head = ByteArray(2)
            val n = raw.read(head)
            val isGzip = n == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            if (n > 0) raw.unread(head, 0, n)
            val source: InputStream = if (isGzip) {
                java.util.zip.GZIPInputStream(raw, com.deivid22srk.portstore.backup.COPY_BUFFER_BYTES)
            } else {
                raw // tar puro (provedor sem compressão)
            }
            var extracted = 0L
            var registryBytes: ByteArray? = null
            try {
                TarArchiveInputStream(source, "UTF-8").use { tar ->
                    extractPackage(tar, entry, paths, apkPending) {
                        publish(VirtualInstallPhase.INSTALLING_DATA, it, totalExtract, false)
                    }.also { (ex, reg) ->
                        extracted = ex
                        registryBytes = reg
                    }
                }
            } catch (e: GameDataException) {
                throw e
            } catch (e: Exception) {
                // CRC32 do GZIP ou checksum do TAR inválido — pacote corrompido
                // no provedor; descarta o download (retomada não recuperaria).
                pkgFile.delete()
                throw GameDataException(
                    GameDataErrorKind.PACKAGE_CORRUPT,
                    "pacote de dados corrompido: $e",
                )
            }
            publish(VirtualInstallPhase.INSTALLING_DATA, extracted, totalExtract, true)

            // ---- Finalização: APK pendente (fluxo manual sem instalação prévia) ----
            finalizeInstall(gameId, entry, vpkg, apkPending, registryBytes)

            // ---- Sucesso: marca e atualiza o launcher ----
            markers[gameId] = InstalledDataMarker(
                versionKey = versionKey(entry),
                installedAt = System.currentTimeMillis(),
            )
            saveMarkers()
            virtual.refresh()
            success = true
            return GameDataResult.INSTALLED
        } finally {
            // Em sucesso apaga o pacote; em falha mantém o `.part` para a
            // próxima tentativa continuar de onde parou (Range).
            if (success) partsDir.deleteRecursively()
        }
    }

    // =====================================================================
    // Extração + finalização (compartilhados entre os fluxos v1 e v2)
    // =====================================================================

    /** APK pendente quando o ambiente virtual ainda não tem o jogo instalado. */
    private fun pendingApkFor(vpkg: String, paths: VirtualGamePaths): File? =
        if (AppGraph.virtual.isInstalled(vpkg)) {
            null // APK já instalado pelo pipeline de instalação virtual
        } else {
            val cache = paths.engineCacheDir ?: File(context.cacheDir, "gamedata/apk")
            File(cache.apply { mkdirs() }, "gamedata_${vpkg}_${System.currentTimeMillis()}.apk")
        }

    /**
     * Varre o TAR e grava cada entrada no ambiente virtual (mesmo
     * mapeamento do importar — [PackageDestinations]). Retorna
     * (bytes extraídos, registry.json opcional). Bloqueante — sem rede.
     */
    private fun extractPackage(
        tar: TarArchiveInputStream,
        entry: RepoGame,
        paths: VirtualGamePaths,
        apkPending: File?,
        onExtracted: (Long) -> Unit,
    ): Pair<Long, ByteArray?> {
        var extracted = 0L
        var registryBytes: ByteArray? = null
        while (true) {
            val tarEntry = tar.nextTarEntry ?: break
            if (tarEntry.isDirectory) continue
            if (!tar.canReadEntryData(tarEntry)) continue
            val name = tarEntry.name
            when {
                name == MANIFEST_ENTRY_NAME -> {
                    // Valida o manifesto embutido (defesa em profundidade).
                    val text = String(readFully(tar, tarEntry.size), Charsets.UTF_8)
                    val embedded = runCatching {
                        com.deivid22srk.portstore.backup.BackupManifest.decode(text)
                    }.getOrNull()
                    if (embedded != null && embedded.gameId.isNotBlank() &&
                        embedded.gameId != entry.gameId
                    ) {
                        throw GameDataException(
                            GameDataErrorKind.PACKAGE_CORRUPT,
                            "manifest do pacote não corresponde ao jogo",
                        )
                    }
                    extracted += tarEntry.size
                }

                name == GameSources.TAR_REGISTRY -> {
                    registryBytes = readFully(tar, tarEntry.size)
                    extracted += tarEntry.size
                }

                name == GameSources.TAR_APK && apkPending == null -> {
                    drainEntry(tar, tarEntry.size) // APK já presente: só consome
                    extracted += tarEntry.size
                }

                else -> {
                    val destination = PackageDestinations.resolve(paths, name, apkPending)
                    if (destination == null) {
                        drainEntry(tar, tarEntry.size) // entrada desconhecida: só consome
                        extracted += tarEntry.size
                    } else {
                        extracted += PackageReader.copyEntry(tar, tarEntry.size, destination)
                    }
                }
            }
            onExtracted(extracted)
        }
        return extracted to registryBytes
    }

    /** Instala o APK pendente (fluxo manual) ou registra o link virtual. */
    private fun finalizeInstall(
        gameId: String,
        entry: RepoGame,
        vpkg: String,
        apkPending: File?,
        registryBytes: ByteArray?,
    ) {
        val virtual = AppGraph.virtual
        if (apkPending != null && apkPending.exists()) {
            val status = virtual.installFromApk(gameId, apkPending)
            if (status is VirtualInstallStatus.Failure) {
                runCatching { apkPending.delete() }
                throw GameDataException(
                    GameDataErrorKind.INSTALL_FAILED,
                    "o ambiente virtual recusou o APK: ${status.reason} ${status.detail.orEmpty()}",
                )
            }
            runCatching { apkPending.delete() }
        } else if (entry.manifest?.includesCategory(BackupCategory.LAUNCHER) == true && registryBytes != null) {
            runCatching {
                val json = org.json.JSONObject(registryBytes.toString(Charsets.UTF_8))
                virtual.registerVirtualLink(
                    gameId = entry.gameId,
                    packageName = json.optString("packageName", vpkg),
                    apkSizeBytes = json.optLong("apkSizeBytes", 0L),
                )
            }
        }
    }

    // =====================================================================
    // Link direto (motor nativo) + download do pacote
    // =====================================================================

    /**
     * Resolve a PÁGINA do provedor para o link direto via motor nativo
     * (Rust — `buzzheavier.rs`). Lança [GameDataException] com a mensagem
     * amigável do resolvedor (inclui verificação Cloudflare).
     */
    private fun resolveDirectLink(pageUrl: String): String {
        val json = NativeDownloader.resolveBuzzheavier(pageUrl)
        if (json.isBlank()) {
            throw GameDataException(
                GameDataErrorKind.LINK_RESOLVE_FAILED,
                "resolvedor de links indisponível (motor nativo inativo)",
            )
        }
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: throw GameDataException(
                GameDataErrorKind.LINK_RESOLVE_FAILED,
                "resposta inválida do resolvedor de links",
            )
        if (obj.optBoolean("ok", false)) {
            val direct = obj.optString("url")
            if (direct.isNotBlank()) return direct
        }
        throw GameDataException(
            GameDataErrorKind.LINK_RESOLVE_FAILED,
            obj.optString("error").ifBlank { "não foi possível resolver o link do provedor" },
        )
    }

    /**
     * Baixa o pacote do link direto para `target` gravando em
     * `<target>.part` com retomada por `Range`: o arquivo parcial sobrevive
     * a falhas e a próxima tentativa continua de onde parou (servidor sem
     * suporte a Range → recomeça do zero). Atômica: só renomeia quando o
     * tamanho confere.
     */
    private fun downloadPackage(directUrl: String, target: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null
        repeat(DOWNLOAD_ATTEMPTS) {
            try {
                val start = if (tmp.exists()) tmp.length() else 0L
                val request = Request.Builder()
                    .url(directUrl)
                    .header("User-Agent", BROWSER_UA)
                    .apply { if (start > 0) header("Range", "bytes=$start-") }
                    .build()
                val response = http.newCall(request).execute()
                response.use { resp ->
                    val append: Boolean
                    var total = -1L
                    when (resp.code) {
                        416 -> {
                            // Range além do fim: o arquivo local pode já estar completo
                            val rangeTotal = contentRangeTotal(resp.header("Content-Range"))
                            if (rangeTotal > 0 && start >= rangeTotal) {
                                onProgress(rangeTotal, rangeTotal)
                                if (target.exists()) target.delete()
                                if (tmp.renameTo(target)) return
                                throw GameDataException(
                                    GameDataErrorKind.IO_FAILURE,
                                    "renomear pacote falhou: ${target.name}",
                                )
                            }
                            tmp.delete() // estado local inconsistente — recomeça
                            throw GameDataException(
                                GameDataErrorKind.IO_FAILURE,
                                "retomada inválida (HTTP 416)",
                            )
                        }
                        206 -> {
                            append = start > 0
                            total = contentRangeTotal(resp.header("Content-Range"))
                        }
                        in 200..299 -> {
                            append = false
                            total = resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                        }
                        else -> throw GameDataException(
                            GameDataErrorKind.REPO_UNREACHABLE,
                            "HTTP ${resp.code} ao baixar o pacote",
                        )
                    }
                    val body = resp.body
                        ?: throw GameDataException(GameDataErrorKind.REPO_UNREACHABLE, "resposta vazia do provedor")
                    var read = start
                    java.io.FileOutputStream(tmp, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                read += n
                                onProgress(read, if (total > 0) total else 0L)
                            }
                        }
                    }
                    if (total > 0 && tmp.length() < total) {
                        throw GameDataException(
                            GameDataErrorKind.IO_FAILURE,
                            "download incompleto (${tmp.length()} de $total)",
                        )
                    }
                    if (tmp.length() == 0L) {
                        throw GameDataException(GameDataErrorKind.IO_FAILURE, "pacote vazio")
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        throw GameDataException(
                            GameDataErrorKind.IO_FAILURE,
                            "renomear pacote falhou: ${target.name}",
                        )
                    }
                    return
                }
            } catch (e: GameDataException) {
                lastError = e
            } catch (e: Exception) {
                lastError = GameDataException(GameDataErrorKind.IO_FAILURE, "falha ao baixar o pacote: $e")
            }
        }
        throw (lastError ?: GameDataException(GameDataErrorKind.IO_FAILURE, "falha ao baixar o pacote"))
    }

    /** Total de um header `Content-Range` ("bytes 100-999/1234"); -1 se ausente. */
    private fun contentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val slash = header.lastIndexOf('/')
        if (slash < 0) return -1L
        val total = header.substring(slash + 1).trim()
        if (total.isEmpty() || total == "*") return -1L
        return total.toLongOrNull() ?: -1L
    }

    // =====================================================================
    // Cache de índice + marcadores
    // =====================================================================

    private fun versionKey(entry: RepoGame): String =
        if (entry.isLink) {
            "link:${entry.appVersion.orEmpty()}:${entry.url.orEmpty().hashCode()}"
        } else {
            "${entry.appVersion.orEmpty()}:${entry.pkg?.plainSha256.orEmpty().take(16)}"
        }

    /** Entrada em cache (sem rede). Populada por [warmUp]/[activate]/pipeline. */
    private fun cachedEntry(gameId: String, packageNames: List<String>): RepoGame? =
        indexCache?.second?.entryFor(gameId, packageNames)

    /** Aquece o cache do índice no boot (só com o repositório ativado). */
    fun warmUp() {
        if (!AppGraph.settings.settings.value.dataRepoEnabled) return
        scope.launch {
            runCatching { getIndex(force = false) }
        }
    }

    private suspend fun getIndex(force: Boolean): RepoIndex {
        val cached = indexCache
        if (!force && cached != null && System.currentTimeMillis() - cached.first < INDEX_TTL_MS) {
            return cached.second
        }
        val fresh = fetcher.fetch(DEFAULT_DATA_REPO_URL)
        indexCache = System.currentTimeMillis() to fresh
        return fresh
    }

    @Serializable
    private data class InstalledDataMarker(val versionKey: String, val installedAt: Long)

    private fun loadMarkers() {
        val file = File(context.filesDir, "gamedata/installed.json")
        if (!file.exists()) return
        runCatching {
            val raw = file.readText()
            val decoded = markerJson.decodeFromString(
                MapSerializer(String.serializer(), InstalledDataMarker.serializer()),
                raw,
            )
            markers.putAll(decoded)
        }
    }

    private fun saveMarkers() {
        runCatching {
            val file = File(context.filesDir, "gamedata/installed.json")
            file.parentFile?.mkdirs()
            val raw = markerJson.encodeToString(
                MapSerializer(String.serializer(), InstalledDataMarker.serializer()),
                markers.toMap(),
            )
            file.writeText(raw)
        }
    }

    // =====================================================================
    // Download de parte + helpers
    // =====================================================================

    private fun isVerifiedPart(file: File, spec: RepoPackageSpec): Boolean {
        if (!file.exists() || !file.isFile) return false
        val part = spec.parts.firstOrNull { it.name.substringAfterLast('/') == file.name }
            ?: return false
        if (file.length() != part.sizeBytes) return false
        val sha = sha256(file) ?: return false
        return sha.equals(part.sha256, ignoreCase = true)
    }

    /** Baixa uma parte gravando em `target.tmp` + hash simultâneo (atômica). */
    private fun downloadPart(part: RepoPart, target: File, onBytes: (Long) -> Unit) {
        val url = "${DEFAULT_DATA_REPO_URL.trimEnd('/')}/${part.name}"
        val request = Request.Builder().url(url).build()
        val response = http.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw GameDataException(
                    GameDataErrorKind.REPO_UNREACHABLE,
                    "HTTP ${resp.code} ao baixar ${part.name}",
                )
            }
            val body = resp.body
                ?: throw GameDataException(GameDataErrorKind.REPO_UNREACHABLE, "resposta vazia: ${part.name}")
            val tmp = File(target.parentFile, target.name + ".tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onBytes(read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(part.sha256, ignoreCase = true)) {
                tmp.delete()
                throw GameDataException(
                    GameDataErrorKind.PART_CORRUPT,
                    "SHA-256 não confere: ${part.name}",
                )
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw GameDataException(GameDataErrorKind.IO_FAILURE, "renomear parte falhou: ${target.name}")
            }
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Consome `size` bytes do TAR sem extrair (entradas puladas). */
    private fun drainEntry(tar: TarArchiveInputStream, size: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = size
        while (remaining > 0) {
            val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val read = tar.read(buffer, 0, want)
            if (read < 0) break
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(if (size in 1..(8 * 1024 * 1024)) size.toInt() else 8192)
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = size
        while (remaining > 0) {
            val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /**
     * Parte do pacote apagada assim que o leitor a consome (close é chamado
     * pelo SequenceInputStream ao passar para a próxima) — o espaço extra
     * durante a extração nunca chega ao tamanho do pacote inteiro.
     */
    private class DeletingFileInputStream(private val file: File) : FileInputStream(file) {
        override fun close() {
            try {
                super.close()
            } finally {
                file.delete()
            }
        }
    }

    /** Menor entre o sandbox interno e o armazenamento externo do app. */
    private fun freeSpaceBestEffort(): Long = runCatching {
        var free = Long.MAX_VALUE
        runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()?.let { free = it }
        context.getExternalFilesDir(null)?.let { ext ->
            runCatching { StatFs(ext.path).availableBytes }.getOrNull()?.let { extFree ->
                free = minOf(free, extFree)
            }
        }
        free
    }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val COPY_BUFFER = 256 * 1024
        const val DOWNLOAD_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
        const val INDEX_TTL_MS = 5 * 60_000L
        const val SPACE_MARGIN = 200L * 1024 * 1024

        /** Mesmo UA do resolvedor Rust (consistência com o provedor). */
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Mapeia a exceção do repositório para o motivo de falha da UI (pt-BR). */
fun GameDataException.toVirtualFailure(): VirtualFailure = when (kind) {
    GameDataErrorKind.PACKAGE_CORRUPT -> VirtualFailure.DATA_CORRUPT
    GameDataErrorKind.INSTALL_FAILED -> VirtualFailure.DATA_INSTALL_FAILED
    else -> VirtualFailure.DATA_DOWNLOAD_FAILED
}
