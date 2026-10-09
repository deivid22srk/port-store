package com.deivid22srk.portstore.update

import android.content.Context
import com.deivid22srk.portstore.BuildConfig
import com.deivid22srk.portstore.installer.ApkInstaller
import com.deivid22srk.portstore.settings.SettingsRepository
import com.deivid22srk.portstore.util.VersionCompare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Informações da última release oficial do próprio app. */
data class AppUpdateInfo(
    val tag: String, // "v1.2.0"
    val version: String, // "1.2.0"
    val notes: String?,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
)

/** Estados do fluxo de atualização do próprio app. */
sealed interface SelfUpdateState {
    data object Idle : SelfUpdateState
    data object Checking : SelfUpdateState
    data object UpToDate : SelfUpdateState
    data class Available(val update: AppUpdateInfo) : SelfUpdateState
    data class Downloading(val update: AppUpdateInfo, val progress: Int) : SelfUpdateState
    data class ReadyToInstall(val update: AppUpdateInfo, val apkPath: String) : SelfUpdateState
    data class Error(val message: String) : SelfUpdateState
}

@Serializable
private data class SURelease(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<SUAsset> = emptyList(),
)

@Serializable
private data class SUAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
)

/**
 * Atualização do próprio Port Store:
 * consulta `releases/latest` deste repositório, compara a versão instalada
 * (versionName) com a tag (sem o "v", numericamente), baixa o APK da release
 * com progresso e abre o instalador do Android via FileProvider.
 *
 * A verificação automática é feita no máximo uma vez a cada
 * [AUTO_CHECK_INTERVAL_MS] (a checagem manual em "Você" ignora o intervalo).
 */
class SelfUpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs =
        context.getSharedPreferences("self_update", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<SelfUpdateState>(SelfUpdateState.Idle)
    val state: StateFlow<SelfUpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /** Versão instalada (definida pela tag no CI ou pelo padrão do gradle). */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Verificação automática (abertura do app): respeita o intervalo mínimo
     * entre consultas para poupar a API do GitHub.
     */
    fun maybeAutoCheck() {
        val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L)
        if (elapsed < AUTO_CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    /**
     * Verifica atualizações. Chamadas manuais ("Verificar atualizações")
     * ignoram o intervalo; se já houver download em curso, não interrompe.
     */
    fun check(manual: Boolean = true) {
        when (_state.value) {
            is SelfUpdateState.Checking, is SelfUpdateState.Downloading -> return
            is SelfUpdateState.ReadyToInstall -> {
                // Reabre o instalador do APK já baixado.
                if (manual) installReady()
                return
            }
            else -> Unit
        }
        scope.launch {
            _state.value = SelfUpdateState.Checking
            try {
                val update = fetchLatestRelease()
                if (update == null) {
                    _state.value = SelfUpdateState.Error(
                        "Nenhuma versão publicada ainda. Volte mais tarde.",
                    )
                    return@launch
                }
                markChecked()
                if (!VersionCompare.isUpdateAvailable(update.version, currentVersion())) {
                    _state.value = SelfUpdateState.UpToDate
                    // Mensagem "em dia" some sozinha após alguns segundos.
                    delay(UP_TO_DATE_HOLD_MS)
                    if (_state.value is SelfUpdateState.UpToDate) {
                        _state.value = SelfUpdateState.Idle
                    }
                } else {
                    _state.value = SelfUpdateState.Available(update)
                }
            } catch (e: SelfUpdateException) {
                if (!manual) resetOnError()
                _state.value = SelfUpdateState.Error(e.userMessage)
            } catch (e: Exception) {
                if (!manual) resetOnError()
                _state.value =
                    SelfUpdateState.Error("Falha ao verificar: ${e.message ?: "erro desconhecido"}")
            }
        }
    }

    /** Usuário escolheu "Atualizar": baixa o APK com progresso. */
    fun startDownload(update: AppUpdateInfo) {
        if (_state.value is SelfUpdateState.Downloading) return
        downloadJob = scope.launch {
            _state.value = SelfUpdateState.Downloading(update, 0)
            try {
                val apk = downloadApk(update)
                _state.value = SelfUpdateState.ReadyToInstall(update, apk.absolutePath)
                installReady()
            } catch (e: SelfUpdateException) {
                _state.value = SelfUpdateState.Error(e.userMessage)
            } catch (e: Exception) {
                _state.value = SelfUpdateState.Error(
                    "Falha ao baixar: ${e.message ?: "erro desconhecido"}",
                )
            }
        }
    }

    /** Cancela o download em curso e volta para o estado de disponível. */
    fun cancelDownload() {
        val current = _state.value
        if (current is SelfUpdateState.Downloading) {
            downloadJob?.cancel()
            downloadJob = null
            cleanupDownloads()
            _state.value = SelfUpdateState.Available(current.update)
        }
    }

    /** Fecha o diálogo ("Depois") sem perder a oferta. */
    fun dismissOffer() {
        if (_state.value is SelfUpdateState.Available) {
            _state.value = SelfUpdateState.Idle
        }
    }

    /** Tenta abrir o instalador do Android com o APK já baixado. */
    fun installReady(): Boolean {
        val current = _state.value as? SelfUpdateState.ReadyToInstall ?: return false
        val ok = ApkInstaller.installApk(context, current.apkPath)
        if (ok) {
            _state.value = SelfUpdateState.Idle
        }
        // false = faltou a permissão "Instalar apps desconhecidos" (a tela
        // de configuração foi aberta) — mantém ReadyToInstall p/ tentar de novo.
        return ok
    }

    /** Fecha um erro mostrado na linha de "Verificar atualizações". */
    fun clearError() {
        if (_state.value is SelfUpdateState.Error) _state.value = SelfUpdateState.Idle
    }

    fun shutdown() {
        scope.cancel()
    }

    // --- internals -----------------------------------------------------------

    private class SelfUpdateException(val userMessage: String) : IOException(userMessage)

    private fun markChecked() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun resetOnError() {
        // Verificação automática falhou: volta a Idle silenciosamente
        // (o diálogo só aparece com resultado positivo).
        _state.value = SelfUpdateState.Idle
    }

    private suspend fun fetchLatestRelease(): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$REPO/releases/latest"
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PortStore/${BuildConfig.VERSION_NAME} (Android)")
            val token = settings.settings.value.githubToken
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")

            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 403 || resp.code == 429 -> throw SelfUpdateException(
                        "Limite de consultas do GitHub atingido. Tente novamente mais tarde.",
                    )
                    resp.code == 404 -> return@withContext null
                    !resp.isSuccessful -> throw SelfUpdateException(
                        "GitHub respondeu HTTP ${resp.code}. Tente novamente.",
                    )
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val release = json.decodeFromString<SURelease>(body)
                if (release.draft) return@withContext null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                    ?: throw SelfUpdateException(
                        "A versão publicada não contém o arquivo APK.",
                    )
                AppUpdateInfo(
                    tag = release.tag_name ?: "",
                    version = VersionCompare.normalize(release.tag_name) ?: "",
                    notes = release.body?.takeIf { it.isNotBlank() },
                    apkUrl = apk.browser_download_url,
                    apkName = apk.name,
                    apkSize = apk.size,
                )
            }
        }

    private suspend fun downloadApk(update: AppUpdateInfo): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "Download/selfupdate").apply { mkdirs() }
            cleanupDownloads(dir)
            val file = File(dir, update.apkName.ifBlank { "PortStore-atualizacao.apk" })
            val request = Request.Builder()
                .url(update.apkUrl)
                .header("User-Agent", "PortStore/${BuildConfig.VERSION_NAME} (Android)")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw SelfUpdateException("GitHub respondeu HTTP ${resp.code} no download.")
                }
                val source = resp.body ?: throw SelfUpdateException("Download vazio.")
                val total = source.contentLength()
                var written = 0L
                var lastProgress = -1
                source.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                if (pct != lastProgress) {
                                    lastProgress = pct
                                    _state.value =
                                        SelfUpdateState.Downloading(update, pct)
                                }
                            }
                        }
                    }
                }
                file
            }
        }

    private fun cleanupDownloads() {
        cleanupDownloads(File(context.filesDir, "Download/selfupdate"))
    }

    private fun cleanupDownloads(dir: File) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        /** Repositório oficial do app (fonte das releases). */
        const val REPO = "deivid22srk/port-store"

        /** Intervalo mínimo entre verificações automáticas: 6 horas. */
        const val AUTO_CHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val UP_TO_DATE_HOLD_MS = 5_000L
    }
}
