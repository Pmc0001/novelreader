package com.example.novelreader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 章节缓存数据结构，内存与磁盘共用 */
data class CachedChapter(
    val title: String,
    val content: String,
    val nextChapterUrl: String?
)

/**
 * 章节离线持久化缓存。
 * 以 [bookKey]（HistoryItem.keyOf 的目录层级）作为分区目录，
 * 每章以 URL MD5 作为文件名、JSON 格式落盘。
 *
 * 目录结构：
 *   filesDir/novel_cache/{bookKey}/
 *     {url_md5}.json
 */
class ChapterCache(private val context: Context, private val bookKey: String) {

    private val cacheDir: File
        get() = File(context.filesDir, "novel_cache/$bookKey").also {
            if (!it.exists()) it.mkdirs()
        }

    /** 单章的元信息（用于章节列表展示，不加载正文） */
    data class ChapterInfo(val title: String, val url: String)

    /** 列出本节已缓存的章（按 URL 自然排序，反映章节顺序） */
    fun listChapterInfos(): List<ChapterInfo> {
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { file ->
            try {
                val json = org.json.JSONObject(file.readText())
                ChapterInfo(
                    title = json.optString("title", "未知章节"),
                    url = json.optString("url", "")
                )
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.url }
    }

    // -- 序列化 / 反序列化 --

    /** 写入一章到磁盘缓存 */
    fun put(url: String, chapter: CachedChapter) {
        val json = JSONObject().apply {
            put("title", chapter.title)
            put("content", chapter.content)
            if (chapter.nextChapterUrl != null) {
                put("nextChapterUrl", chapter.nextChapterUrl)
            }
            put("url", url)
        }
        chapterFile(url).writeText(json.toString())
    }

    /** 从磁盘缓存读取一章，文件损坏或缺失返回 null */
    fun get(url: String): CachedChapter? {
        val file = chapterFile(url)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            CachedChapter(
                title = json.getString("title"),
                content = json.getString("content"),
                nextChapterUrl = json.optString("nextChapterUrl", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            // 损坏缓存：删除并忽略
            file.delete()
            null
        }
    }

    /** 判断一章是否已缓存 */
    fun contains(url: String): Boolean = chapterFile(url).exists()

    /** 本节已缓存章数 */
    val count: Int
        get() = cacheDir.listFiles()?.count { it.name.endsWith(".json") } ?: 0

    /** 本节缓存总大小（字节） */
    val totalSize: Long
        get() = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 删除本节所有缓存 */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.delete()
    }

    // -- 工具 --

    /** 从 URL 生成安全的文件名 */
    private fun chapterFile(url: String): File {
        val hash = urlHash(url)
        return File(cacheDir, "$hash.json")
    }

    private fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // -- 全局工具 --

    /** 单本书的缓存统计 */
    data class BookCacheStats(
        val bookKey: String,
        val chapterCount: Int,
        val totalSize: Long
    )

    companion object {
        /** 列出所有已缓存的书及其统计（递归遍历，处理 URL/bookKey 产生的嵌套子目录） */
        fun listCachedBooks(context: Context): List<BookCacheStats> {
            val root = File(context.filesDir, "novel_cache")
            if (!root.exists()) return emptyList()

            val map = linkedMapOf<String, Pair<Int, Long>>() // bookKey -> (count, totalSize)

            root.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    // 文件所在目录相对于 novel_cache 的路径即为有效 bookKey
                    val relParent = file.relativeTo(root).parent ?: return@forEach
                    val (c, s) = map.getOrDefault(relParent, 0 to 0L)
                    map[relParent] = (c + 1) to (s + file.length())
                }
            }

            return map.map { (key, stats) ->
                BookCacheStats(
                    bookKey = key,
                    chapterCount = stats.first,
                    totalSize = stats.second
                )
            }.sortedByDescending { it.totalSize }
        }

        /** 所有书缓存总量（字节） */
        fun totalCacheSize(context: Context): Long {
            val root = File(context.filesDir, "novel_cache")
            if (!root.exists()) return 0
            return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        /** 清空所有离线缓存 */
        fun clearAll(context: Context) {
            File(context.filesDir, "novel_cache").deleteRecursively()
        }
    }
}
