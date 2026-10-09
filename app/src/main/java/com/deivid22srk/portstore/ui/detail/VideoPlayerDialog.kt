@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deivid22srk.portstore.ui.detail

import android.content.res.Configuration
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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

/**
 * Player de vídeo do YouTube EMBUTIDO no app (overlay modal fullscreen).
 *
 * Ao tocar em uma miniatura "Vídeo" do carrossel, este diálogo reproduz o vídeo
 * dentro do próprio app — NUNCA abre o app do YouTube/navegador. Usa a biblioteca
 * android-youtube-player (WebView/IFrame API) com a DefaultPlayerUiController
 * (play/pause, barra de progresso, tempo, mudo, velocidade e botão de tela cheia).
 *
 * - Fechar (X ou Voltar) fecha apenas o diálogo: a tela de detalhes permanece
 *   na mesma posição de scroll (o player é um overlay, não uma navegação).
 * - Rotação não reinicia o vídeo: o manifest declara configChanges e o
 *   YouTubePlayerView sobrevive à reconfiguração; em paisagem o player ocupa
 *   a tela inteira automaticamente.
 * - Ciclo de vida: o YouTubePlayerView é registrado como LifecycleEventObserver
 *   (ON_STOP pausa a reprodução) e release() destrói o WebView ao fechar.
 * - Fallback: em erro de reprodução exibe mensagem amigável com "Tentar
 *   novamente" e "Abrir no YouTube" (único caminho que lança intent externo,
 *   sempre por escolha explícita do usuário).
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
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playerRef by remember { mutableStateOf<YouTubePlayer?>(null) }
    val watchUrl = YouTubeLinks.watchUrl(videoId)

    // Em paisagem o player sempre ocupa a tela toda (padrão dos players de vídeo).
    val effectiveFullscreen = isFullscreen || isLandscape

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
                    onOpenInYouTube = {
                        // Fallback explícito: única saída intencional para o app do YouTube.
                        LinkOpener.open(context, watchUrl)
                        onDismiss()
                    },
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
                        EmbeddedPlayer(
                            videoId = videoId,
                            onPlayerReady = { playerRef = it },
                            onError = { playbackError = it },
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                        )

                        playbackError?.let { message ->
                            PlaybackErrorFallback(
                                message = message,
                                onRetry = {
                                    playbackError = null
                                    playerRef?.loadVideo(videoId, 0f)
                                },
                                onOpenYouTube = {
                                    LinkOpener.open(context, watchUrl)
                                    onDismiss()
                                },
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
 */
@Composable
private fun EmbeddedPlayer(
    videoId: String,
    onPlayerReady: (YouTubePlayer?) -> Unit,
    onError: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // remember: a view sobrevive a recomposições e reconfigurações
    // (o manifest usa configChanges) — o vídeo NÃO reinicia ao girar.
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
                            val controller = DefaultPlayerUiController(this@apply, youTubePlayer)
                            controller.showVideoTitle(false)
                            controller.showMenuButton(false)
                            // O botão de tela cheia da UI alterna o tamanho do
                            // contêiner no Compose (sem mexer em LayoutParams).
                            controller.setFullscreenButtonClickListener { onToggleFullscreen() }
                            setCustomPlayerUi(controller.rootView)
                            onPlayerReady(youTubePlayer)
                            // loadVideo = reproduz imediatamente (autoplay).
                            youTubePlayer.loadVideo(videoId, 0f)
                        }

                        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                            onError(friendlyErrorMessage(error))
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
            playerView.release()
        }
    }
}

@Composable
private fun PlaybackErrorFallback(
    message: String,
    onRetry: () -> Unit,
    onOpenYouTube: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.93f)),
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
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tentar novamente")
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

private fun friendlyErrorMessage(error: PlayerConstants.PlayerError): String = when (error) {
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND ->
        "O vídeo não foi encontrado. Ele pode ter sido removido ou tornado privado no YouTube."
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ->
        "O dono do vídeo não permite a reprodução fora do YouTube. Abra no YouTube para assistir."
    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST ->
        "O link do vídeo está inválido ou desatualizado."
    PlayerConstants.PlayerError.HTML_5_PLAYER ->
        "Falha no player de vídeo. Verifique sua conexão e tente novamente."
    PlayerConstants.PlayerError.UNKNOWN ->
        "Ocorreu um erro inesperado ao reproduzir o vídeo."
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
