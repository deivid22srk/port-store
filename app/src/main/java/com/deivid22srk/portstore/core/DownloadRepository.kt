package com.deivid22srk.portstore.core

import android.content.Context
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.db.AppDatabase
import com.deivid22srk.portstore.db.DownloadEntity
import com.deivid22srk.portstore.service.DownloadService
import com.deivid22srk.portstore.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Progresso em tempo real vindo do motor Rust (não persistido a cada tick). */
data class LiveProgress(
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val etaSec: Long,
)

/**
 * Fonte da verdade: Room (histórico persistente) + snapshot/Eventos do motor Rust.
 * - Estados e mudanças de fase => gravados no Room imediatamente (callback JNI).
 * - Progresso/velocidade => mantidos em memória e sincronizados ao Room a cada ~10s.
 */
class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : DownloadCallback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    private val liveLock = Any()

    val items: StateFlow<List<DownloadItem>> =
        combine(db.downloadDao().observeAll(), live) { rows, liveMap ->
            rows.map { row ->
                DownloadItem(
                    id = row.id,
                    gameId = row.gameId,
                    title = row.title,
                    cover = row.cover,
                    url = row.url,
                    destPath = row.destPath,
                    state = row.state,
                    downloaded = liveMap[row.id]?.downloaded ?: row.downloaded,
                    total = liveMap[row.id]?.total ?: row.total,
                    speedBps = liveMap[row.id]?.speedBps ?: 0L,
                    etaSec = liveMap[row.id]?.etaSec ?: 0L,
                    error = row.error,
                    createdAt = row.createdAt,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _nativeReady = MutableStateFlow(false)
    val nativeReady: StateFlow<Boolean> = _nativeReady.asStateFlow()

    fun initialize() {
        if (!NativeDownloader.available) return
        val cfg = currentNativeConfig()
        NativeDownloader.init(json.encodeToString(NativeConfig.serializer(), cfg), this)
        _nativeReady.value = true
        scope.launch { applyConfigFromSettings() }
        scope.launch { restorePending() }
        scope.launch { pollNativeLoop() }
        scope.launch { periodicPersistLoop() }
    }

    // ---------------------------------------------------------------- config

    private fun currentNativeConfig(): NativeConfig = nativeConfigOf(settings.settings.value)

    suspend fun applyConfigFromSettings() {
        NativeDownloader.setConfig(json.encodeToString(NativeConfig.serializer(), nativeConfigOf(settings.settings.value)))
    }

    private fun nativeConfigOf(s: com.deivid22srk.portstore.settings.AppSettings): NativeConfig = NativeConfig(
        segments = s.segments,
        maxConcurrent = s.maxConcurrent,
        speedLimitBps = if (s.speedLimitMbps > 0) s.speedLimitMbps.toLong() * 1_000_000L / 8L else 0L,
        wifiOnly = s.wifiOnly,
    )

    // ------------------------------------------------------------- lifecycle

    /** Reenfileira downloads não terminados após morte do processo / boot. */
    suspend fun restorePending() {
        if (!NativeDownloader.available) return
        withContext(Dispatchers.IO) {
            val rows = db.downloadDao().nonTerminal()
            val snap = snapshotMap()
            for (row in rows) {
                if (row.state == DlState.PAUSED) continue // pausado pelo usuário
                if (!snap.containsKey(row.id)) {
                    enqueueNative(row.id, row.url, row.destPath, optionsFor(row.id))
                }
            }
        }
    }

    private suspend fun optionsFor(id: String): String =
        json.encodeToString(NativeJobOptions.serializer(), NativeJobOptions(isApk = true))

    /** "63 MB" / "1.2 GB" -> bytes (estimativa do catálogo). */
    private fun estimateBytes(size: String?): Long {
        val s = size?.trim()?.lowercase() ?: return 0
        val m = Regex("([0-9]+([.,][0-9]+)?)\\s*(kb|mb|gb)").find(s) ?: return 0
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        val unit = m.groupValues[3]
        return when (unit) {
            "gb" -> (value * 1024 * 1024 * 1024).toLong()
            "mb" -> (value * 1024 * 1024).toLong()
            "kb" -> (value * 1024).toLong()
            else -> 0
        }
    }

    private fun snapshotMap(): Map<String, SnapshotItem> = try {
        val parsed = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SnapshotItem.serializer()),
            NativeDownloader.snapshot(),
        )
        parsed.associateBy { it.id }
    } catch (_: Exception) {
        emptyMap()
    }

    // ---------------------------------------------------------------- API UI

    fun downloadDir(): File {
        val ext = context.getExternalFilesDir(null)
        return File(ext ?: context.filesDir, "Download")
    }

    fun destPathFor(gameId: String): String = File(downloadDir(), "$gameId.apk").absolutePath

    fun startDownload(game: Game, url: String, sha256: String?) {
        scope.launch {
            val dest = destPathFor(game.id)
            withContext(Dispatchers.IO) { File(dest).parentFile?.mkdirs() }

            // Checagem de espaço livre (StatFs) antes de começar.
            val estimate = estimateBytes(game.apkSize)
            if (estimate > 0) {
                val free = runCatching { android.os.StatFs(downloadDir().absolutePath).availableBytes }
                    .getOrDefault(Long.MAX_VALUE)
                if (free < estimate + 128L * 1024 * 1024) {
                    val now = System.currentTimeMillis()
                    db.downloadDao().upsert(
                        DownloadEntity(
                            id = game.id, gameId = game.id, title = game.title,
                            cover = game.coverUrl, url = url, destPath = dest,
                            state = DlState.FAILED,
                            downloaded = 0, total = 0,
                            error = "Espaço insuficiente no armazenamento",
                            createdAt = now, updatedAt = now,
                        ),
                    )
                    return@launch
                }
            }

            val now = System.currentTimeMillis()
            val existing = db.downloadDao().get(game.id)
            val entity = DownloadEntity(
                id = game.id,
                gameId = game.id,
                title = game.title,
                cover = game.coverUrl,
                url = url,
                destPath = dest,
                state = DlState.QUEUED,
                downloaded = 0,
                total = 0,
                error = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            db.downloadDao().upsert(entity)
            synchronized(liveLock) { live.value = live.value - game.id }
            enqueueNative(game.id, url, dest, json.encodeToString(NativeJobOptions.serializer(), NativeJobOptions(expectedSha256 = sha256, isApk = true)))
            DownloadService.ensureStarted(context)
        }
    }

    private fun enqueueNative(id: String, url: String, destPath: String, optionsJson: String) {
        NativeDownloader.enqueue(id, url, destPath, optionsJson)
    }

    fun pause(id: String) {
        NativeDownloader.pause(id)
        scope.launch {
            db.downloadDao().updateState(id, DlState.PAUSED, null, System.currentTimeMillis())
        }
    }

    fun resume(id: String) {
        scope.launch {
            val row = db.downloadDao().get(id) ?: return@launch
            val snap = snapshotMap()
            if (snap.containsKey(id)) {
                NativeDownloader.resume(id)
            } else {
                enqueueNative(id, row.url, row.destPath, optionsFor(id))
            }
            db.downloadDao().updateState(id, DlState.QUEUED, null, System.currentTimeMillis())
            DownloadService.ensureStarted(context)
        }
    }

    fun cancel(id: String) {
        NativeDownloader.cancel(id, true)
        scope.launch {
            db.downloadDao().updateState(id, DlState.CANCELED, null, System.currentTimeMillis())
        }
    }

    fun retry(id: String) = resume(id)

    /** Apaga o item do histórico e o arquivo do disco. */
    fun delete(id: String) {
        NativeDownloader.cancel(id, true)
        scope.launch {
            val row = db.downloadDao().get(id)
            db.downloadDao().delete(id)
            row?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        File(it.destPath).delete()
                        File(it.destPath + ".part").delete()
                        File(it.destPath + ".state").delete()
                    }
                }
            }
        }
    }

    fun clearFinished() {
        scope.launch {
            db.downloadDao().deleteCompleted()
            // mantém os arquivos .apk no disco: o usuário ainda pode instalá-los
        }
    }

    fun setNetworkState(connected: Boolean, unmetered: Boolean) {
        NativeDownloader.setNetworkState(connected, unmetered)
    }

    // ------------------------------------------------------ callbacks do Rust

    override fun onProgress(id: String, downloaded: Long, total: Long, speedBps: Long, etaSec: Long) {
        synchronized(liveLock) {
            live.value = live.value + (id to LiveProgress(downloaded, total, speedBps, etaSec))
        }
    }

    override fun onStateChanged(id: String, state: String, error: String?) {
        val lp = synchronized(liveLock) { live.value[id] }
        scope.launch {
            val row = db.downloadDao().get(id)
            if (row == null) return@launch
            db.downloadDao().updateProgress(
                id = id,
                state = state,
                error = error,
                downloaded = lp?.downloaded ?: row.downloaded,
                total = lp?.total ?: row.total,
                updatedAt = System.currentTimeMillis(),
            )
            if (state in DlState.TERMINAL) {
                synchronized(liveLock) { live.value = live.value - id }
            }
            DownloadService.ensureStarted(context)
        }
    }

    // ------------------------------------------------------------------ loops

    private suspend fun pollNativeLoop() {
        while (scope.isActive) {
            delay(700)
            val hasActive = items.value.any { it.isActive || it.state == DlState.PAUSED }
            if (!hasActive) continue
            val snap = snapshotMap()
            if (snap.isEmpty()) continue
            synchronized(liveLock) {
                val current = live.value
                val updated = current.toMutableMap()
                for ((id, s) in snap) {
                    if (s.state in DlState.ACTIVE) {
                        updated[id] = LiveProgress(s.downloaded, s.total, s.speedBps, s.etaSec)
                    }
                }
                live.value = updated
            }
        }
    }

    private suspend fun periodicPersistLoop() {
        while (scope.isActive) {
            delay(10_000)
            val snapshot = synchronized(liveLock) { live.value.toMap() }
            if (snapshot.isEmpty()) continue
            withContext(Dispatchers.IO) {
                for ((id, lp) in snapshot) {
                    val row = db.downloadDao().get(id) ?: continue
                    if (row.state in DlState.ACTIVE) {
                        db.downloadDao().updateProgress(id, row.state, row.error, lp.downloaded, lp.total, System.currentTimeMillis())
                    }
                }
            }
        }
    }
}
