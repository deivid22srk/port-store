@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deivid22srk.portstore.ui.detail

import android.content.res.Configuration
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.util.LinkOpener
import com.deivid22srk.portstore.util.YouTubeLinks
import com.pierfrancescosoffritti.androidyoutubeplayer.core.customui.DefaultPlayerUiController
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

private const val TAG = "PortStoreVideo"

/** Timeout para o player ficar pronto (onReady) ou começar a tocar (PLAYING). */
private const val LOAD_TIMEOUT_MS = 10_000L

/** Máximo de tentativas de "Tentar novamente" (cada uma DESTRÓI e recria o player). */
private const val MAX_RETRIES = 2

/**
 * Tipos de falha de reprodução. [allowRetry] = false para erros ESTÁTICOS, em que
 * tentar de novo nunca funciona (id inválido, vídeo removido, incorporação
 * desativada) — nesses casos a única ação oferecida é "Abrir no YouTube".
 */
private enum class VideoFailure(val allowRetry: Boolean) {
    /** IFrame code 2: parâmetro/ID inválido na requisição. */
    INVALID_ID(false),

    /** IFrame code 5: erro do player HTML5 (transiente — retry pode resolver). */
    HTML5(true),

    /** IFrame code 100: vídeo removido ou privado. */
    NOT_FOUND(false),

    /** IFrame codes 101/150 e variações do embed (ex.: "152 - 4"): incorporação desativada/restrita. */
    RESTRICTED(false),

    /** Player não ficou pronto (ou não entrou em PLAYING) dentro do timeout. */
    TIMEOUT(true),
}

private fun messageFor(failure: VideoFailure): String = when (failure) {
    VideoFailure.INVALID_ID ->
        "O ID do vídeo configurado para este jogo é inválido."
    VideoFailure.HTML5 ->
        "Falha no player de vídeo. Verifique sua conexão e tente novamente."
    VideoFailure.NOT_FOUND ->
        "Vídeo não encontrado. Ele pode ter sido removido ou tornado privado no YouTube."
    VideoFailure.RESTRICTED ->
        "O dono do vídeo desativou a incorporação em outros apps/sites. " +
            "Assista diretamente no YouTube."
    VideoFailure.TIMEOUT ->
        "O vídeo demorou demais para carregar. Verifique sua conexão e tente novamente."
}

/**
 * Player de vídeo do YouTube EMBUTIDO no app (overlay modal fullscreen).
 *
 * Ao tocar em uma miniatura "Vídeo" do carrossel, este diálogo reproduz o vídeo
 * dentro do próprio app — NUNCA abre o app do YouTube/navegador. Usa a biblioteca
 * android-youtube-player (WebView/IFrame API) com a DefaultPlayerUiController
 * (play/pause, barra de progresso, tempo, mudo, velocidade e botão de tela cheia).
 *
 * Máquina de estado da reprodução:
 * - Loading: player criado, aguardando onReady (spinner do app; timeout de 10s).
 * - Ready: onReady recebido, loadVideo disparado, aguardando o 1º PLAYING
 *   (timeout de 10s — evita spinner/buffering infinito).
 * - Playing: reprodução confirmada; timeouts cancelados.
 * - Failed: UMA ÚNICA superfície de erro do app (fundo PRETO OPACO — a página
 *   de erro interna do YouTube nunca aparece por baixo, pois o WebView é
 *   destruído). Mensagem específica por código; "Tentar novamente" só em
 *   falhas transientes (HTML5/timeout) e limitado a [MAX_RETRIES] tentativas,
 *   cada uma recriando o WebView do zero; "Abrir no YouTube" é o fallback
 *   final (única intent externa, sempre por escolha explícita do usuário).
 *
 * - Fechar (X ou Voltar) fecha apenas o diálogo: a tela de detalhes permanece
 *   na mesma posição de scroll (o player é um overlay, não uma navegação).
 * - Rotação não reinicia o vídeo: o manifest declara configChanges e o
 *   YouTubePlayerView sobrevive à reconfiguração; em paisagem o player ocupa
 *   a tela inteira automaticamente.
 * - Ciclo de vida: o YouTubePlayerView é registrado como LifecycleEventObserver
 *   (ON_STOP pausa a reprodução) e release() destrói o WebView ao fechar ou
 *   ao entrar em falha (sem vazamento, abrir/fechar repetido ok).
 *
 * Configuração do WebView: gerenciada pela própria lib 12.1.0 —
 * javaScriptEnabled=true, mediaPlaybackRequiresUserGesture=false (autoplay ok),
 * origin="https://www.youtube.com" (domínio confiável recomendado pela lib)
 * e sem User-Agent customizado. O videoId que chega aqui é SEMPRE validado
 * (11 caracteres) por [YouTubeLinks.isValidVideoId] — id malformado nunca
 * chega ao player.
 */
