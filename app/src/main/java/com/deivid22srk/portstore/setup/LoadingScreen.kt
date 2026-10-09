package com.deivid22srk.portstore.setup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.catalog.LoadPhase
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tela cheia de carregamento pós-setup (referência: F-Droid "Buscando
 * repositórios de apps…"): indicador circular ondulado central, texto de
 * fase e progresso por repositório.
 */
@Composable
fun LoadingScreen(
    viewModel: SetupViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress by viewModel.loadProgress.collectAsStateWithLifecycle()
    val finished by viewModel.loadingFinished.collectAsStateWithLifecycle()
    val totalError by viewModel.loadingTotalError.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(finished) {
        if (finished) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            val determinate = progress?.overall?.takeIf { it > 0.01f }
            WavyCircularIndicator(
                progress = determinate,
                modifier = Modifier.size(132.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        val phaseText = when {
            totalError -> "Não foi possível carregar os repositórios"
            progress == null -> "Preparando…"
            else -> when (progress!!.phase) {
                LoadPhase.BUSCANDO -> "Buscando repositórios…"
                LoadPhase.BAIXANDO -> "Baixando dados…"
                LoadPhase.PROCESSANDO -> "Processando jogos…"
                LoadPhase.IMAGENS -> "Preparando imagens…"
                LoadPhase.PRONTO -> "Tudo pronto!"
            }
        }
        Text(
            text = phaseText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        progress?.let { p ->
            Spacer(Modifier.height(8.dp))
            val sub = buildString {
                p.currentRepo?.let { append(it) }
                if (p.repos.isNotEmpty()) {
                    val done = p.repos.count { it.state == "ok" || it.state == "falhou" }
                    if (done > 0) {
                        if (isNotEmpty()) append(" • ")
                        append("$done de ${p.repos.size} repositórios")
                    }
                }
                if (p.totalBytes > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("${com.deivid22srk.portstore.util.Formatters.formatBytes(p.downloadedBytes)} de ${com.deivid22srk.portstore.util.Formatters.formatBytes(p.totalBytes)}")
                }
            }
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
            }

            if (p.repos.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        p.repos.forEach { repo ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                            ) {
                                val (icon, color) = when (repo.state) {
                                    "ok" -> "✓" to Lime
                                    "falhou" -> "!" to MaterialTheme.colorScheme.error
                                    "baixando", "lendo" -> "…" to TextSecondary
                                    else -> "•" to TextSecondary
                                }
                                Text(icon, color = color, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    repo.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    when (repo.state) {
                                        "ok" -> "concluído"
                                        "falhou" -> repo.error?.let { if (it.length > 28) "falhou" else it } ?: "falhou"
                                        "baixando" -> "baixando"
                                        "lendo" -> "lendo"
                                        else -> "aguardando"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))

        when {
            totalError -> {
                Text(
                    "Verifique sua conexão e tente de novo. Se você já usou o app antes, " +
                        "pode continuar com o catálogo salvo no aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(24.dp)) {
                        Text("Voltar ao setup")
                    }
                    Button(
                        onClick = viewModel::continueOffline,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
                    ) { Text("Continuar offline") }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = viewModel::retryLoading) { Text("Tentar novamente") }
            }
            else -> {
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }
}

/**
 * Indicador circular ondulado: círculo com raio modulado por seno, girando
 * (indeterminado) ou com arco proporcional ao progresso (determinado).
 */
@Composable
fun WavyCircularIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = Lime,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavyPhase",
    )
    val sweepPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavySweep",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = min(size.width, size.height) / 2f * 0.72f
        val amplitude = baseRadius * 0.10f
        val waves = 14
        val stroke = Stroke(width = baseRadius * 0.14f, cap = StrokeCap.Round)

        fun radiusAt(theta: Float, t: Float): Float =
            baseRadius + amplitude * sin(waves * theta + t)

        fun buildPath(t: Float, sweepFraction: Float): Path {
            val path = Path()
            if (sweepFraction >= 0.999f) {
                var angle = 0f
                var first = true
                while (angle <= 2f * PI.toFloat() + 0.05f) {
                    val r = radiusAt(angle, t)
                    val p = Offset(
                        center.x + r * cos(angle),
                        center.y + r * sin(angle),
                    )
                    if (first) { path.moveTo(p.x, p.y); first = false } else path.lineTo(p.x, p.y)
                    angle += 0.05f
                }
                path.close()
            } else {
                val sweep = (2f * PI.toFloat()) * sweepFraction.coerceIn(0.03f, 1f)
                var angle = 0f
                var first = true
                while (angle <= sweep) {
                    val r = radiusAt(angle, t)
                    val p = Offset(
                        center.x + r * cos(angle),
                        center.y + r * sin(angle),
                    )
                    if (first) { path.moveTo(p.x, p.y); first = false } else path.lineTo(p.x, p.y)
                    angle += 0.04f
                }
            }
            return path
        }

        // Trilho
        drawPath(
            path = buildPath(phase, 1f),
            color = color.copy(alpha = 0.18f),
            style = stroke,
        )
        // Preenchimento
        val fraction = progress ?: (0.25f + 0.35f * (1f - cos(sweepPhase)) / 2f)
        drawPath(
            path = buildPath(phase, fraction),
            color = color,
            style = stroke,
        )
    }
}
