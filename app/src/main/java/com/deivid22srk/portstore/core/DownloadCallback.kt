package com.deivid22srk.portstore.core

/**
 * Callbacks que o motor Rust invoca via JNI. A implementação vive no
 * DownloadRepository; o Rust guarda um GlobalRef para o objeto.
 */
interface DownloadCallback {
    fun onProgress(id: String, downloaded: Long, total: Long, speedBps: Long, etaSec: Long)
    fun onStateChanged(id: String, state: String, error: String?)
}