@Composable
fun YouTubePlayerDialog(
    videoId: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isFullscreen by remember { mutableStateOf(false) }
    val effectiveFullscreen = isFullscreen || isLandscape

    // Estado da reprodução (escrito também pelos callbacks da lib na thread
    // JavaBridge — snapshot state do Compose é thread-safe para escrita).
    var retryToken by remember { mutableIntStateOf(0) } // incrementa => destrói e recria o player
    var retryCount by remember { mutableIntStateOf(0) }
    var isReady by remember { mutableStateOf(false) } // onReady recebido
    var hasPlayed by remember { mutableStateOf(false) } // 1º PLAYING recebido
    var failure by remember { mutableStateOf<VideoFailure?>(null) }

    val idValid = remember(videoId) { YouTubeLinks.isValidVideoId(videoId) }

    // Guarda de defesa: videoId malformado NUNCA chega ao player — erro específico imediato.
    LaunchedEffect(idValid, videoId) {
        if (!idValid) {
            Log.e(TAG, "videoId inválido: '$videoId' (jogo='$title') — player nem será criado")
            failure = VideoFailure.INVALID_ID
        }
    }

    fun openOnYouTube() {
        // Fallback explícito: única saída intencional para o app do YouTube.
        LinkOpener.open(context, YouTubeLinks.watchUrl(videoId))
        onDismiss()
    }

    fun retry() {
        if (failure == null || retryCount >= MAX_RETRIES) return
        Log.i(TAG, "retry ${retryCount + 1}/$MAX_RETRIES: recriando player (videoId=$videoId)")
        retryCount++
        isReady = false
        hasPlayed = false
        failure = null
        // A troca do token remove o EmbeddedPlayer atual da composição:
        // o DisposableEffect libera (release) a WebView morta e uma NOVA
        // instância é criada com load do zero — sem reaproveitar estado morto.
        retryToken++
    }

    // Timeout 1: o player não ficou pronto (onReady) em 10s.
    LaunchedEffect(retryToken) {
        delay(LOAD_TIMEOUT_MS)
        if (!isReady && failure == null) {
            Log.w(
                TAG,
                "timeout: onReady não chegou em ${LOAD_TIMEOUT_MS / 1000}s " +
                    "(videoId=$videoId, tentativa=$retryCount)",
            )
            failure = VideoFailure.TIMEOUT
        }
    }

    // Timeout 2: pronto, mas nunca entrou em PLAYING (buffering preso/spinner infinito).
    LaunchedEffect(retryToken, isReady) {
        if (!isReady) return@LaunchedEffect
        delay(LOAD_TIMEOUT_MS)
        if (!hasPlayed && failure == null) {
            Log.w(
                TAG,
                "timeout: PLAYING não chegou em ${LOAD_TIMEOUT_MS / 1000}s " +
                    "(videoId=$videoId, tentativa=$retryCount)",
            )
            failure = VideoFailure.TIMEOUT
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                PlayerTopBar(
                    title = title,
                    onClose = onDismiss,
                    onOpenInYouTube = { openOnYouTube() },
                    onToggleFullscreen = { isFullscreen = !isFullscreen },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = if (effectiveFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        },
                    ) {
                        val currentFailure = failure
                        if (currentFailure == null && idValid) {
                            key(retryToken) {
                                EmbeddedPlayer(
                                    videoId = videoId,
                                    onReady = { isReady = true },
                                    onFirstPlay = { hasPlayed = true },
                                    onError = { failure = it },
                                    onToggleFullscreen = { isFullscreen = !isFullscreen },
                                )
                            }
                            // Feedback enquanto o IFrame carrega (antes do onReady a
                            // WebView é só um retângulo preto): spinner do app.
                            if (!isReady) {
                                LoadingOverlay()
                            }
                        } else if (currentFailure != null) {
                            // ÚNICA superfície de erro: fundo preto OPACO e o player
                            // REMOVIDO da composição (WebView destruída) — a página
                            // de erro interna do YouTube não existe mais por baixo.
                            PlaybackErrorFallback(
                                failure = currentFailure,
                                canRetry = retryCount < MAX_RETRIES,
                                onRetry = { retry() },
                                onOpenYouTube = { openOnYouTube() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    onClose: () -> Unit,
    onOpenInYouTube: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Fechar player", tint = Color.White)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenInYouTube) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = "Abrir no YouTube", tint = Color.White)
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = "Tela cheia", tint = Color.White)
        }
    }
}

/**
 * YouTubePlayerView dentro do Compose. Inicialização manual com controls(0)
 * (a UI da IFrame fica desligada) e DefaultPlayerUiController por cima —
 * play/pause, seek, tempo, mudo, velocidade e tela cheia funcionam de fábrica.
 * Reporta onReady/1º PLAYING/onError para a máquina de estado do diálogo.
 */
@Composable
private fun EmbeddedPlayer(
    videoId: String,
    onReady: () -> Unit,
    onFirstPlay: () -> Unit,
    onError: (VideoFailure) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // remember: a view sobrevive a recomposições e reconfigurações
    // (o manifest usa configChanges) — o vídeo NÃO reinicia ao girar.
    // O key(retryToken) no chamador recria TUDO (nova WebView) em cada retry.
    val playerView = remember {
        YouTubePlayerView(context).apply { enableAutomaticInitialization = false }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            playerView.apply {
                // Rede de segurança: com controls(0) a IFrame nunca pede fullscreen
                // nativo do WebView; se pedir, sai imediatamente para não escurecer
                // a tela (o FullscreenListener é obrigatório nesta versão da lib).
                addFullscreenListener(object : FullscreenListener {
                    override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) {
                        exitFullscreen()
                    }

                    override fun onExitFullscreen() = Unit
                })

                initialize(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            Log.i(TAG, "onReady: player pronto (videoId=$videoId)")
                            val controller = DefaultPlayerUiController(this@apply, youTubePlayer)
                            controller.showVideoTitle(false)
                            controller.showMenuButton(false)
                            // O botão de tela cheia da UI alterna o tamanho do
                            // contêiner no Compose (sem mexer em LayoutParams).
                            controller.setFullscreenButtonClickListener { onToggleFullscreen() }
                            setCustomPlayerUi(controller.rootView)
                            onReady()
                            // loadVideo = reproduz imediatamente (autoplay).
                            youTubePlayer.loadVideo(videoId, 0f)
                        }

                        override fun onStateChange(
                            youTubePlayer: YouTubePlayer,
                            state: PlayerConstants.PlayerState,
                        ) {
                            if (state == PlayerConstants.PlayerState.PLAYING) {
                                Log.i(TAG, "PLAYING confirmado (videoId=$videoId)")
                                onFirstPlay()
                            }
                        }

                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) {
                            // Códigos da IFrame API: 2, 5, 100, 101, 150 (+ variações do
                            // embed como "152 - 4", que a lib entrega como UNKNOWN).
                            val mapped = mapFailure(error)
                            Log.e(
                                TAG,
                                "onError: videoId=$videoId código_lib=$error " +
                                    "(familia_embed=${rawCodeHint(error)}) => $mapped",
                            )
                            onError(mapped)
                        }
                    },
                    handleNetworkEvents = true, // reconecta/reinicia sozinho se a rede cair
                    playerOptions = IFramePlayerOptions.Builder() // defaults 12.1.0: controls=0, rel=0, modestbranding=1
                        .controls(0) // UI da IFrame desligada: usamos a DefaultPlayerUiController
                        .rel(0) // sem vídeos relacionados ao terminar
                        .modestBranding(1)
                        .build(),
                )
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        // ON_STOP pausa a reprodução quando o app vai para segundo plano.
        lifecycle.addObserver(playerView)
        onDispose {
            lifecycle.removeObserver(playerView)
            // Destrói o WebView e remove listeners/receivers: sem vazamento.
            // Também roda ao entrar em FALHA (o player sai da composição) —
            // a página de erro do YouTube morre junto com o WebView.
            playerView.release()
            Log.d(TAG, "player liberado (WebView destruída, videoId=$videoId)")
        }
    }
}

