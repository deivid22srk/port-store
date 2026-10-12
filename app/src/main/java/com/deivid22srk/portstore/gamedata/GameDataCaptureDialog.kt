package com.deivid22srk.portstore.gamedata

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

/**
 * UA de DESKTOP usado no WebView de captura — DEVE ser exatamente o mesmo UA
 * que o motor Rust usa nas requisições do download: provedores atrás de
 * Cloudflare ligam `cf_clearance` a IP+UA, então a sessão capturada só vale
 * se o motor repetir o MESMO UA (o `CapturedDownload.userAgent` carrega este
 * valor até o motor). Desktop (e não o UA mobile do WebView) para casar com
 * a emulação Chrome desktop do wreq (mesmos headers sec-ch-ua/etc.).
 */
const val CAPTURE_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Teto para capturas inline (`data:`/`blob:`) — pacotes grandes vêm por HTTP. */
private const val INLINE_MAX_BYTES: Int = 64 * 1024 * 1024
private const val BLOB_MAX_BYTES: Long = 128L * 1024 * 1024

/**
 * Diálogo GLOBAL de captura do download dos DADOS complementares: um WebView
 * REAL (mesmo motor do Chrome do aparelho) carrega a PÁGINA do provedor; o
 * usuário navega (passa verificação do Cloudflare, fecha propaganda…) e clica
 * no BOTÃO DE DOWNLOAD do arquivo. O `DownloadListener` da WebView intercepta
 * o download e devolve URL final + cookies da sessão + UA + Referer ao
 * [GameDataInstaller], que enfileira no motor Rust — a página fecha sozinha.
 *
 * Navegação:
 * - MESMO host: navega normal (links internos, paginação…).
 * - HOST DIFERENTE: confirmação explícita mostrando a URL — alguns provedores
 *   forçam redirecionamentos para páginas de anúncio; o usuário decide.
 * - Esquemas não web (`intent:`, `market:`, `javascript:`…): bloqueados.
 * - `data:`/`blob:` (downloads gerados por JS): decodificados localmente
 *   (com teto de tamanho) e entregues como arquivo — sem rede.
 *
 * Observa [GameDataInstaller.capture] globalmente (MainActivity) — o pedido
 * pode surgir da instalação automática, com o usuário em qualquer tela.
 * Cancelar → instalação falha com CANCELED (tentar de novo reabre a página).
 */
@Composable
fun GameDataCaptureDialog() {
    val request by AppGraph.dataRepo.capture.collectAsStateWithLifecycle()
    val current = request ?: return
    // Recrea o WebView para cada pedido (páginas/hosts diferentes).
    key(current) {
        CaptureScreen(current)
    }
}

