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
     * Resolve o link direto de uma PÁGINA do Buzzheavier no motor Rust
     * (`buzzheavier.rs`): página → token → `Hx-Redirect` → URL `/d/…`.
     *
     * @param cookies cookies do WebView (plano B do Cloudflare — `cf_clearance`);
     *   vazio = sem cookies (caminho rápido só com emulação Chrome).
     * @param userAgent UA EXATO do WebView que passou a verificação —
     *   `cf_clearance` é ligado a IP+UA, então tem de bater; vazio = UA da
     *   emulação.
     *
     * Retorna JSON (nunca null): `{"ok":true,"url":"…"}` ou
     * `{"ok":false,"kind":"network|challenge|notfound","error":"…"}`.
     * String vazia quando o motor nativo não está disponível.
     *
     * Bloqueante (rede) — chamar de Dispatchers.IO.
     */
    fun resolveBuzzheavier(
        pageUrl: String,
        cookies: String = "",
        userAgent: String = "",
    ): String =
        if (available) nativeResolveBuzzheavier(pageUrl, cookies, userAgent) else ""

    /**
     * Baixa o ARQUIVO do provedor (link direto `/d/…`) no motor Rust com o
     * MESMO fingerprint de navegador da resolução (`provider_download.rs` —
     * a rota `/d/` fica atrás do mesmo Cloudflare).
     *
     * BLOQUEANTE até concluir/falhar/cancelar — chamar de Dispatchers.IO;
     * o progresso acompanha via [providerDownloadProgress] e o cancelamento
     * via [cancelProviderDownload].
     *
     * Retorna JSON (nunca null):
     * `"ok":true,"status":200|206,"bytes":…,"total":…,"resumedFrom":…}`
     * `"ok":false,"status":416,"total":…,"resumedFrom":…}` (arquivo local
     * pode já estar completo — o Kotlin decide), `"ok":false,"challenge":true}`
     * (Cloudflare — repetir cedo não resolve) ou `"ok":false,"canceled":true}`.
     */
    fun downloadProviderFile(
        url: String,
        destPath: String,
        appendFrom: Long,
        cookies: String = "",
        userAgent: String = "",
    ): String =
        if (available) nativeDownloadProviderFile(url, destPath, appendFrom, cookies, userAgent) else ""

    /** Snapshot do download do provedor: `{"written":N,"total":M}`. */
    fun providerDownloadProgress(): String =
        if (available) nativeProviderDownloadProgress() else "{\"written\":0,\"total\":-1}"

    /** Cancela o download do provedor em andamento (o .part é mantido). */
    fun cancelProviderDownload() {
        if (available) nativeCancelProviderDownload()
    }

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
    external fun nativeResolveBuzzheavier(pageUrl: String, cookies: String, userAgent: String): String
    external fun nativeDownloadProviderFile(
        url: String,
        destPath: String,
        appendFrom: Long,
        cookies: String,
        userAgent: String,
    )
    external fun nativeProviderDownloadProgress(): String
    external fun nativeCancelProviderDownload()
}