/**
 * Mapeia o enum da lib para a falha do app. Em 12.1.0 a lib já traduz os
 * códigos da IFrame API: 101/150 => VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER;
 * QUALQUER outro código fora de {2, 5, 100, 101, 150} — incluindo as
 * variações de restrição do embed (família "152", exibida pelo YouTube como
 * "152 - 4") — chega como UNKNOWN. Em prática, UNKNOWN = restrição de
 * incorporação, então é tratado como RESTRICTED (sem "Tentar novamente"
 * inútil; ação primária "Abrir no YouTube").
 */
private fun mapFailure(error: PlayerConstants.PlayerError): VideoFailure = when (error) {
    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> VideoFailure.INVALID_ID
    PlayerConstants.PlayerError.HTML_5_PLAYER -> VideoFailure.HTML5
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> VideoFailure.NOT_FOUND
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> VideoFailure.RESTRICTED
    PlayerConstants.PlayerError.UNKNOWN -> VideoFailure.RESTRICTED
}

/** Hint para o log: qual família de código da IFrame provavelmente gerou o enum da lib. */
private fun rawCodeHint(error: PlayerConstants.PlayerError): String = when (error) {
    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> "2"
    PlayerConstants.PlayerError.HTML_5_PLAYER -> "5"
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> "100"
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> "101/150"
    PlayerConstants.PlayerError.UNKNOWN -> "152 (e não mapeados)"
}

