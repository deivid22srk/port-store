package com.deivid22srk.portstore.virtual

import android.content.Context
import org.json.JSONObject

/**
 * Registro local de jogos virtuais: gameId do catálogo -> pacote virtual +
 * data de instalação + tamanho do APK. Fica em SharedPreferences (leitura
 * síncrona e barata, sem depender do motor estar pronto).
 */
class VirtualRegistry(context: Context) {

    private val prefs =
        context.getSharedPreferences("virtual_registry", Context.MODE_PRIVATE)

    data class Entry(
        val gameId: String,
        val packageName: String,
        val installedAt: Long,
        val apkSizeBytes: Long,
    )

    @Synchronized
    fun register(gameId: String, packageName: String, apkSizeBytes: Long) {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        json.put(
            packageName,
            JSONObject()
                .put("gameId", gameId)
                .put("installedAt", System.currentTimeMillis())
                .put("apkSizeBytes", apkSizeBytes),
        )
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun remove(packageName: String) {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        if (json.has(packageName)) {
            json.remove(packageName)
            prefs.edit().putString(KEY, json.toString()).apply()
        }
    }

    fun entry(packageName: String): Entry? {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        val obj = json.optJSONObject(packageName) ?: return null
        return Entry(
            gameId = obj.optString("gameId"),
            packageName = packageName,
            installedAt = obj.optLong("installedAt", 0L),
            apkSizeBytes = obj.optLong("apkSizeBytes", 0L),
        )
    }

    fun gameIdFor(packageName: String): String? = entry(packageName)?.gameId

    fun packageNameFor(gameId: String): String? {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        val keys = json.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val obj = json.optJSONObject(pkg) ?: continue
            if (obj.optString("gameId") == gameId) return pkg
        }
        return null
    }

    private companion object {
        const val KEY = "entries"
    }
}
