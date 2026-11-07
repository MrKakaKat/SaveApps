package com.savetube.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class History(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<HistoryEntry> {
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(
                id = o.getString("id"),
                url = o.getString("url"),
                thumbnail = o.getString("thumb"),
                title = o.optString("title"),
                uploader = o.optString("uploader"),
            )
        }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> {
        val items = (listOf(entry) + load().filterNot { it.id == entry.id }).take(MAX)
        save(items)
        return items
    }

    fun clear() = save(emptyList())

    private fun save(items: List<HistoryEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("url", it.url).put("thumb", it.thumbnail)
                    .put("title", it.title).put("uploader", it.uploader)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private companion object {
        const val MAX = 200
    }
}
