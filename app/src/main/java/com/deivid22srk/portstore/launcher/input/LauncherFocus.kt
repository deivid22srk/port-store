@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.deivid22srk.portstore.launcher.input

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.ui.theme.Lime
import kotlinx.coroutines.launch

/** Configuração de feedback injetada pelo LauncherRoot via CompositionLocal. */
data class LauncherFeedbackConfig(
    val haptics: Boolean = true,
    val sounds: Boolean = false,
)

val LocalLauncherFeedback = compositionLocalOf { LauncherFeedbackConfig() }

val LocalGamepadMonitor = compositionLocalOf<GamepadMonitor?> { null }

/** Chaves que confirmam (botão A / Enter / D-pad central). */
private val CONFIRM_KEYS = listOf(Key.ButtonA, Key.Enter, Key.DirectionCenter)

/** Chave de opções (botão Y). */
private val OPTIONS_KEY = Key.ButtonY

/**
 * Foco do Game Launcher: torna qualquer elemento navegável por D-pad/analógico,
 * com destaque estilo Nintendo Switch (escala + borda/brilho em amarelo-limão),
 * rolagem automática para manter o foco visível e cliques por A/toque.
 *
 * - [onClick]: executado no botão A (gamepad), Enter, D-pad central ou toque.
 * - [onLongClick]: opções do item — botão Y (gamepad) ou toque longo.
 * - [scaleFocused]/[focusedBorderWidth]/[glowColor]: aparência do destaque.
 * - [onFocusChange]: avisado quando este item ganha/perde o foco
 *   (a Home usa para mostrar o nome do jogo focado acima da linha).
 *
 * O toque não quebra o estado do gamepad: tocar um item move o foco para ele
 * (comportamento padrão de [focusable]) e o destaque passa a acompanhá-lo.
 */
fun Modifier.launcherFocusable(
    shape: Shape,
    enabled: Boolean = true,
    scaleFocused: Float = 1.08f,
    focusedBorderWidth: Dp = 3.dp,
    glowColor: Color = Lime,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val feedback = LocalLauncherFeedback.current
    val view = LocalView.current
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val currentClick by rememberUpdatedState(onClick)
    val currentLongClick by rememberUpdatedState(onLongClick)

    val scale by animateFloatAsState(
        targetValue = if (focused) scaleFocused else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "launcherFocusScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) focusedBorderWidth else 0.dp,
        animationSpec = tween(durationMillis = 130),
        label = "launcherFocusBorder",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 130),
        label = "launcherFocusGlow",
    )

    this
        // Brilho (shadow com spot color) + escala — ordem: glow, escala, borda.
        .graphicsLayer {
            shadowElevation = if (focused) 18.dp.toPx() else 0f
            this.shape = shape
            ambientShadowColor = glowColor.copy(alpha = glowAlpha)
            spotShadowColor = glowColor.copy(alpha = glowAlpha)
        }
        .scale(scale)
        .border(
            width = borderWidth,
            color = glowColor.copy(alpha = if (focused) 1f else 0f),
            shape = shape,
        )
        .bringIntoViewRequester(bringIntoView)
        .onFocusEvent { state ->
            val isFocused = state.isFocused
            if (isFocused != focused) {
                focused = isFocused
                onFocusChange?.invoke(isFocused)
                if (isFocused) {
                    scope.launch { runCatching { bringIntoView.bringIntoView() } }
                    LauncherFeedback.focusTick(view, feedback.haptics, feedback.sounds)
                }
            }
        }
        .focusable(enabled = enabled)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onTap = {
                    LauncherFeedback.confirm(view, feedback.haptics, feedback.sounds)
                    currentClick()
                },
                onLongPress = {
                    LauncherFeedback.confirm(view, feedback.haptics, feedback.sounds)
                    currentLongClick?.invoke()
                },
            )
        }
        .onKeyEvent { event ->
            if (!enabled || !focused) return@onKeyEvent false
            handleLauncherItemKey(event, onClick = {
                LauncherFeedback.confirm(view, feedback.haptics, feedback.sounds)
                currentClick()
            }, onOptions = {
                LauncherFeedback.confirm(view, feedback.haptics, feedback.sounds)
                currentLongClick?.invoke()
            })
        }
}

/**
 * Tratamento de teclas por item: A confirma; Y abre as opções (se houver).
 * Retorna true quando a tecla foi consumida.
 */
fun handleLauncherItemKey(event: KeyEvent, onClick: () -> Unit, onOptions: (() -> Unit)?): Boolean {
    val key = event.key
    return when {
        event.type == KeyEventType.KeyUp && key in CONFIRM_KEYS -> {
            onClick()
            true
        }
        onOptions != null && event.type == KeyEventType.KeyUp && key == OPTIONS_KEY -> {
            onOptions()
            true
        }
        else -> false
    }
}
