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
    val totalPages: Int,
    // 记录保存进度时所用的阅读模式，续读时据此正确解释 position（翻页=页码 / 滚动=百分比）
    val scrollMode: Boolean = false
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
                        totalPages = o.optInt("total", 0),
                        scrollMode = o.optBoolean("scroll", false)
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

    /** 在历史或书架里按 url/bookKey 命中一项，优先历史（最近阅读的有最新进度）。*/
    fun findByUrl(url: String): HistoryItem? {
        val key = HistoryItem.keyOf(url)
        return getHistory().firstOrNull { it.url == url || it.bookKey == key }
            ?: getShelf().firstOrNull { it.url == url || it.bookKey == key }
    }

    // ===== 书架 / 收藏（独立于阅读历史，永久保留，无数量上限） =====
    fun getShelf(): List<HistoryItem> {
        val raw = prefs.getString(KEY_SHELF, null) ?: return emptyList()
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
                        totalPages = o.optInt("total", 0),
                        scrollMode = o.optBoolean("scroll", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToShelf(item: HistoryItem) {
        val list = getShelf().toMutableList()
        val idx = list.indexOfFirst { it.bookKey == item.bookKey }
        if (idx >= 0) list.removeAt(idx)
        list.add(0, item)
        saveShelf(list)
    }

    fun removeFromShelf(bookKey: String) {
        val list = getShelf().toMutableList()
        list.removeAll { it.bookKey == bookKey }
        saveShelf(list)
    }

    fun isInShelf(bookKey: String): Boolean =
        getShelf().any { it.bookKey == bookKey }

    // 保持书架顺序（按加入时间），仅更新进度，不置顶
    fun updateShelfProgress(bookKey: String, position: Int, totalPages: Int, nextChapterUrl: String?, scrollMode: Boolean = false) {
        val list = getShelf().toMutableList()
        val idx = list.indexOfFirst { it.bookKey == bookKey }
        if (idx < 0) return
        list[idx] = list[idx].copy(position = position, totalPages = totalPages, nextChapterUrl = nextChapterUrl, scrollMode = scrollMode)
        saveShelf(list)
    }

    fun clearShelf() {
        prefs.edit().remove(KEY_SHELF).apply()
    }

    private fun saveShelf(list: List<HistoryItem>) {
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
                    put("scroll", item.scrollMode)
                }
                arr.put(o)
            }
            prefs.edit().putString(KEY_SHELF, arr.toString()).apply()
        } catch (e: Exception) {
            // 序列化失败则忽略
        }
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
                    put("scroll", item.scrollMode)
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
        private const val KEY_SHELF = "shelf_list"
    }
}
