package com.example.novelreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书源管理：内置若干通用书源 + 用户自定义书源（持久化）。
 * 内置源按文献记录的真实搜索格式填写（笔趣阁系多为 modules/article/search.php?searchkey= 且 GBK 编码），
 * 并保留多个候选兜底；经联网核实部分域名已失效，需真机复测，故务必保留「添加自定义书源」入口。
 */
object SourceManager {

    private const val PREFS = "book_sources"
    private const val KEY_CUSTOM = "custom"

    // 内置书源（2026-08-04 复核：原内置地址多数已失效/改为 JS 渲染空壳，已替换为实测可用/高可用的端点）。
    // sudugu 已用真实搜索页验证为服务端渲染；bookszw 为设备侧已验证可用；其余为常见格式+多候选兜底。
    // 盗版站域名/搜索结构常变动，若某源无结果，请在「换源」面板添加自定义书源（粘贴带 {q} 的搜索地址）。
    private val BUILTIN = listOf(
        BookSource(
            "sudugu", "速读谷(sudugu)",
            listOf(
                "https://www.sudugu.org/i/sor.aspx?key={q}"
            ),
            "utf-8", true
        ),
        BookSource(
            "bookszw", "书神网(bookszw)",
            listOf(
                "https://www.bookszw.com/search?q={q}",
                "https://www.bookszw.com/s?q={q}"
            ),
            "utf-8", true
        ),
        BookSource(
            "bqg52", "笔趣阁(52bqg)",
            listOf(
                "https://m.52bqg.com/search.html?searchkey={q}",
                "https://www.52bqg.com/modules/article/search.php?searchkey={q}"
            ),
            "gbk", true
        ),
        BookSource(
            "biquge", "笔趣阁(biquge)",
            listOf(
                "https://www.biquge.la/modules/article/search.php?searchkey={q}",
                "https://www.biquge.com.tw/search?q={q}"
            ),
            "gbk", true
        ),
        BookSource(
            "booktxt", "顶点小说(booktxt)",
            listOf(
                "https://www.booktxt.net/modules/article/search.php?searchkey={q}",
                "https://so.biqusoso.com/s1.php?ie=utf-8&siteid=booktxt.net&q={q}"
            ),
            "utf-8", true
        ),
        BookSource(
            "bq69", "69书吧(69shu)",
            listOf(
                "https://www.69shu.com/search?q={q}",
                "https://m.69shu.com/search?q={q}"
            ),
            "utf-8", true
        )
    )

    fun builtin(): List<BookSource> = BUILTIN

    fun custom(context: Context): List<BookSource> {
        val prefs = prefsOf(context)
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<BookSource>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // 兼容旧版本：优先读 searchUrls 数组，否则退回单个 searchUrl 字符串
                val urls = if (o.has("searchUrls")) {
                    val a = o.getJSONArray("searchUrls")
                    val tmp = mutableListOf<String>()
                    for (j in 0 until a.length()) tmp.add(a.getString(j))
                    tmp
                } else {
                    listOf(o.getString("searchUrl"))
                }
                list.add(
                    BookSource(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        searchUrls = urls,
                        encoding = o.optString("encoding", "utf-8"),
                        builtin = false
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun all(context: Context): List<BookSource> = BUILTIN + custom(context)

    fun addCustom(context: Context, source: BookSource) {
        val list = custom(context).toMutableList()
        list.removeAll { it.id == source.id }
        list.add(source)
        saveCustom(context, list)
    }

    fun removeCustom(context: Context, id: String) {
        saveCustom(context, custom(context).filter { it.id != id })
    }

    private fun saveCustom(context: Context, list: List<BookSource>) {
        val arr = JSONArray()
        list.forEach {
            val urls = JSONArray()
            it.searchUrls.forEach { u -> urls.put(u) }
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("searchUrls", urls)
                put("encoding", it.encoding)
            })
        }
        prefsOf(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun prefsOf(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
