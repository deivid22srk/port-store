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
        // NUNCA deve acontecer no APK do CI (o build empacota as dependências
        // nativas, ex.: libc++_shared.so do wreq/BoringSSL). Se acontecer, o
        // log abaixo é a pista: dlopen falhou por lib ausente/ABI errada.
        android.util.Log.e("portstore", "libportstore_core.so não carregou — motor de download nativo INATIVO", t)
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

    /**
     * Enfileira um download CAPTURADO da WebView no motor Rust
     * (`nativeEnqueueCaptured` → `webview_bridge.rs`): a captura traz a URL
     * final do arquivo + a sessão HTTP completa da página (cookies do
     * `CookieManager`, UA EXATO da WebView, Referer/Origin e headers extra).
     *
     * `captureJson` segue o contrato `CapturedDownload` (camelCase, validado
     * no Rust — cookies/headers/hostis tratados como entrada hostil). O motor
     * baixa com multipartes, retomada, retry, limite de velocidade e
     * verificação de hash; progresso/estados saem pelos callbacks normais
     * (`DownloadCallback`), então o item aparece na tela Downloads se houver
     * linha correspondente no Room (ver [com.deivid22srk.portstore.core.DownloadRepository.enqueueCaptured]).
     *
     * @return false quando o motor não está disponível OU a captura foi
     *   rejeitada pela validação (detalhe só no log — nunca com cookies).
     */
    fun enqueueCaptured(id: String, destPath: String, captureJson: String): Boolean =
        if (available) nativeEnqueueCaptured(id, destPath, captureJson) else false

    // Métodos externos. Nomes não podem mudar: o Rust os resolve por
    // convenção JNI (Java_com_deivid22srk_portstore_core_NativeDownloader_*).
    external fun nativeInit(configJson: String, callback: DownloadCallback)
    external fun nativeEnqueue(id: String, url: String, destPath: String, optionsJson: String): Boolean
    external fun nativeEnqueueCaptured(id: String, destPath: String, captureJson: String): Boolean
    external fun nativePause(id: String)
    external fun nativeResume(id: String): Boolean
    external fun nativeCancel(id: String, deleteFile: Boolean)
    external fun nativeSetConfig(configJson: String)
    external fun nativeSetNetworkState(connected: Boolean, unmetered: Boolean)
    external fun nativeGetSnapshot(): String
}
