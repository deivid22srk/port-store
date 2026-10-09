package com.deivid22srk.portstore.util

/**
 * Utilitário para links do YouTube.
 *
 * Extrai o videoId a partir de URLs nos formatos mais comuns do catálogo
 * (watch?v=, youtu.be, embed, shorts, live e qualquer URL com parâmetro ?v=)
 * ou aceita um ID puro de 11 caracteres, como o campo `videoId` do games.json.
 */
object YouTubeLinks {

    private val idPatterns = listOf(
        Regex("""(?:youtube\.com/watch\?v=)([\w-]{11})"""),
        Regex("""(?:youtu\.be/)([\w-]{11})"""),
        Regex("""(?:youtube\.com/embed/)([\w-]{11})"""),
        Regex("""(?:youtube\.com/shorts/)([\w-]{11})"""),
        Regex("""(?:youtube\.com/live/)([\w-]{11})"""),
    )

    /** ID puro do YouTube: 11 caracteres de [A-Za-z0-9_-]. */
    private val bareId = Regex("""^[\w-]{11}$""")

    /** Parâmetro ?v= em qualquer domínio (ex.: m.youtube.com/watch?v=ID&t=30s). */
    private val vParam = Regex("""[?&]v=([\w-]{11})""")

    /** Extrai o videoId de uma URL (ou de um ID puro). Retorna null se não reconhecer. */
    fun extractVideoId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (bareId.matches(value)) return value
        idPatterns.firstNotNullOfOrNull { it.find(value)?.groupValues?.get(1) }?.let { return it }
        return vParam.find(value)?.groupValues?.get(1)
    }

    /**
     * Verifica se a string já é um videoId válido (11 caracteres de
     * [A-Za-z0-9_-], sem parâmetros extras). Usada como guarda antes de
     * entregar o ID ao player — um ID malformado nunca deve chegar ao WebView.
     */
    fun isValidVideoId(id: String?): Boolean {
        val value = id?.trim().orEmpty()
        return bareId.matches(value)
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun thumbUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
