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
    val scrollMode: Boolean = false,
    // 当前所在章节标题，用于历史/书架列表展示"读到哪一章"（否则列表只显示书名，换章后看起来"没更新"）
    val chapterTitle: String = "",
    // 本地书续读的章节索引（本地书没有在线 URL，用本地缓存文件 + 章节下标续读）；网络书恒为 -1
    val localChapter: Int = -1
) {
    companion object {
        // 从章节 URL 提取小说标识：取文件所在的目录路径，同一本小说不同章节共享。
        // 注意：必须先去尾部斜杠 —— 「…/965388/102945727/」这种目录式章节链接若不去斜杠，
        // lastIndexOf('/') 会落在结尾斜杠上，算出来还是章节自己的路径，同一本书就会越读越多条。
        fun keyOf(url: String): String {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            val lastSlash = clean.lastIndexOf('/')
            if (lastSlash <= 7) return clean  // 没有更深的目录则整条作为 key
            return clean.substring(0, lastSlash)
        }

        /**
         * 同一本书的「规范去重键」。
         *
         * 旧版本把**章节 URL** 存成了 bookKey（…/html/965388/102945725），新版本存的是
         * 书目录（…/html/965388），两者永远匹配不上 → 同一本书在历史里越翻越多。
         * 这里统一按「章节 URL 去掉文件名」的书目录归并：
         *  - 在线书且 URL 末段像章节（带扩展名 / 纯数字长号）→ 取上级目录；
         *  - 末段像书籍 id（如 /book/123）或本身就是书页 → 保留 bookKey，避免把相邻两本书并成一本；
         *  - 本地书用文件路径本身（唯一标识一本本地书）。
         */
        fun canonicalKey(url: String, bookKey: String): String {
            if (url.isBlank()) return bookKey
            if (!url.contains("://")) return bookKey.ifBlank { url }
            val last = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            val chapterLike = last.contains('.') || (last.length >= 6 && last.all { it.isDigit() })
            return if (chapterLike) keyOf(url) else bookKey.ifBlank { url }
        }

        /** 两个 bookKey 是否指向同一本书：相等，或互为上下级目录（兼容旧数据的章节级 key）。 */
        fun isSameBook(a: String, b: String): Boolean {
            val x = a.trimEnd('/')
            val y = b.trimEnd('/')
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            return x.startsWith("$y/") || y.startsWith("$x/")
        }
    }
}

class HistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading_history", Context.MODE_PRIVATE)

    fun getHistory(): List<HistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = try {
            val arr = JSONArray(raw)
            val parsed = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                parsed.add(
                    HistoryItem(
                        title = o.getString("title"),
                        url = o.getString("url"),
                        bookKey = o.optString("bookKey", HistoryItem.keyOf(o.getString("url"))),
                        nextChapterUrl = if (!o.isNull("next")) o.getString("next") else null,
                        position = o.optInt("position", 0),
                        totalPages = o.optInt("total", 0),
                        scrollMode = o.optBoolean("scroll", false),
                        chapterTitle = o.optString("chapter", ""),
                        localChapter = o.optInt("lc", -1)
                    )
                )
            }
            parsed
        } catch (e: Exception) {
            return emptyList()
        }
        val deduped = dedupe(list)
        // 旧数据里的重复项顺手落盘清掉（只在真的去重过时写一次，不会每次读都写盘）
        if (deduped.size != list.size) save(deduped)
        return deduped
    }

    /**
     * 同一本书只留一条（保留最靠前 = 最近阅读的那条）。
     * 归并依据是 [HistoryItem.canonicalKey]，能兼容「bookKey 存成章节 URL」的旧数据。
     */
    private fun dedupe(list: List<HistoryItem>): List<HistoryItem> {
        if (list.size <= 1) return list
        val out = ArrayList<HistoryItem>(list.size)
        for (item in list) {
            val key = HistoryItem.canonicalKey(item.url, item.bookKey)
            val dup = out.any {
                HistoryItem.canonicalKey(it.url, it.bookKey) == key ||
                    HistoryItem.isSameBook(it.bookKey, item.bookKey)
            }
            if (!dup) out.add(item)
        }
        return out
    }

    fun addOrUpdate(item: HistoryItem, maxItems: Int = 10) {
        val list = getHistory().toMutableList()
        // 同一本小说只保留一项：按规范键去重（旧数据 bookKey 是章节 URL 也能命中），
        // 再兜一层目录包含关系，避免「章节 key / 书目录 key」两种写法并存时又长出重复项。
        val key = HistoryItem.canonicalKey(item.url, item.bookKey)
        list.removeAll {
            HistoryItem.canonicalKey(it.url, it.bookKey) == key ||
                HistoryItem.isSameBook(it.bookKey, item.bookKey)
        }
        list.add(0, item)
        while (list.size > maxItems) list.removeAt(list.size - 1)
        save(list)
    }

    fun remove(url: String) {
        val list = getHistory().toMutableList()
        val key = HistoryItem.canonicalKey(url, HistoryItem.keyOf(url))
        list.removeAll {
            it.url == url || HistoryItem.canonicalKey(it.url, it.bookKey) == key ||
                HistoryItem.isSameBook(it.bookKey, HistoryItem.keyOf(url))
        }
        save(list)
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).commit()
    }

    /** 在历史或书架里按 url/bookKey 命中一项，优先历史（最近阅读的有最新进度）。*/
    fun findByUrl(url: String): HistoryItem? {
        val key = HistoryItem.keyOf(url)
        val canon = HistoryItem.canonicalKey(url, key)
        fun List<HistoryItem>.match(): HistoryItem? = firstOrNull {
            it.url == url || HistoryItem.canonicalKey(it.url, it.bookKey) == canon ||
                HistoryItem.isSameBook(it.bookKey, key)
        }
        return getHistory().match() ?: getShelf().match()
    }

    // ===== 书架 / 收藏（独立于阅读历史，永久保留，无数量上限） =====
    fun getShelf(): List<HistoryItem> {
        val raw = prefs.getString(KEY_SHELF, null) ?: return emptyList()
        val list = try {
            val arr = JSONArray(raw)
            val parsed = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                parsed.add(
                    HistoryItem(
                        title = o.getString("title"),
                        url = o.getString("url"),
                        bookKey = o.optString("bookKey", HistoryItem.keyOf(o.getString("url"))),
                        nextChapterUrl = if (!o.isNull("next")) o.getString("next") else null,
                        position = o.optInt("position", 0),
                        totalPages = o.optInt("total", 0),
                        scrollMode = o.optBoolean("scroll", false),
                        chapterTitle = o.optString("chapter", ""),
                        localChapter = o.optInt("lc", -1)
                    )
                )
            }
            parsed
        } catch (e: Exception) {
            return emptyList()
        }
        val deduped = dedupe(list)
        if (deduped.size != list.size) saveShelf(deduped)
        return deduped
    }

    fun addToShelf(item: HistoryItem) {
        val list = getShelf().toMutableList()
        val key = HistoryItem.canonicalKey(item.url, item.bookKey)
        list.removeAll {
            HistoryItem.canonicalKey(it.url, it.bookKey) == key ||
                HistoryItem.isSameBook(it.bookKey, item.bookKey)
        }
        list.add(0, item)
        saveShelf(list)
    }

    fun removeFromShelf(bookKey: String) {
        val list = getShelf().toMutableList()
        list.removeAll { it.bookKey == bookKey || it.url == bookKey || HistoryItem.isSameBook(it.bookKey, bookKey) }
        saveShelf(list)
    }

    fun isInShelf(bookKey: String): Boolean =
        getShelf().any { it.bookKey == bookKey || it.url == bookKey || HistoryItem.isSameBook(it.bookKey, bookKey) }

    // 保持书架顺序（按加入时间），仅更新进度，不置顶
    fun updateShelfProgress(bookKey: String, position: Int, totalPages: Int, nextChapterUrl: String?, scrollMode: Boolean = false, chapterTitle: String = "", localChapter: Int = -1) {
        val list = getShelf().toMutableList()
        val idx = list.indexOfFirst {
            it.bookKey == bookKey || it.url == bookKey || HistoryItem.isSameBook(it.bookKey, bookKey)
        }
        if (idx < 0) return
        // 本地书续读章节索引：传入有效下标才覆盖，否则保留既有值（避免把已存的章节清成 -1）。
        val lc = if (localChapter >= 0) localChapter else list[idx].localChapter
        list[idx] = list[idx].copy(position = position, totalPages = totalPages, nextChapterUrl = nextChapterUrl, scrollMode = scrollMode, chapterTitle = chapterTitle, localChapter = lc)
        saveShelf(list)
    }

    fun clearShelf() {
        prefs.edit().remove(KEY_SHELF).commit()
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
                    put("chapter", item.chapterTitle)
                    put("lc", item.localChapter)
                }
                arr.put(o)
            }
            // 用 commit() 而非 apply()：退出 app（进程被杀）时 apply 的异步写入可能来不及落盘，
            // 导致上次阅读位置丢失；commit 同步落盘可保证 onPause 保存的进度一定被持久化。
            prefs.edit().putString(KEY_SHELF, arr.toString()).commit()
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
                    put("chapter", item.chapterTitle)
                    put("lc", item.localChapter)
                }
                arr.put(o)
            }
            // 用 commit() 而非 apply()：退出 app（进程被杀）时 apply 的异步写入可能来不及落盘，
            // 导致上次阅读位置丢失；commit 同步落盘可保证 onPause 保存的进度一定被持久化。
            prefs.edit().putString(KEY_HISTORY, arr.toString()).commit()
        } catch (e: Exception) {
            // 序列化失败则忽略
        }
    }

    companion object {
        private const val KEY_HISTORY = "history_list"
        private const val KEY_SHELF = "shelf_list"
    }
}
