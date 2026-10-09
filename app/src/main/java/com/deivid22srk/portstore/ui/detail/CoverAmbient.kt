package com.deivid22srk.portstore.ui.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.deivid22srk.portstore.catalog.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FEATURE EXPERIMENTAL (branch feat/dynamic-cover-colors) — tema dinâmico pelas
 * cores da capa do jogo.
 *
 * Fluxo completo:
 *  1. [CoverColorExtractor] carrega a capa (e o topo do 1º screenshot) pelo Coil
 *     FORA da main thread, com bitmap reduzido (downsample) e sem configuração
 *     "hardware" — o Palette precisa ler os pixels (bitmap de software);
 *  2. extrai a cor dominante com AndroidX Palette na ordem de preferência
 *     vibrant -> darkVibrant -> muted -> dominant;
 *  3. normaliza a cor (saturação/brilho limitados; se clara demais, o alpha do
 *     pico é reduzido automaticamente) para manter a legibilidade do texto;
 *  4. [AmbientGlow] desenha o gradiente vertical ATRÁS do conteúdo: nasce no
 *     topo do carrossel de screenshots (pico de alpha) e esmaece até o topo da
 *     tela, com um fade curto logo abaixo do carrossel — sem faixas duras.
 *
 * Fallback: qualquer falha (sem capa, decode, rede) devolve [CoverColors.Fallback],
 * um neutro praticamente invisível sobre o fundo escuro — o visual de hoje.
 * As cores extraídas são cacheadas em memória por id do jogo (sem reextração
 * a cada abertura); a falha NÃO é cacheada, para reextrair quando a imagem
 * estiver disponível no cache de disco do Coil.
 */

/** Resultado da extração de cores de um jogo. */
data class CoverColors(
    /** Cor de acento extraída da capa (já normalizada para o glow). */
    val accent: Color,
    /** Cor da borda superior do 1º screenshot (conecta carrossel -> fundo). */
    val edgeAccent: Color,
    /** Alpha do pico do glow (aplicado no topo do carrossel). */
    val peakAlpha: Float,
    /** true quando nada foi extraído (fallback neutro = visual de hoje). */
    val isFallback: Boolean,
) {
    companion object {
        /** Neutro escuro: sobre o fundo #0F0F0F é praticamente imperceptível. */
        val Fallback = CoverColors(
            accent = Color(0xFF3A3A3A),
            edgeAccent = Color(0xFF3A3A3A),
            peakAlpha = 0.10f,
            isFallback = true,
        )
    }
}

/** Extração assíncrona das cores + cache em memória (por id do jogo). */
object CoverColorExtractor {

    private const val TAG = "PortStoreCoverColors"

    /** Pico do glow dentro da janela pedida no design (alpha 0,25–0,40). */
    private const val PEAK_ALPHA = 0.34f

    /** Pico reduzido quando a cor extraída é clara demais (legibilidade). */
    private const val PEAK_ALPHA_LIGHT = 0.25f

    /** Peso da cor amostrada do screenshot na cor final de "borda". */
    private const val EDGE_SHOT_WEIGHT = 0.45f

    /** Limite de entradas no cache (objetos minúsculos — 48 jogos é folgado). */
    private const val CACHE_LIMIT = 48

    private val cache = LruCache<String, CoverColors>(CACHE_LIMIT)

    /**
     * Extrai as cores do jogo fora da main thread, com cache por id.
     * Nunca lança: qualquer erro vira [CoverColors.Fallback].
     */
    suspend fun extract(context: Context, game: Game): CoverColors {
        cache.get(game.id)?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { extractBlocking(context.applicationContext, game) }
            .getOrElse { e ->
                Log.w(TAG, "extração falhou gameId=${game.id}: ${e.message}")
                CoverColors.Fallback
            }
        Log.d(
            TAG,
            "gameId=${game.id} accent=${result.accent.toHex()} edge=${result.edgeAccent.toHex()} " +
                "peak=${result.peakAlpha} fallback=${result.isFallback} " +
                "em=${SystemClock.elapsedRealtime() - started}ms",
        )
        if (!result.isFallback) cache.put(game.id, result)
        return result
    }

    private suspend fun extractBlocking(appContext: Context, game: Game): CoverColors =
        withContext(Dispatchers.Default) {
            val coverUrl = game.coverUrl
            if (coverUrl.isNullOrBlank()) return@withContext CoverColors.Fallback

            val cover = loadBitmap(appContext, coverUrl, width = 240, height = 320)
                ?: return@withContext CoverColors.Fallback

            val palette = Palette.from(cover).maximumColorCount(16).generate()
            val selected = selectSwatch(palette)
            if (selected == null) {
                Log.d(TAG, "sem swatches utilizáveis gameId=${game.id}")
                return@withContext CoverColors.Fallback
            }
            val (source, rgb) = selected
            val accent = normalizeColor(rgb)
            val peakAlpha = peakAlphaFor(accent)
            Log.d(TAG, "gameId=${game.id} swatch=$source ${accent.toHex()}")

            // Opcional e recomendado: amostra a borda superior do 1º screenshot
            // para conectar visualmente o carrossel ao gradiente (transição
            // contínua screenshot -> fundo). Qualquer falha aqui é silenciosa.
            val edgeAccent = game.screenshotUrls.firstOrNull()
                ?.let { loadBitmap(appContext, it, width = 160, height = 90) }
                ?.let { topEdgeAverageColor(it) }
                ?.let { blend(accent, normalizeColor(it), EDGE_SHOT_WEIGHT) }
                ?: accent

            CoverColors(accent = accent, edgeAccent = edgeAccent, peakAlpha = peakAlpha, isFallback = false)
        }

