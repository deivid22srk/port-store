package com.deivid22srk.portstore.launcher.input

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Feedback sutil de navegação do Launcher: vibração leve e/ou beep curto.
 * Ambos podem ser desativados nas configurações do Launcher
 * ([com.deivid22srk.portstore.settings.AppSettings.launcherHaptics] / `launcherSounds`).
 *
 * A vibração usa o aparelho (performHapticFeedback) — funciona igualmente em
 * handhelds com controles integrados, que vibram junto ao gamepad interno.
 */
object LauncherFeedback {

    private var tone: ToneGenerator? = null

    /** Feedback ao mover o foco entre itens (bem discreto). */
    fun focusTick(view: View, haptics: Boolean, sounds: Boolean) {
        if (haptics) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
        if (sounds) playTone(ToneGenerator.TONE_PROP_BEEP2, 40)
    }

    /** Feedback ao confirmar uma ação (botão A / toque). */
    fun confirm(view: View, haptics: Boolean, sounds: Boolean) {
        if (haptics) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        if (sounds) playTone(ToneGenerator.TONE_PROP_ACK, 60)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        runCatching {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 30)
                .also { tone = it }
            generator.startTone(toneType, durationMs)
        }
    }
}
