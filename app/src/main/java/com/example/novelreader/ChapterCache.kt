package com.example.novelreader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 章节缓存数据结构，内存与磁盘共用 */
data class CachedChapter(
    val title: String,
    val content: String,
    val nextChapterUrl: String?,
    // 章节页上解析出的「上一章」链接。旧缓存文件里没有这个字段，读出来是 null，兼容。
    val prevChapterUrl: String? = null,
    /**
     * 正文是否抓全。
     *
     * 章内分页（第1/3页、第2/3页…）如果只抓到前两页（页码翻到一半超时、某页被反爬拦了），
     * 正文就会少一页。这种「残章」**绝不能落盘** —— 一旦写进磁盘缓存，之后每次读都是
     * 这份缺页的正文，用户只会觉得"这章怎么短了一截"，而且再也不会重新请求。
     * 所以残章只在内存里给当前这次阅读用，不持久化；下次重新抓全的版本。
     */
    val complete: Boolean = true
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
            if (chapter.prevChapterUrl != null) {
                put("prevChapterUrl", chapter.prevChapterUrl)
            }
            // 只存完整的章节；万一有残章到这里，也要把标记写进去，读的时候能识别
            put("complete", chapter.complete)
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
                nextChapterUrl = json.optString("nextChapterUrl", "").takeIf { it.isNotBlank() },
                prevChapterUrl = json.optString("prevChapterUrl", "").takeIf { it.isNotBlank() },
                complete = json.optBoolean("complete", true)
            )
        } catch (_: Exception) {
            // 损坏缓存：删除并忽略
            file.delete()
            null
        }
    }

    /** 判断一章是否已缓存 */
    fun contains(url: String): Boolean = chapterFile(url).exists()

    /** 删除单章缓存（内容被判定为坏数据时用，删掉才能重新抓） */
    fun remove(url: String) {
        runCatching { chapterFile(url).delete() }
    }

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

    /**
     * 控制本节落盘的章节数，超出部分按「最久未更新」删除。
     *
     * 磁盘缓存是「加速层」不是「收藏夹」：阅读时每翻一章都会写一份 JSON，
     * 不设上限的话长期使用会无感涨到几十 MB。按 lastModified 淘汰即可 ——
     * 正在读的章节刚写过，永远是最新的那一批。
     */
    fun trimTo(maxFiles: Int) {
        if (maxFiles <= 0) return
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { runCatching { it.delete() } }
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

        /**
         * 全局磁盘上限。超出后按「最后修改时间」从旧到新删，直到降到上限的 80%。
         *
         * 必须要有这一层：单本 400 章只约束了「一本书」，用户读十几本书就是十几倍，
         * 长期用下去能涨到几百 MB。缓存是加速层，不该无限占用用户的存储。
         */
        fun enforceGlobalLimit(context: Context, maxBytes: Long) {
            if (maxBytes <= 0) return
            val root = File(context.filesDir, "novel_cache")
            if (!root.exists()) return
            val files = mutableListOf<File>()
            var total = 0L
            root.walkTopDown().forEach { f ->
                if (f.isFile && f.name.endsWith(".json")) {
                    total += f.length()
                    files.add(f)
                }
            }
            if (total <= maxBytes) return
            val target = (maxBytes * 8 / 10).coerceAtLeast(1)
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= target) break
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) total -= len
            }
        }

        /** 清空所有离线缓存 */
        fun clearAll(context: Context) {
            File(context.filesDir, "novel_cache").deleteRecursively()
        }
    }
}
