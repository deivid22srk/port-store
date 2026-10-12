package com.deivid22srk.portstore.gamedata

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R

/**
 * UA de DESKTOP usado no WebView do challenge — DEVE ser exatamente o mesmo
 * UA que o motor Rust usa nas requisições seguintes: `cf_clearance` é ligado
 * a IP+UA, então o Cloudflare valida o cookie contra o UA das chamadas.
 * Desktop (e não o UA mobile do WebView) porque a emulação do wreq é de
 * Chrome desktop (mesmos headers sec-ch-ua/etc.). Mesmo valor de
 * `DESKTOP_UA` em `core/src/buzzheavier.rs`.
 */
private const val CHALLENGE_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Títulos típicos da página de verificação do Cloudflare (enquanto ativa). */
private val CHALLENGE_TITLE_MARKERS =
    listOf("just a moment", "attention required", "please wait", "checking your browser", "verificando")

private fun isChallengeTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return true // ainda carregando — tratar como challenge
    return CHALLENGE_TITLE_MARKERS.any { title.contains(it, ignoreCase = true) }
}

/**
 * Diálogo GLOBAL do plano B do Cloudflare (dados complementares): o provedor
 * pediu verificação e a emulação do motor não bastou. Um WebView REAL (o
 * mesmo motor do Chrome do aparelho) carrega a página do provedor; quando a
 * verificação passa (ou o usuário toca em "Continuar"), os cookies
 * (incluindo `cf_clearance`) + o UA do WebView são devolvidos ao
 * [GameDataInstaller], que refaz a resolução e o download com eles.
 *
 * Observa [GameDataInstaller.challenge] globalmente (MainActivity) — o
 * pedido pode surgir da instalação automática, com o usuário em qualquer
 * tela. Cancelar → instalação falha com CANCELED (o usuário pode tentar de
 * novo, o WebView é aberto de novo).
 */
@Composable
fun GameDataChallengeDialog() {
    val request by AppGraph.dataRepo.challenge.collectAsStateWithLifecycle()
    val current = request ?: return
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    /** Envia os cookies atuais ao instalador (plano B resolvido). */
    fun respondWithCookies(force: Boolean) {
        val view = webViewHolder.value ?: return
        val cookies = CookieManager.getInstance().getCookie(current.pageUrl).orEmpty()
        if (force || !isChallengeTitle(view.title)) {
            AppGraph.dataRepo.resolveChallenge(
                GameDataInstaller.ChallengeCleared(cookies, view.settings.userAgentString),
            )
        }
    }

    // Recrea o WebView para cada pedido (páginas/hosts diferentes).
    key(current) {
        AlertDialog(
            onDismissRequest = { AppGraph.dataRepo.resolveChallenge(null) },
            title = { Text(stringResource(R.string.data_challenge_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.data_challenge_message),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    ChallengeWebView(
                        pageUrl = current.pageUrl,
                        holder = webViewHolder,
                        onAutoCleared = { respondWithCookies(force = false) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { respondWithCookies(force = true) }) {
                    Text(stringResource(R.string.data_challenge_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { AppGraph.dataRepo.resolveChallenge(null) }) {
                    Text(stringResource(R.string.data_challenge_cancel))
                }
            },
        )
    }
}

/**
 * WebView do challenge. Auto-responde (via [onAutoCleared]) quando a página
 * termina de carregar FORA de uma página de verificação do Cloudflare
 * (título típico ausente) — com um pequeno atraso para os cookies
 * `cf_clearance` assentarem. O botão "Continuar" do diálogo cobre o caso de
 * detecção falha (força o envio dos cookies correntes).
 */
@Composable
private fun ChallengeWebView(
    pageUrl: String,
    holder: MutableState<WebView?>,
    onAutoCleared: () -> Unit,
) {
    var responded by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CHALLENGE_UA
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({
                            if (!responded && !isChallengeTitle(view.title)) {
                                responded = true
                                onAutoCleared()
                            }
                        }, 1_500L)
                    }
                }
                loadUrl(pageUrl)
                holder.value = this
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
