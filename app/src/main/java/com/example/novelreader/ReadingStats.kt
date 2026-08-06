package com.example.novelreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 单日阅读统计 */
data class DayStat(
    var ms: Long = 0L,      // 阅读时长（毫秒）
    var chars: Int = 0,     // 累计阅读字数
    var chapters: Int = 0   // 翻阅章节数
)

/**
 * 阅读统计：按天持久化阅读时长 / 累计字数 / 章节数。
 * 数据存于独立 SharedPreferences（reading_stats），不污染阅读历史。
 */
object ReadingStats {

    private const val PREFS = "reading_stats"
    private const val KEY_DAILY = "daily"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun today(): String = dateFmt.format(Date())

    private fun mutate(date: String, block: (DayStat) -> Unit) {
        val all = loadAll().toMutableMap()
        val s = all[date] ?: DayStat()
        block(s)
        all[date] = s
        saveAll(all)
    }

    fun addReadingTime(ms: Long) {
        if (ms <= 0) return
        mutate(today()) { it.ms += ms }
    }

    fun addChars(n: Int) {
        if (n <= 0) return
        mutate(today()) { it.chars += n }
    }

    fun addChapter() {
        mutate(today()) { it.chapters += 1 }
    }

    fun getDay(date: String): DayStat = loadAll()[date] ?: DayStat()

    fun getToday(): DayStat = getDay(today())

    /** 最近 7 天（含今天），下标 0=6天前 … 6=今天 */
    fun getLast7Days(): List<Pair<String, DayStat>> {
        val cal = Calendar.getInstance()
        val list = mutableListOf<Pair<String, DayStat>>()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val d = dateFmt.format(cal.time)
            list.add(d to getDay(d))
        }
        return list
    }

    fun getTotal(): DayStat {
        val all = loadAll().values
        return DayStat(
            ms = all.sumOf { it.ms },
            chars = all.sumOf { it.chars },
            chapters = all.sumOf { it.chapters }
        )
    }

    private fun loadAll(): Map<String, DayStat> {
        val raw = prefs.getString(KEY_DAILY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, DayStat>()
            obj.keys().forEach { k ->
                val d = obj.getJSONObject(k)
                map[k] = DayStat(d.optLong("ms"), d.optInt("chars"), d.optInt("chapters"))
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAll(map: Map<String, DayStat>) {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, JSONObject().apply {
                put("ms", v.ms)
                put("chars", v.chars)
                put("chapters", v.chapters)
            })
        }
        prefs.edit().putString(KEY_DAILY, obj.toString()).apply()
    }

    /** 毫秒 → "Xh Ym" / "Ym" / "Ns" */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    /** 字数 → "1.2万字" / "1234字" */
    fun formatChars(n: Int): String = when {
        n >= 10000 -> String.format(Locale.getDefault(), "%.1f万字", n / 10000f)
        else -> "${n}字"
    }
}
