package com.deivid22srk.portstore.launcher

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.theme.BgDark
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Tela de carregamento/erro da abertura de jogos pelo Game Launcher (Recurso
 * 1): fundo escuro com a capa do jogo desfocada, logo do Port Store com
 * entrada e pulso, indicador girando, capa+nome do jogo e texto de estado.
 *
 * Visual: 60 fps com animações apenas de alpha/escala; capa desfocada é
 * estática (RenderEffect, API 31+). Com "reduzir animações" do sistema
 * (escala 0), tudo fica estático — só conteúdo.
 *
 * NÃO tem elementos focáveis enquanto carrega (o foco do Launcher permanece
 * no card; B/Voltar é tratado pelo LauncherRoot → [GameExperienceController]
 * .cancel()). Na tela de erro os botões são focáveis — ao fechar, o Launcher
 * recupera o foco do card/botão salvo (ver LauncherHomeScreen).
 */
@Composable
fun GameLoadingOverlay(state: GameExperienceController.State) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
    ) {
        GameBackdrop(coverUrl = state.coverUrl)
        when (state) {
            is GameExperienceController.State.Loading ->
                LoadingContent(state)
            is GameExperienceController.State.Error ->
                ErrorContent(state)
            GameExperienceController.State.Idle -> Unit
        }
    }
}

// =====================================================================
// Fundo: capa desfocada + véu escuro (coerente com o Launcher)
// =====================================================================

@Composable
private fun GameBackdrop(coverUrl: String?) {
    val canBlur = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (canBlur) 0.42f else 0.30f),
    ) {
        GameImage(
            url = coverUrl,
            modifier = Modifier
                .fillMaxSize()
                .then(if (canBlur) Modifier.blur(26.dp) else Modifier),
            shape = RectangleShape,
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(BgDark.copy(alpha = 0.66f)),
    )
}

// =====================================================================
// Carregando
// =====================================================================

@Composable
private fun LoadingContent(state: GameExperienceController.State.Loading) {
    val reduced = rememberReducedMotion()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        PortStoreLogo(reduced)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(
            color = Lime,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeWidth = 4.dp,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(8.dp))
        Crossfade(
            targetState = statusText(state),
            animationSpec = tween(if (reduced) 0 else 300),
            label = "loadingStatus",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
    // Dica de botões no rodapé (só faz sentido com o jogo ainda não aberto).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        LauncherHints(
            text = stringResource(R.string.game_loading_cancel_hint),
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun statusText(state: GameExperienceController.State.Loading): String = when {
    !state.virtual && state.phase == GameExperienceController.Phase.PREPARING ->
        stringResource(R.string.game_loading_opening)
    state.phase == GameExperienceController.Phase.PREPARING ->
        stringResource(R.string.game_loading_preparing)
    state.phase == GameExperienceController.Phase.LOADING ->
        stringResource(R.string.game_loading_loading)
    else ->
        stringResource(R.string.game_loading_almost)
}

// =====================================================================
// Logo do Port Store: quadrado amarelo-limão + ícone de download,
// com entrada (fade/escala) e pulso discreto.
// =====================================================================

@Composable
private fun PortStoreLogo(reduced: Boolean) {
    val pulse = rememberInfiniteTransition(label = "portStoreLogo")
    val glow by pulse.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "logoGlow",
    )
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "logoPulseScale",
    )
    var appeared by remember { mutableStateOf(reduced) }
    val entrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.82f,
        animationSpec = tween(340),
        label = "logoEntrance",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(300),
        label = "logoEntranceAlpha",
    )
    LaunchedEffect(Unit) { appeared = true }

    Box(contentAlignment = Alignment.Center) {
        // Anel de brilho atrás do quadrado.
        Box(
            Modifier
                .size(140.dp)
                .scale(if (reduced) 1f else pulseScale)
                .background(Lime.copy(alpha = if (reduced) 0.14f else glow), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .scale(if (reduced) 1f else entrance)
                .clip(RoundedCornerShape(28.dp))
                .background(Lime),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = Color(0xFF171800),
                modifier = Modifier
                    .size(58.dp)
                    .alpha(if (reduced) 1f else entranceAlpha),
            )
        }
    }
}

// =====================================================================
// Erro
// =====================================================================

@Composable
private fun ErrorContent(state: GameExperienceController.State.Error) {
    val retryRequester = remember { FocusRequester() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        ErrorIcon(icon = Icons.Rounded.ErrorOutline)
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.game_launch_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = errorDetail(state.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(22.dp))

        ErrorAction(
            label = stringResource(R.string.game_launch_retry),
            container = Lime,
            contentColor = Color(0xFF171800),
            focusRequester = retryRequester,
            onClick = { GameExperienceController.retry() },
        )
        Spacer(Modifier.height(10.dp))
        ErrorAction(
            label = stringResource(R.string.game_launch_back),
            container = Color.Transparent,
            contentColor = TextPrimary,
            onClick = { GameExperienceController.dismissError() },
        )
        if (state.virtual) {
            Spacer(Modifier.height(10.dp))
            ErrorAction(
                label = stringResource(R.string.game_launch_install_android),
                container = Color.Transparent,
                contentColor = TextPrimary,
                onClick = {
                    val context = LocalContext.current
                    GameExperienceController.installNormally(context)
                },
            )
        }
    }
    // Foco inicial em "Tentar novamente" (navegável por gamepad e touch).
    LauncherInitialFocus(retryRequester)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        LauncherHints(
            text = stringResource(R.string.game_launch_back_hint),
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun ErrorIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(GameExperienceController.ErrorColor.copy(alpha = 0.16f)),
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GameExperienceController.ErrorColor,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun ErrorAction(
    label: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .widthIn(min = 260.dp)
            .clip(shape)
            .background(container)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .launcherFocusable(
                shape = shape,
                scaleFocused = 1.05f,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun errorDetail(reason: GameExperienceController.ErrorReason): String =
    when (reason) {
        GameExperienceController.ErrorReason.ENGINE ->
            stringResource(R.string.game_launch_error_engine)
        GameExperienceController.ErrorReason.LAUNCH_FAILED ->
            stringResource(R.string.game_launch_error_launch)
        GameExperienceController.ErrorReason.TIMEOUT ->
            stringResource(R.string.game_launch_error_timeout)
        GameExperienceController.ErrorReason.RESOLVE ->
            stringResource(R.string.game_launch_error_resolve)
    }

/**
 * "Reduzir animações" do sistema (escala de animador/transição = 0): versão
 * estática do overlay — sem pulso, sem crossfade, sem entrada animada.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            val resolver = context.contentResolver
            Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f ||
                Settings.Global.getFloat(
                    resolver,
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                    1f,
                ) == 0f
        }.getOrDefault(false)
    }
}