    /** Ordem de preferência definida no design: vibrant -> darkVibrant -> muted -> dominant. */
    private fun selectSwatch(palette: Palette): Pair<String, Int>? {
        val candidates = listOf(
            "vibrant" to palette.vibrantSwatch,
            "darkVibrant" to palette.darkVibrantSwatch,
            "muted" to palette.mutedSwatch,
            "dominant" to palette.dominantSwatch,
        )
        for ((name, swatch) in candidates) {
            if (swatch != null) return name to swatch.rgb
        }
        return null
    }

    /**
     * Normaliza a cor extraída para um glow "sutil e não forçado":
     * - saturação reduzida ~10% e teto de 0,85 (não compete com o texto);
     * - brilho limitado a [0,20; 0,62] — claro demais perde força, escuro
     *   demais não aparece sobre o fundo #0F0F0F.
     */
    private fun normalizeColor(rgb: Int): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.85f)
        hsv[2] = hsv[2].coerceIn(0.20f, 0.62f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Capa clara => pico mais baixo (legibilidade do texto branco por cima). */
    private fun peakAlphaFor(color: Color): Float {
        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(
            (color.red * 255f).toInt(),
            (color.green * 255f).toInt(),
            (color.blue * 255f).toInt(),
            hsl,
        )
        val lightness = hsl[2]
        return when {
            lightness <= 0.45f -> PEAK_ALPHA
            lightness >= 0.62f -> PEAK_ALPHA_LIGHT
            else -> PEAK_ALPHA_LIGHT + (PEAK_ALPHA - PEAK_ALPHA_LIGHT) * ((0.62f - lightness) / 0.17f)
        }
    }

    /** Média das cores da faixa superior do bitmap (ignora pixels transparentes). */
    private fun topEdgeAverageColor(bitmap: Bitmap, fraction: Float = 0.18f): Int? {
        val stripHeight = (bitmap.height * fraction).toInt().coerceIn(1, bitmap.height)
        val w = bitmap.width
        val pixels = IntArray(w * stripHeight)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, stripHeight)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (px in pixels) {
            if ((px ushr 24) < 32) continue
            r += (px shr 16) and 0xFF
            g += (px shr 8) and 0xFF
            b += px and 0xFF
            n++
        }
        if (n == 0L) return null
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun blend(a: Color, b: Color, weightB: Float): Color {
        val w = weightB.coerceIn(0f, 1f)
        return Color(
            red = a.red * (1f - w) + b.red * w,
            green = a.green * (1f - w) + b.green * w,
            blue = a.blue * (1f - w) + b.blue * w,
            alpha = 1f,
        )
    }

    /**
     * Baixa/decodifica a imagem pelo ImageLoader do app (reusa cache de disco,
     * entende SVG), em bitmap de software reduzido — pronto para o Palette.
     */
    private suspend fun loadBitmap(context: Context, url: String, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                // Palette precisa ler os pixels: bitmap de software, downsampled.
                .allowHardware(false)
                .size(width, height)
                .build()
            val drawable = runCatching { context.imageLoader.execute(request).drawable }.getOrNull()
            drawable?.let { toSoftwareBitmap(it) }
        }

    /**
     * Converte o drawable decodificado em bitmap de software.
     * Raster (png/jpg/webp) -> BitmapDrawable direto; capas .svg do catálogo
     * chegam como PictureDrawable e são renderizadas num ARGB_8888 com base
     * preta (casando com o fundo escuro do app antes do Palette).
     */
    private fun toSoftwareBitmap(drawable: Drawable): Bitmap? = runCatching {
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap?.takeIf { it.config != Bitmap.Config.HARDWARE }
            is PictureDrawable -> {
                val w = drawable.intrinsicWidth.coerceIn(1, 2048)
                val h = drawable.intrinsicHeight.coerceIn(1, 2048)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    drawable.picture?.draw(canvas)
                }
            }
            else -> null
        }
    }.getOrNull()

    private fun Color.toHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
}

/**
 * Glow ambiente: gradiente vertical desenhado ATRÁS do conteúdo da tela.
 *
 * - [carouselTopPx]: topo do carrossel de screenshots medido no 1º layout
 *   (< 0 = ainda não medido -> nada é desenhado);
 * - [overlapPx]: quanto o gradiente continua ABAIXO do topo do carrossel,
 *   esmaecendo até zero para não criar faixa dura atrás dos screenshots.
 *
 * Sem banding: 5 paradas intermediárias com curva de alpha suave.
 */
@Composable
fun AmbientGlow(
    accent: Color,
    edgeAccent: Color,
    peakAlpha: Float,
    carouselTopPx: Float,
    overlapPx: Float,
    modifier: Modifier = Modifier,
) {
    val targetHeight = if (carouselTopPx < 0f) 0f else (carouselTopPx + overlapPx).coerceAtLeast(1f)
    val height by animateFloatAsState(
        targetValue = targetHeight,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "ambientGlowHeight",
    )
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                if (height < 1f) return@drawBehind
                // Pico do glow no topo do carrossel; paradas proporcionais a ele.
                val peak = (carouselTopPx / height).coerceIn(0.35f, 0.95f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to accent.copy(alpha = 0f),
                            (peak * 0.40f) to accent.copy(alpha = peakAlpha * 0.14f),
                            (peak * 0.70f) to accent.copy(alpha = peakAlpha * 0.42f),
                            (peak * 0.92f) to accent.copy(alpha = peakAlpha * 0.78f),
                            peak to edgeAccent.copy(alpha = peakAlpha),
                            1.00f to Color.Transparent,
                        ),
                        startY = 0f,
                        endY = height,
                    ),
                    size = Size(size.width, height),
                )
            },
    )
}
