package com.example.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件下载记录：记录系统 DownloadManager 的任务 id、文件名与原始链接。
 * 下载管理页凭 dmId 实时查询状态（下载中/已完成/失败），并通过
 * DownloadManager 返回的 content URI 打开文件——全程无需存储权限。
 */
object DownloadRepo {

    data class Record(val dmId: Long, val fileName: String, val url: String, val addedAt: Long)

    private const val PREFS = "file_download_records"
    private const val KEY = "records"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(ctx: Context, record: Record) {
        val list = list(ctx).toMutableList()
        // 同一任务的重复记录、或同链接同名的历史记录只保留最新一条
        list.removeAll { it.dmId == record.dmId || (it.url == record.url && it.fileName == record.fileName) }
        list.add(0, record)
        persist(ctx, list)
    }

    fun list(ctx: Context): List<Record> {
        val arr = try {
            JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Record(
                dmId = o.optLong("id"),
                fileName = o.optString("name"),
                url = o.optString("url"),
                addedAt = o.optLong("at")
            )
        }
    }

    fun remove(ctx: Context, dmId: Long) {
        persist(ctx, list(ctx).filterNot { it.dmId == dmId })
    }

    private fun persist(ctx: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.dmId)
                    .put("name", it.fileName)
                    .put("url", it.url)
                    .put("at", it.addedAt)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
