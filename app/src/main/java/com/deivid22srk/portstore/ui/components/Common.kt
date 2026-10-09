package com.deivid22srk.portstore.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Barra de progresso fina estilo Play Store.
 *
 * Diferente do LinearProgressIndicator padrão do Material 3 (que desenha um
 * ponto de parada na ponta direita e um vão antes dele), esta barra:
 * - ocupa exatamente toda a largura disponível (acompanha a pílula do botão);
 * - tem trilho em formato de pílula (pontas totalmente arredondadas);
 * - desenha o preenchimento com ponta arredondada apenas no início (esquerda),
 *   mantendo a borda de avanço reta, sem pontos ou vãos visuais.
 */
@Composable
fun DownloadProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Lime,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    barHeight: Dp = 6.dp,
) {
    Canvas(modifier = modifier.height(barHeight)) {
        val radius = size.height / 2f
        // Trilho: pílula completa, de ponta a ponta.
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(radius, radius),
        )
        val clamped = progress.coerceIn(0f, 1f)
        val fillWidth = size.width * clamped
        if (fillWidth > 0f && size.width > 0f) {
            // Preenchimento: cantos esquerdos arredondados, borda direita reta.
            val r = minOf(radius, fillWidth)
            val fillPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = fillWidth,
                        bottom = size.height,
                        topLeftCornerRadius = CornerRadius(r, r),
                        bottomLeftCornerRadius = CornerRadius(r, r),
                        topRightCornerRadius = CornerRadius.Zero,
                        bottomRightCornerRadius = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(fillPath, color)
        }
    }
}

/** Efeito shimmer simples (skeleton de carregamento). */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    this.alpha(alpha).background(MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
fun GameImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier.clip(shape),
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SportsEsports,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "Ver mais",
                    style = MaterialTheme.typography.labelLarge,
                    color = Lime,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Ver mais $title",
                    tint = Lime,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) Lime.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) Lime else TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun AppLogo() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Lime),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            tint = Color(0xFF171800),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Selo "Instalado" / "Atualização disponível" para cards e listas. */
@Composable
fun InstallBadge(game: Game, modifier: Modifier = Modifier) {
    val pkg = game.primaryPackage ?: return
    if (game.isWeb) return
    val states by com.deivid22srk.portstore.AppGraph.installedApps.states.collectAsStateWithLifecycle()
    when (states[pkg]) {
        is InstallState.UpdateAvailable -> Pill("Atualização disponível", modifier = modifier, highlight = true)
        is InstallState.Installed -> Pill("Instalado", modifier = modifier)
        else -> Unit
    }
}

/** Card de capa 3:4 usado nos carrosséis (estilo Play Store). */
@Composable
fun CoverCard(
    game: Game,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 128.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
    ) {
        GameImage(
            url = game.coverUrl,
            modifier = Modifier
                .width(width)
                .height(width * 4f / 3f),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = game.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        InstallBadge(game)
    }
}

/** Card largo estilo "Sugestões para você" da Play Store. */
@Composable
fun SuggestionCard(
    game: Game,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameImage(
                url = game.coverUrl,
                modifier = Modifier.size(width = 64.dp, height = 84.dp),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(game.categories.take(2).joinToString(" · ") { com.deivid22srk.portstore.catalog.categoryLabel(it) })
                        game.original?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    game.performanceLabel?.let { Pill(it, highlight = true) }
                    game.apkSize?.takeIf { it.isNotBlank() }?.let { Pill(it) }
                    if (game.isWeb) Pill("Web", highlight = true)
                }
                InstallBadge(game)
            }
        }
    }
}

/** Selo sobre banners (Novo / Em breve / Web). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.65f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Lime,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Degradê inferior para legibilidade sobre imagens. */
fun Modifier.bottomScrim(): Modifier = background(
    brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
    ),
)