@Composable
private fun CaptureScreen(request: GameDataInstaller.CaptureRequest) {
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    var pendingRedirect by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var pageHost by remember { mutableStateOf(hostOf(request.pageUrl).orEmpty()) }
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = true) {
        if (pendingRedirect != null) {
            pendingRedirect = null
        } else {
            showExitConfirm = true
        }
    }

    // ---- Confirmação de redirecionamento (mostra a URL de destino) ----
    val redirectTarget = pendingRedirect
    if (redirectTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRedirect = null },
            title = { Text(stringResource(R.string.data_capture_redirect_title)) },
            text = { Text(stringResource(R.string.data_capture_redirect_message, redirectTarget)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRedirect = null
                    loadError = false
                    webViewHolder.value?.loadUrl(redirectTarget)
                }) { Text(stringResource(R.string.data_capture_redirect_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRedirect = null }) {
                    Text(stringResource(R.string.data_capture_redirect_block))
                }
            },
        )
    }

    // ---- Confirmação de saída (botão fechar / B do gamepad) ----
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.data_capture_exit_title)) },
            text = { Text(stringResource(R.string.data_capture_exit_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    AppGraph.dataRepo.resolveCapture(null)
                }) { Text(stringResource(R.string.data_capture_exit_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.data_capture_exit_no))
                }
            },
        )
    }

    // ---- Tela cheia ----
    Dialog(
        onDismissRequest = { showExitConfirm = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Barra superior: título/host + recarregar + fechar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.data_capture_title),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            pageTitle.ifBlank { pageHost },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    TextButton(
                        onClick = {
                            loadError = false
                            webViewHolder.value?.reload()
                        },
                        modifier = Modifier.focusRequester(focusRequester),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.data_capture_refresh))
                    }
                    IconButton(onClick = { showExitConfirm = true }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.data_capture_close),
                        )
                    }
                }
                Text(
                    stringResource(R.string.data_capture_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxSize()) {
                    if (loadError) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(R.string.data_capture_error),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(14.dp))
                            TextButton(onClick = {
                                loadError = false
                                webViewHolder.value?.reload()
                            }) { Text(stringResource(R.string.data_capture_retry)) }
                        }
                    } else {
                        CaptureWebView(
                            request = request,
                            holder = webViewHolder,
                            onPage = { title, host ->
                                pageTitle = title
                                pageHost = host
                            },
                            onLoadError = { loadError = true },
                            onRedirect = { pendingRedirect = it },
                            onCaptured = { AppGraph.dataRepo.resolveCapture(it) },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * WebView de captura. Intercepta navegações de frame principal (mesmo host
 * navega; host novo pergunta; esquemas não web bloqueia) e o download
 * ([DownloadListener]) — que vira a captura entregue a [onCaptured].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptureWebView(
    request: GameDataInstaller.CaptureRequest,
    holder: MutableState<WebView?>,
    onPage: (String, String) -> Unit,
    onLoadError: () -> Unit,
    onRedirect: (String) -> Unit,
    onCaptured: (GameDataInstaller.CapturedFile) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CAPTURE_UA
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (!request.isForMainFrame) return false // iframes/ads: deixa
                        val target = request.url
                        val scheme = target.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") {
                            // intent:/market:/javascript:/mailto:… — bloqueado.
                            Toast.makeText(
                                view.context,
                                view.context.getString(
                                    R.string.data_capture_scheme_blocked,
                                    scheme ?: "?",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return true
                        }
                        val currentHost = hostOf(view.url)
                        val targetHost = hostOf(target.toString())
                        if (currentHost.isNullOrBlank() || targetHost.isNullOrBlank()) return false
                        return if (targetHost != currentHost) {
                            // Site forçando redirecionamento para outra página:
                            // o usuário decide, vendo a URL completa.
                            onRedirect(target.toString())
                            true // não carrega até a aprovação
                        } else {
                            false
                        }
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        onPage(view.title.orEmpty(), hostOf(finishedUrl).orEmpty())
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) onLoadError()
                    }
                }

                setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                    handleCapturedDownload(
                        context = ctx,
                        view = this,
                        gameId = request.gameId,
                        url = url,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        onCaptured = onCaptured,
                    )
                }
            }.also { view ->
                // Ponte para downloads gerados por JS (blob:) — ver [CaptureBlobBridge].
                // Fora do apply para referenciar a view explícitamente.
                view.addJavascriptInterface(
                    CaptureBlobBridge(ctx, request.gameId) { file, mime ->
                        onCaptured(
                            GameDataInstaller.CapturedFile(
                                url = "",
                                fileName = file.name,
                                mimeType = mime,
                                contentLength = file.length(),
                                userAgent = view.settings.userAgentString,
                                referer = view.url,
                                cookies = "",
                                localFile = file,
                            ),
                        )
                    },
                    "PortStoreCapture",
                )
                view.loadUrl(request.pageUrl)
                holder.value = view
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            holder.value?.apply {
                stopLoading()
                destroy()
            }
            holder.value = null
        }
    }
}

