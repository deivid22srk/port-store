package com.deivid22srk.portstore.virtual.gex

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Detector do gesto "duas deslizadas rápidas da esquerda para a direita"
 * que abre o menu rápido lateral dentro de um jogo virtual.
 *
 * Princípios (Regra do recurso 3):
 *  - Observa os toques SEM consumi-los: o jogo continua recebendo tudo
 *    normalmente (o detector retorna false em qualquer evento que não seja
 *    o disparo do gesto);
 *  - Cada deslizada precisa COMEÇAR na faixa esquerda da tela (região
 *    configurável) para reduzir falsos positivos;
 *  - Todos os parâmetros ficam aqui, num único lugar.
 *
 * Consumo: apenas no instante em que o gesto dispara (fim da 2ª deslizada) —
 * aí o chamador envia ACTION_CANCEL para o jogo e abre o menu.
 *
 * @param onDoubleSwipe chamado uma única vez por reconhecimento.
 */
class DoubleSwipeDetector(private val onDoubleSwipe: () -> Unit) {

    companion object {
        /** Largura mínima de cada deslizada (fração da largura da tela). */
        const val SWIPE_MIN_DISTANCE_FRACTION = 0.26f

        /** Largura mínima absoluta de cada deslizada (dp) — o que for maior vale. */
        const val SWIPE_MIN_DISTANCE_DP = 120f

        /** Ângulo máximo de tolerância em relação ao eixo horizontal (graus). */
        const val SWIPE_MAX_ANGLE_DEG = 25f

        /** Velocidade horizontal mínima de cada deslizada (dp/s). */
        const val SWIPE_MIN_VELOCITY_DP = 900f

        /** Duração máxima de cada deslizada (ms) — precisa ser RÁPIDA. */
        const val SWIPE_MAX_DURATION_MS = 500L

        /** Intervalo máximo entre o fim da 1ª e o fim da 2ª deslizada (ms). */
        const val DOUBLE_SWIPE_WINDOW_MS = 700L

        /** Faixa esquerda da tela onde as deslizadas devem começar (fração). */
        const val START_REGION_FRACTION = 0.25f
    }

    // Estado da deslizada em andamento (só da 1ª ponta: DOWN -> UP).
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /** Instante (eventTime) do fim da última deslizada válida; 0 = nenhuma. */
    private var lastSwipeEnd = 0L

    /** Reinicia todo o estado (ex.: menu aberto/fechado, activity pausada). */
    fun reset() {
        tracking = false
        lastSwipeEnd = 0L
    }

    /**
     * Alimenta o detector. Retorna true SOMENTE quando o gesto completo é
     * reconhecido (evento consumido); em todo o resto retorna false — o jogo
     * nem percebe que o detector existe.
     */
    fun onTouchEvent(event: MotionEvent, screenWidthPx: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = event.x <= screenWidthPx * START_REGION_FRACTION
                downX = event.x
                downY = event.y
                downTime = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val duration = event.eventTime - downTime
                if (!qualifies(downX, downY, event.x, event.y, duration, screenWidthPx)) {
                    // Deslizada inválida quebra a sequência (evita encadeamentos lentos).
                    lastSwipeEnd = 0L
                    return false
                }
                val now = event.eventTime
                if (lastSwipeEnd > 0L && now - lastSwipeEnd <= DOUBLE_SWIPE_WINDOW_MS) {
                    lastSwipeEnd = 0L
                    onDoubleSwipe()
                    return true
                }
                lastSwipeEnd = now
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                lastSwipeEnd = 0L
            }
        }
        return false
    }

    private fun qualifies(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        screenWidthPx: Int,
    ): Boolean {
        if (durationMs <= 0 || durationMs > SWIPE_MAX_DURATION_MS) return false
        val dx = x2 - x1
        // Esquerda -> direita com deslocamento mínimo (fração da tela ou 120 dp).
        val density = max(1f, densityPx())
        val minDistance = max(screenWidthPx * SWIPE_MIN_DISTANCE_FRACTION, SWIPE_MIN_DISTANCE_DP * density)
        if (dx < minDistance) return false
        // Predominantemente horizontal (tolerância de ângulo limitada).
        val angleDeg = Math.toDegrees(atan2(abs(y2 - y1).toDouble(), abs(dx).toDouble()))
        if (angleDeg > SWIPE_MAX_ANGLE_DEG) return false
        // Rápida: velocidade horizontal mínima.
        val velocityPxPerSec = dx / (durationMs / 1000f)
        if (velocityPxPerSec < SWIPE_MIN_VELOCITY_DP * density) return false
        return true
    }

    /** Densidade em px/dp, lida por evento (barato; cacheia na primeira chamada). */
    private fun densityPx(): Float {
        cachedDensity?.let { return it }
        val d = try {
            android.content.res.Resources.getSystem().displayMetrics.density
        } catch (_: Throwable) {
            2f
        }
        cachedDensity = d
        return d
    }

    @Volatile
    private var cachedDensity: Float? = null
}
