package com.deivid22srk.portstore.launcher.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observa o estado dos gamepads no Launcher:
 * - [connected]: há um controle conectado agora (Xbox, PlayStation, 8BitDo,
 *   controles integrados de handhelds etc.);
 * - [lastInputGamepad]: o último tipo de entrada usado — alterna as dicas de
 *   botões do rodapé entre ícones de controle e dicas de toque.
 *
 * Não substitui a entrada em si (KeyEvent/MotionEvent tratados nos modifiers
 * de launcher/input); aqui é só o "sensor" de contexto para a UI.
 */
class GamepadMonitor(private val context: Context) {

    private val inputManager =
        context.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastInputGamepad = MutableStateFlow(false)
    val lastInputGamepad: StateFlow<Boolean> = _lastInputGamepad.asStateFlow()

    private var started = false

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            if (isGamepadDevice(InputDevice.getDevice(deviceId))) refresh()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            refresh()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        inputManager?.registerInputDeviceListener(listener, null)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        inputManager?.unregisterInputDeviceListener(listener)
    }

    /** Re-verifica os dispositivos e ajusta o modo de dicas. */
    fun refresh() {
        val has = hasGamepad()
        _connected.value = has
        // Controle acabou de conectar: ele passa a ser o padrão das dicas.
        // Sem controle: volta para dicas de toque.
        _lastInputGamepad.value = has
    }

    /** Chamado quando qualquer tecla/movimento de gamepad é detectado. */
    fun notifyGamepadInput() {
        if (!_lastInputGamepad.value) _lastInputGamepad.value = true
    }

    /** Chamado quando um toque na tela é detectado. */
    fun notifyTouchInput() {
        if (_lastInputGamepad.value) _lastInputGamepad.value = false
    }

    private fun hasGamepad(): Boolean =
        InputDevice.getDeviceIds().any { id -> isGamepadDevice(InputDevice.getDevice(id)) }

    private fun isGamepadDevice(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val dpad = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        return gamepad || joystick || dpad
    }
}
