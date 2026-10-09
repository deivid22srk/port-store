package com.deivid22srk.portstore.core

import kotlinx.serialization.Serializable

/** Estados do motor (string Java <-> enum Rust). */
object DlState {
    const val QUEUED = "Queued"
    const val CONNECTING = "Connecting"
    const val DOWNLOADING = "Downloading"
    const val PAUSED = "Paused"
    const val VERIFYING = "Verifying"
    const val COMPLETED = "Completed"
    const val FAILED = "Failed"
    const val CANCELED = "Canceled"

    val ACTIVE = setOf(QUEUED, CONNECTING, DOWNLOADING, VERIFYING)
    val TERMINAL = setOf(COMPLETED, FAILED, CANCELED)
}

fun String?.isActiveState(): Boolean = this in DlState.ACTIVE

/** Item exposto para a UI (Room + snapshot do motor). */
data class DownloadItem(
    val id: String,
    val gameId: String,
    val title: String,
    val cover: String?,
    val url: String,
    val destPath: String,
    val state: String,
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val etaSec: Long,
    val error: String?,
    val createdAt: Long,
) {
    val isActive: Boolean get() = state in DlState.ACTIVE
    val isPaused: Boolean get() = state == DlState.PAUSED
    val isCompleted: Boolean get() = state == DlState.COMPLETED
    val isFailed: Boolean get() = state == DlState.FAILED

    val progress: Float
        get() = if (total > 0) (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

/** Configuração enviada ao motor Rust (nativeInit / nativeSetConfig). */
@Serializable
data class NativeConfig(
    val segments: Int = 6,
    val maxConcurrent: Int = 3,
    val speedLimitBps: Long = 0,
    val wifiOnly: Boolean = false,
    val userAgent: String = "PortStore/1.0 (Android; +https://github.com/deivid22srk/port-store)",
)

/** Opções por download (nativeEnqueue). */
@Serializable
data class NativeJobOptions(
    val expectedSha256: String? = null,
    val isApk: Boolean = true,
    val estimatedSize: Long = 0,
)

/** Entrada do snapshot JSON (nativeGetSnapshot). */
@Serializable
data class SnapshotItem(
    val id: String = "",
    val state: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = 0,
    val error: String? = null,
)
