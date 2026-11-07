package com.savetube.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The audio library: every downloaded track with its current tags and a cached cover. */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** A fresh file name each time, so image caches never show an outdated cover. */
    fun newCoverFile(id: String) =
        File(context.filesDir, "covers/${id}_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }

    fun load(): List<LibraryItem> {
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LibraryItem(
                id = o.getString("id"),
                uri = o.getString("uri"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                cover = o.optString("cover").ifEmpty { null },
                durationSec = o.optLong("duration"),
            )
        }
    }

    fun put(item: LibraryItem): List<LibraryItem> {
        val items = load().toMutableList()
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item else items.add(0, item)
        save(items)
        return items
    }

    fun remove(id: String): List<LibraryItem> {
        load().find { it.id == id }?.cover?.let { File(it).delete() }
        val items = load().filterNot { it.id == id }
        save(items)
        return items
    }

    private fun save(items: List<LibraryItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("uri", it.uri).put("title", it.title).put("artist", it.artist)
                    .put("cover", it.cover ?: "").put("duration", it.durationSec)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
