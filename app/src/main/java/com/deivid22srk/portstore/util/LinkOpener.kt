package com.deivid22srk.portstore.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Abre links externos (YouTube, Telegram, GitHub, ports web) no navegador. */
object LinkOpener {

    fun open(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!ok) Toast.makeText(context, "Nenhum navegador encontrado", Toast.LENGTH_SHORT).show()
    }

    fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "Compartilhar").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