/** Spinner do app sobre fundo preto OPACO: cobre a WebView enquanto o IFrame carrega. */
@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Lime)
    }
}

/**
 * Superfície de erro ÚNICA: fundo preto OPACO (nunca translúcido — sem
 * "vazamento" da página de erro do YouTube por baixo). Mensagem específica
 * por tipo de falha; "Tentar novamente" apenas em falhas transientes e
 * enquanto houver tentativas; "Abrir no YouTube" sempre disponível.
 */
@Composable
private fun PlaybackErrorFallback(
    failure: VideoFailure,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenYouTube: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Não foi possível reproduzir o vídeo",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = messageFor(failure),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            if (failure.allowRetry && !canRetry) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Não funcionou após $MAX_RETRIES tentativas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failure.allowRetry && canRetry) {
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tentar novamente")
                    }
                }
                Button(
                    onClick = onOpenYouTube,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color.Black),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Abrir no YouTube")
                }
            }
        }
    }
}

/**
 * Lightbox fullscreen para screenshots do carrossel: fundo escuro, imagem em
 * "fit" e botão de fechar. Voltar também fecha (onDismissRequest do Dialog).
 */
@Composable
fun ImageLightboxDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.96f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                GameImage(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    shape = RoundedCornerShape(0.dp),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Fechar imagem", tint = Color.White)
                }
            }
        }
    }
}