/** Intercepção do download: http(s) → captura de sessão; data:/blob: → arquivo local. */
private fun handleCapturedDownload(
    context: Context,
    view: WebView,
    gameId: String,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
    contentLength: Long,
    onCaptured: (GameDataInstaller.CapturedFile) -> Unit,
) {
    val referer = view.url
    val ua = view.settings.userAgentString
    when {
        url.startsWith("data:", ignoreCase = true) -> {
            val file = decodeDataUrl(context, url)
            if (file != null) {
                onCaptured(
                    GameDataInstaller.CapturedFile(
                        url = "",
                        fileName = file.name,
                        mimeType = mimeType,
                        contentLength = file.length(),
                        userAgent = ua,
                        referer = referer,
                        cookies = "",
                        localFile = file,
                    ),
                )
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.data_capture_inline_big),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        url.startsWith("blob:", ignoreCase = true) -> {
            // O conteúdo vive na heap JS da página — baixa via fetch no JS da
            // própria página, em pedaços base64 para a ponte nativa.
            val token = "b${System.currentTimeMillis()}"
            val js = blobFetcherScript(token, url, BLOB_MAX_BYTES)
            view.evaluateJavascript(js, null)
        }

        url.startsWith("http://") || url.startsWith("https://") -> {
            val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
            onCaptured(
                GameDataInstaller.CapturedFile(
                    url = url,
                    fileName = fileNameFrom(contentDisposition, url),
                    mimeType = mimeType,
                    contentLength = contentLength,
                    userAgent = ua,
                    referer = referer,
                    cookies = cookies,
                ),
            )
        }

        else -> Toast.makeText(
            context,
            context.getString(R.string.data_capture_scheme_blocked, Uri.parse(url).scheme ?: "?"),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/** Nome de arquivo do Content-Disposition (RFC 5987/6266) ou do path da URL. */
private fun fileNameFrom(contentDisposition: String?, url: String): String? {
    contentDisposition?.let { cd ->
        Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }
    return url.substringBefore('?').substringAfterLast('/')
        .takeIf { it.isNotBlank() && it.contains('.') }
        ?.let { Uri.decode(it) }
}

/** Decodifica uma URL `data:` (base64 ou percent-encoded) para arquivo em cache. */
private fun decodeDataUrl(context: Context, url: String): File? = runCatching {
    val comma = url.indexOf(',')
    if (comma < 0) return@runCatching null
    val header = url.substring(5, comma)
    val payload = url.substring(comma + 1)
    val bytes = if (header.contains(";base64", ignoreCase = true)) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        Uri.decode(payload).toByteArray(Charsets.UTF_8)
    }
    if (bytes.isEmpty() || bytes.size > INLINE_MAX_BYTES) return@runCatching null
    val dir = File(context.cacheDir, "gamedata_capture").apply { mkdirs() }
    File(dir, "inline-${System.currentTimeMillis()}.bin").apply { writeBytes(bytes) }
}.getOrNull()

/** JS que baixa um `blob:` com fetch e despeja em pedaços base64 na ponte nativa. */
private fun blobFetcherScript(token: String, blobUrl: String, maxBytes: Long): String {
    val jsUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
    return """
        (function(){
          var T='$token';
          var bridge=window.PortStoreCapture;
          if(!bridge){return;}
          fetch('$jsUrl').then(function(r){
            if(!r.ok){throw new Error('HTTP '+r.status);}
            return r.blob();
          }).then(function(blob){
            if(blob.size>$maxBytes){bridge.onError(T,'too_big:'+blob.size);return;}
            var CHUNK=2*1024*1024, off=0;
            function next(){
              if(off>=blob.size){bridge.onDone(T,blob.size);return;}
              var slice=blob.slice(off,Math.min(off+CHUNK,blob.size));
              var fr=new FileReader();
              fr.onload=function(){
                var dataUrl=String(fr.result);
                var b64=dataUrl.substring(dataUrl.indexOf(',')+1);
                bridge.onChunk(T,b64);
                off+=slice.size;
                setTimeout(next,0);
              };
              fr.onerror=function(){bridge.onError(T,'read');};
              fr.readAsDataURL(slice);
            }
            next();
          }).catch(function(e){bridge.onError(T,String(e));});
        })();
    """.trimIndent()
}

/**
 * Ponte nativa do fetch de `blob:` — acumula os pedaços base64 num arquivo
 * temporário e completa a captura no fim. Exposta só para a página carregada
 * (addJavascriptInterface); escreve APENAS em um arquivo temporário criado
 * aqui — uma página maliciosa, no máximo, estraga o próprio temporário (a
 * extração valida o formato depois).
 */
private class CaptureBlobBridge(
    private val context: Context,
    gameIdRaw: String,
    private val onComplete: (File, String?) -> Unit,
) {
    private val gameId = gameIdRaw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "game" }
    private val lock = Any()
    private var out: FileOutputStream? = null
    private var target: File? = null
    private var written = 0L
    private var done = false

    @JavascriptInterface
    fun onChunk(token: String, b64: String) {
        synchronized(lock) {
            if (done) return
            try {
                if (out == null) {
                    val dir = File(context.cacheDir, "gamedata_capture").apply { mkdirs() }
                    val f = File(dir, "blob-$gameId-${System.currentTimeMillis()}.bin")
                    target = f
                    out = FileOutputStream(f)
                }
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                written += bytes.size
                if (written > BLOB_MAX_BYTES) {
                    fail("too_big")
                    return
                }
                out?.write(bytes)
            } catch (_: Exception) {
                fail("write")
            }
        }
    }

    @JavascriptInterface
    fun onDone(token: String, totalSize: Long) {
        synchronized(lock) {
            if (done) return
            done = true
            runCatching { out?.flush(); out?.close() }
            out = null
            val f = target
            if (f != null && f.length() > 0) {
                android.util.Log.i("portstore", "captura blob concluída: ${f.length()} bytes")
                onComplete(f, null)
            } else {
                f?.delete()
            }
        }
    }

    @JavascriptInterface
    fun onError(token: String, message: String) {
        synchronized(lock) { fail(message) }
    }

    private fun fail(reason: String) {
        done = true
        runCatching { out?.close() }
        out = null
        target?.delete()
        target = null
        android.util.Log.w("portstore", "captura blob falhou: $reason")
    }
}

/** Host de uma URL (lowercase) — comparação de redirecionamento. */
private fun hostOf(url: String?): String? = runCatching {
    Uri.parse(url ?: return@runCatching null).host?.lowercase()
}.getOrNull()
