package com.deivid22srk.portstore.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.LauncherHints
import com.deivid22srk.portstore.launcher.LauncherTopBar
import com.deivid22srk.portstore.launcher.input.GamepadMonitor
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.launcher.input.launcherGamepadNav
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Sobre-cor do texto sobre o amarelo-limão (mesma do launcher). */
private val OnLime = Color(0xFF171800)

/**
 * Scaffold abstrato do assistente de configuração: mesmo conteúdo de etapas
 * ([stepContent]) renderizado em duas "peles" conforme o modo de uso:
 *
 * - [PhoneSetupScaffold]: layout retrato atual (indicador segmentado no topo,
 *   botões Voltar/Próximo na base).
 * - [GamerSetupScaffold]: layout estilo console do Game Launcher (paisagem,
 *   barra de status customizada, cards focáveis com borda amarelo-limão,
 *   navegação por controle e barra de dicas contextual).
 *
 * O indicador "Etapa X de N" permanece visível nos dois layouts.
 */
@Composable
fun SetupScaffold(
    gamer: Boolean,
    step: Int,
    totalSteps: Int,
    canProceed: Boolean,
    nextLabel: String,
    backVisible: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    monitor: GamepadMonitor?,
    stepContent: @Composable (Int) -> Unit,
) {
    if (gamer) {
        GamerSetupScaffold(
            step = step,
            totalSteps = totalSteps,
            canProceed = canProceed,
            nextLabel = nextLabel,
            backVisible = backVisible,
            onBack = onBack,
            onNext = onNext,
            monitor = monitor,
            stepContent = stepContent,
        )
    } else {
        PhoneSetupScaffold(
            step = step,
            totalSteps = totalSteps,
            canProceed = canProceed,
            nextLabel = nextLabel,
            backVisible = backVisible,
            onBack = onBack,
            onNext = onNext,
            stepContent = stepContent,
        )
    }
}

/** Transição entre etapas respeitando a direção (avançar/ voltar). */
@Composable
private fun StepTransition(
    step: Int,
    stepContent: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier,
    slide: Boolean = true,
) {
    AnimatedContent(
        // weight(1f) é aplicado pelo chamador: o rodapé é medido primeiro e
        // o conteúdo recebe o espaço restante.
        modifier = modifier,
        targetState = step,
        transitionSpec = {
            if (slide) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "setupStep",
    ) { target ->
        stepContent(target)
    }
}

// =====================================================================
// Implementação CELULAR (retrato) — visual do assistente original.
// =====================================================================

@Composable
fun PhoneSetupScaffold(
    step: Int,
    totalSteps: Int,
    canProceed: Boolean,
    nextLabel: String,
    backVisible: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    stepContent: @Composable (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SetupStepHeader(step = step, totalSteps = totalSteps)

        StepTransition(
            step = step,
            stepContent = stepContent,
            modifier = Modifier.weight(1f),
        )

        SetupFooter(
            backVisible = backVisible,
            canProceed = canProceed,
            nextLabel = nextLabel,
            onBack = onBack,
            onNext = onNext,
        )
    }
}

/**
 * Cabeçalho "Etapa X de N" com barra de progresso segmentada (identidade
 * visual do assistente original).
 */
@Composable
fun SetupStepHeader(step: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            text = stringResource(R.string.setup_step_indicator, step + 1, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = Lime,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(totalSteps) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            color = if (i <= step) Lime else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun SetupFooter(
    backVisible: Boolean,
    canProceed: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (backVisible) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(0.35f),
                    shape = RoundedCornerShape(24.dp),
                ) { Text(stringResource(R.string.setup_back)) }
            }
            Button(
                onClick = onNext,
                enabled = canProceed,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Color(0xFF171800),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = TextSecondary,
                ),
            ) {
                Text(
                    nextLabel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

// =====================================================================
// Implementação GAMER LAUNCHER (paisagem, console) — visual do launcher.
// =====================================================================

@Composable
fun GamerSetupScaffold(
    step: Int,
    totalSteps: Int,
    canProceed: Boolean,
    nextLabel: String,
    backVisible: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    monitor: GamepadMonitor?,
    stepContent: @Composable (Int) -> Unit,
) {
    val gamepadInput = if (monitor != null) {
        monitor.lastInputGamepad.collectAsStateWithLifecycle().value
    } else {
        false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .let { m ->
                if (monitor != null) {
                    m.launcherGamepadNav(
                        monitor = monitor,
                        onBack = onBack,
                        // Start = próximo (quando a etapa permite avançar).
                        onStart = { if (canProceed) onNext() },
                    )
                } else {
                    m
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LauncherTopBar()
            SetupStepHeader(step = step, totalSteps = totalSteps)

            StepTransition(
                step = step,
                stepContent = stepContent,
                modifier = Modifier.weight(1f),
            )

            GamerSetupActions(
                backVisible = backVisible,
                canProceed = canProceed,
                nextLabel = nextLabel,
                onBack = onBack,
                onNext = onNext,
            )

            LauncherHints(
                stringResource(
                    if (gamepadInput) R.string.setup_hint_gamer else R.string.setup_hint_touch_gamer,
                ),
            )
        }
    }
}

/** Botões Voltar/Próximo do layout gamer: pílulas grandes focáveis. */
@Composable
private fun GamerSetupActions(
    backVisible: Boolean,
    canProceed: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (backVisible) {
            GamerPillButton(
                label = stringResource(R.string.setup_back),
                filled = false,
                enabled = true,
                onClick = onBack,
            )
            Spacer(Modifier.width(16.dp))
        }
        GamerPillButton(
            label = nextLabel,
            filled = true,
            enabled = canProceed,
            onClick = onNext,
        )
    }
}

/** Pílula de ação com tratamento de foco idêntico aos botões do launcher. */
@Composable
fun GamerPillButton(
    label: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(52.dp)
            .defaultMinSize(minWidth = 48.dp)
            .launcherFocusable(
                shape = shape,
                enabled = enabled,
                scaleFocused = if (enabled) 1.05f else 1f,
                onClick = onClick,
                onFocusChange = { focused = it },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    color = when {
                        focused && filled -> Lime
                        filled -> Lime.copy(alpha = 0.24f)
                        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    shape = shape,
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> TextSecondary
                focused && filled -> OnLime
                filled -> Lime
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
    }
}
