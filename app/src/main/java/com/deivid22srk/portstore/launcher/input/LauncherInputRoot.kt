package com.deivid22srk.portstore.launcher.input

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Navegação por gamepad para o Game Launcher (prioridade máxima do modo).
 *
 * Aplicado na RAIZ de cada tela/janela, intercepta em fase de preview:
 * - D-pad e analógico esquerdo: move o foco (com repetição ao segurar);
 * - B: voltar ([onBack]) — nulo em diálogos que tratam B por conta própria;
 * - L1/R1: troca de seção ([onTabSwitch] com -1/+1);
 * - Start: menu rápido ([onStart]);
 * - X: pesquisa ([onSearch]);
 * - A/Y NÃO são tratados aqui: cada item focado reage via [launcherFocusable].
 *
 * O analógico é lido por um listener de [MotionEvent] na View raiz, com zona
 * morta ([STICK_DEAD_ZONE]) para evitar movimentos acidentais. D-pads físicos
 * chegam como KeyEvent (KEYCODE_DPAD_*), já suportados nativamente.
 *
 * Sem callbacks (padrão), funciona apenas como navegação de foco — é o modo
 * usado dentro de diálogos/menus.
 */
fun Modifier.launcherGamepadNav(
    monitor: GamepadMonitor,
    onBack: (() -> Unit)? = null,
    onTabSwitch: ((Int) -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnTabSwitch by rememberUpdatedState(onTabSwitch)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnSearch by rememberUpdatedState(onSearch)

    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var stickDirection by remember { mutableStateOf<FocusDirection?>(null) }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    /** Repetição ao segurar uma direção do D-pad. */
    fun startKeyRepeat(direction: FocusDirection) {
        stopRepeat()
        repeatJob = scope.launch {
            delay(KEY_REPEAT_INITIAL_DELAY_MS)
            while (true) {
                focusManager.moveFocus(direction)
                delay(KEY_REPEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Analógico: um único elemento focado se move enquanto o stick ficar
     * além da zona morta; reinicia quando a direção muda; para ao soltar.
     */
    fun updateStick(direction: FocusDirection?) {
        if (direction == stickDirection) return
        stickDirection = direction
        stopRepeat()
        if (direction != null) {
            focusManager.moveFocus(direction)
            repeatJob = scope.launch {
                delay(STICK_REPEAT_INITIAL_DELAY_MS)
                while (stickDirection == direction) {
                    focusManager.moveFocus(direction)
                    delay(KEY_REPEAT_INTERVAL_MS)
                }
            }
        }
    }

    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val motionListener = View.OnGenericMotionListener { _, event ->
            val isJoystick =
                event.source and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK
            if (event.action != MotionEvent.ACTION_MOVE || !isJoystick) {
                return@OnGenericMotionListener false
            }
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val dx = if (abs(x) > STICK_DEAD_ZONE) x else 0f
            val dy = if (abs(y) > STICK_DEAD_ZONE) y else 0f
            val direction = when {
                abs(dx) >= abs(dy) && dx > 0f -> FocusDirection.Right
                abs(dx) >= abs(dy) && dx < 0f -> FocusDirection.Left
                dy > 0f -> FocusDirection.Down
                dy < 0f -> FocusDirection.Up
                else -> null
            }
            monitor.notifyGamepadInput()
            updateStick(direction)
            true
        }
        view.setOnGenericMotionListener(motionListener)
        onDispose {
            view.setOnGenericMotionListener(null)
            stopRepeat()
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stopRepeat() }
    }

    onPreviewKeyEvent { event ->
        val key = event.key
        val isDown = event.type == KeyEventType.KeyDown
        val isUp = event.type == KeyEventType.KeyUp

        when {
            // Repetições do sistema são consumidas: o Launcher repete por conta própria.
            isDown && NAV_KEYS.containsKey(key) && event.nativeKeyEvent.repeatCount > 0 -> true

            isDown && NAV_KEYS.containsKey(key) -> {
                monitor.notifyGamepadInput()
                val direction = NAV_KEYS.getValue(key)
                stopRepeat()
                focusManager.moveFocus(direction)
                startKeyRepeat(direction)
                true
            }

            isUp && NAV_KEYS.containsKey(key) -> {
                stopRepeat()
                true
            }

            isDown && key == Key.ButtonB -> {
                val callback = currentOnBack
                if (callback != null && event.nativeKeyEvent.repeatCount == 0) {
                    monitor.notifyGamepadInput()
                    callback()
                    true
                } else {
                    false
                }
            }

            isDown && key == Key.ButtonX -> {
                val callback = currentOnSearch
                if (callback != null && event.nativeKeyEvent.repeatCount == 0) {
                    monitor.notifyGamepadInput()
                    callback()
                    true
                } else {
                    false
                }
            }

            isDown && key == Key.ButtonStart -> {
                val callback = currentOnStart
                if (callback != null && event.nativeKeyEvent.repeatCount == 0) {
                    monitor.notifyGamepadInput()
                    callback()
                    true
                } else {
                    false
                }
            }

            isDown && (key == Key.ButtonL1 || key == Key.ButtonR1) -> {
                val callback = currentOnTabSwitch
                if (callback != null && event.nativeKeyEvent.repeatCount == 0) {
                    monitor.notifyGamepadInput()
                    callback(if (key == Key.ButtonL1) -1 else 1)
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }
}

/** D-pads físicos (todas as fontes): mapeamento direto para direções de foco. */
private val NAV_KEYS: Map<Key, FocusDirection> = mapOf(
    Key.DirectionUp to FocusDirection.Up,
    Key.DirectionDown to FocusDirection.Down,
    Key.DirectionLeft to FocusDirection.Left,
    Key.DirectionRight to FocusDirection.Right,
)

/** Zona morta do analógico esquerdo (evita entradas acidentais). */
private const val STICK_DEAD_ZONE = 0.45f

/** Atraso antes de repetir a navegação com o D-pad segurado. */
private const val KEY_REPEAT_INITIAL_DELAY_MS = 340L

/** Atraso antes de repetir a navegação com o analógico segurado. */
private const val STICK_REPEAT_INITIAL_DELAY_MS = 260L

/** Intervalo entre passos da repetição (D-pad e analógico). */
private const val KEY_REPEAT_INTERVAL_MS = 140L

/**
 * Foco inicial de uma tela do Launcher: garante que sempre exista exatamente
 * um elemento focado ao entrar (e que o foco nunca fique "perdido"). O pequeno
 * atraso espera a primeira composição das linhas/cardezes da tela.
 */
@Composable
fun LauncherInitialFocus(focusRequester: FocusRequester, key: Any? = null) {
    LaunchedEffect(key) {
        delay(160)
        runCatching { focusRequester.requestFocus() }
    }
}
