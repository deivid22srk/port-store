package com.deivid22srk.portstore.core

/**
 * Ponte JNI com o motor de download em Rust (libportstore_core.so).
 *
 * O objeto Rust mantém os downloads, a fila, o estado persistente (.state)
 * e os callbacks de progresso. Se a biblioteca não puder ser carregada
 * (ABI ausente, por exemplo), [available] fica false e o app continua
 * funcionando — apenas os downloads ficam desabilitados.
 */
object NativeDownloader {

    val available: Boolean = try {
        System.loadLibrary("portstore_core")
        true
    } catch (t: Throwable) {
        false
    }

    fun init(configJson: String, callback: DownloadCallback) {
        if (available) nativeInit(configJson, callback)
    }

    fun enqueue(id: String, url: String, destPath: String, optionsJson: String): Boolean =
        if (available) nativeEnqueue(id, url, destPath, optionsJson) else false

    fun pause(id: String) {
        if (available) nativePause(id)
    }

    fun resume(id: String): Boolean =
        if (available) nativeResume(id) else false

    fun cancel(id: String, deleteFile: Boolean) {
        if (available) nativeCancel(id, deleteFile)
    }

    fun setConfig(configJson: String) {
        if (available) nativeSetConfig(configJson)
    }

    fun setNetworkState(connected: Boolean, unmetered: Boolean) {
        if (available) nativeSetNetworkState(connected, unmetered)
    }

    fun snapshot(): String =
        if (available) nativeGetSnapshot() else "[]"

    // Métodos externos. Nomes não podem mudar: o Rust os resolve por
    // convenção JNI (Java_com_deivid22srk_portstore_core_NativeDownloader_*).
    external fun nativeInit(configJson: String, callback: DownloadCallback)
    external fun nativeEnqueue(id: String, url: String, destPath: String, optionsJson: String): Boolean
    external fun nativePause(id: String)
    external fun nativeResume(id: String): Boolean
    external fun nativeCancel(id: String, deleteFile: Boolean)
    external fun nativeSetConfig(configJson: String)
    external fun nativeSetNetworkState(connected: Boolean, unmetered: Boolean)
    external fun nativeGetSnapshot(): String
}
