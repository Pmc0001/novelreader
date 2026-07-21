package com.example.novelreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val title: String,
    val url: String,
    val bookKey: String,
    val nextChapterUrl: String?,
    val position: Int,
    val totalPages: Int
) {
    companion object {
        // 从章节 URL 提取小说标识：取文件所在的目录路径，同一本小说不同章节共享
        fun keyOf(url: String): String {
            val clean = url.substringBefore("?").substringBefore("#")
            val lastSlash = clean.lastIndexOf('/')
            if (lastSlash <= 7) return clean  // 没有更深的目录则整条作为 key
            return clean.substring(0, lastSlash)
        }
    }
}

class HistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading_history", Context.MODE_PRIVATE)

    fun getHistory(): List<HistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        title = o.getString("title"),
                        url = o.getString("url"),
                        bookKey = o.optString("bookKey", HistoryItem.keyOf(o.getString("url"))),
                        nextChapterUrl = if (!o.isNull("next")) o.getString("next") else null,
                        position = o.optInt("position", 0),
                        totalPages = o.optInt("total", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addOrUpdate(item: HistoryItem, maxItems: Int = 10) {
        val list = getHistory().toMutableList()
        // 同一本小说只保留一项：按 bookKey 去重
        val idx = list.indexOfFirst { it.bookKey == item.bookKey }
        if (idx >= 0) list.removeAt(idx)
        list.add(0, item)
        while (list.size > maxItems) list.removeAt(list.size - 1)
        save(list)
    }

    fun remove(url: String) {
        val list = getHistory().toMutableList()
        list.removeAll { it.url == url || it.bookKey == HistoryItem.keyOf(url) }
        save(list)
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            for (item in list) {
                val o = JSONObject().apply {
                    put("title", item.title)
                    put("url", item.url)
                    put("bookKey", item.bookKey)
                    put("next", item.nextChapterUrl ?: JSONObject.NULL)
                    put("position", item.position)
                    put("total", item.totalPages)
                }
                arr.put(o)
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (e: Exception) {
            // 序列化失败则忽略
        }
    }

    companion object {
        private const val KEY_HISTORY = "history_list"
    }
}
