package com.example.novelreader

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * 目录（章节列表）持久化缓存。
 *
 * 为什么需要它：目录是「每次打开书都要付一次的网络爬取」—— 从书架/历史进入时
 * 至少要 1~2 个 RTT 才拿到首页，多页目录还要再并发抓几页。缓存只存在内存里，
 * 进程一退（或换个 Activity 打开同一本书）就没了，于是用户每次点「目录」看到的
 * 都是转圈。把解析好的目录落盘之后，第二次起打开目录就是**读盘直出**（约 2~5ms），
 * 网络只在后台做一次静默校验/补全 —— 这才是真正的秒开。
 *
 * 存储格式刻意用行式而不是 JSON：几千条章节的 JSON 反序列化要几十毫秒，
 * 行式只需按 `\t` 切一刀，几千行也就几毫秒。
 *
 *   filesDir/toc_cache/{tocKey 的 md5}.toc
 *     行 1（元信息）：V1 \t savedAt \t complete \t bookKey \t catalogUrl
 *     行 2..n      ：标题 \t URL
 */
object TocStore {

    // V2：meta 行新增「断点续抓」字段；同时让 V1 那种「只抓到首页却标成完整」的坏缓存
    // 自动失效（读到不认识的版本就当没有缓存，重新抓一次）。
    private const val VERSION = "V7"
    private const val DIR_NAME = "toc_cache"
    private const val MAX_BOOKS = 40
    private const val MAX_ITEMS = 20_000
    /** 目录超过这个时间就认为「旧了」：先渲染缓存，再在后台静默刷新（stale-while-revalidate）。 */
    private const val FRESH_MS = 6 * 60 * 60 * 1000L
    /**
     * 「只抓了一部分」的目录的有效期。
     *
     * 大书目录动辄上百页（移动站常见每页 20 章），一次会话抓不完，必须分多次续抓。
     * 但也不能每次翻章都重爬一遍，所以给几分钟的静默期：期间直接用已有的部分，
     * 过了再接着往下补（从 [Entry.nextPageUrl] 断点续抓）。
     */
    private const val PARTIAL_FRESH_MS = 5 * 60 * 1000L

    data class Entry(
        val bookKey: String,
        val items: List<UrlLoader.ChapterItem>,
        /** 真正解析出目录的那个页面（书页/目录页）。下次续抓可少一次 RTT。 */
        val catalogUrl: String?,
        val savedAt: Long,
        /** 是否是「整条目录」（false 表示还有分页没抓完）。 */
        val complete: Boolean,
        /** 断点续抓：下一个还没抓的分页地址；complete=true 时为 null。 */
        val nextPageUrl: String? = null
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean {
            val age = now - savedAt
            if (age < 0) return true
            return age < if (complete) FRESH_MS else PARTIAL_FRESH_MS
        }
    }

    // 进程级内存缓存：Activity 重建、书架↔阅读页来回切换时省掉读盘
    private val memory = object : LinkedHashMap<String, Entry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 6
    }

    // ===== 对外 API =====

    /** 读取目录缓存（内存 → 磁盘）。调用方需在 IO 线程调用。 */
    @Synchronized
    fun load(context: Context, url: String): Entry? {
        val key = tocKeyOf(url)
        if (key.isBlank()) return null
        memory[key]?.let { return it }
        val file = fileFor(context, key)
        val entry = if (file.exists()) read(file) else null
        if (entry != null) memory[key] = entry
        return entry
    }

    /**
     * 写入目录缓存。调用方需在 IO 线程调用。
     *
     * @param url 任意一个属于这本书的 URL（优先传第一章），key 由 [tocKeyOf] 统一推导，
     *   保证「从书页进入」和「从章节页进入」落到同一个缓存文件。
     * @param complete false 表示这只是首页那一段，不覆盖已有的完整目录。
     */
    @Synchronized
    fun save(
        context: Context,
        url: String,
        items: List<UrlLoader.ChapterItem>,
        catalogUrl: String?,
        complete: Boolean,
        nextPageUrl: String? = null
    ) {
        if (items.isEmpty()) return
        val key = tocKeyOf(url)
        if (key.isBlank()) return
        // 不拿「不完整」覆盖「完整」，也不拿更少的覆盖更多的：
        // 打开书时先落的是「首页 20 章」，若它把上次已经补到 1000 章的缓存冲掉，
        // 就等于每次打开都从零开始。
        if (!complete) {
            val old = memory[key] ?: fileFor(context, key).takeIf { it.exists() }?.let { read(it) }
            if (old != null && (old.complete || old.items.size >= items.size)) {
                android.util.Log.d("TocStore", "save skip key=$key old=${old.items.size} new=${items.size}")
                return
            }
        } else {
            // 反向保护：一次「完整」的抓取如果比已有目录还少一大截，多半是这次抓漏了
            // （断点/限流/站点改版），不能拿它覆盖掉更全的缓存。
            val old = memory[key] ?: fileFor(context, key).takeIf { it.exists() }?.let { read(it) }
            if (old != null && old.items.size > items.size && old.items.size - items.size > items.size / 5) {
                android.util.Log.d("TocStore", "save skip(complete shrink) key=$key old=${old.items.size} new=${items.size}")
                return
            }
        }
        val entry = Entry(
            bookKey = key,
            items = if (items.size > MAX_ITEMS) items.subList(0, MAX_ITEMS) else items,
            catalogUrl = catalogUrl?.takeIf { it.isNotBlank() },
            savedAt = System.currentTimeMillis(),
            complete = complete,
            nextPageUrl = if (complete) null else nextPageUrl?.takeIf { it.isNotBlank() }
        )
        memory[key] = entry
        val ok = runCatching { write(fileFor(context, key), entry) }.isSuccess
        android.util.Log.d(
            "TocStore",
            "save key=$key n=${entry.items.size} complete=$complete next=$nextPageUrl ok=$ok"
        )
        runCatching { trimBooks(context) }
    }

    /** 某本书的目录缓存是否为空/过期（供 UI 判断要不要后台刷新）。 */
    fun peek(context: Context, url: String): Entry? = runCatching { load(context, url) }.getOrNull()

    fun clearAll(context: Context) {
        memory.clear()
        runCatching { File(context.filesDir, DIR_NAME).deleteRecursively() }
    }

    /**
     * 删除某本书的目录缓存（内存 + 磁盘）。
     * 书被从书架/历史移除时调用，避免留下永远不会再读的孤儿目录缓存。
     */
    @Synchronized
    fun remove(context: Context, url: String) {
        val key = tocKeyOf(url)
        if (key.isBlank()) return
        memory.remove(key)
        runCatching { fileFor(context, key).delete() }
    }

    // ===== 缓存键 =====

    /**
     * 目录缓存的 key：`host/第1段/第2段`。
     *
     * 不能用 [HistoryItem.keyOf]（父目录）—— 有的站点把书放在「/read/12345_67890/」这种
     * 扁平结构里，父目录永远是 `/read`，全站的书会撞成同一个 key，目录就会串书。
     * 取「前两段路径」既能把同一本书的「书页 / 目录页 / 章节页」归到一起，又能区分不同书：
     *
     *   /156/156468/            → 156/156468   （书页）
     *   /156/156468/52087819.html → 156/156468 （章节页，同一个 key）
     *   /html/965388/102945727/  → html/965388
     */
    fun tocKeyOf(url: String): String {
        val raw = url.trim().substringBefore('#')
        if (raw.isBlank() || raw == "initial") return ""
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            return ""
        }
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        if (host.isBlank()) return ""
        val segs = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val head = when {
            segs.size >= 3 -> segs[0] + "/" + segs[1]
            segs.size == 2 -> segs[0] + "/" + stripPageExt(segs[1])
            segs.size == 1 -> stripPageExt(segs[0])
            else -> ""
        }
        return if (head.isBlank()) host else "$host/$head"
    }

    private fun stripPageExt(seg: String): String {
        val lower = seg.lowercase()
        for (ext in PAGE_EXTS) {
            if (lower.endsWith(ext) && lower.length > ext.length) return seg.dropLast(ext.length)
        }
        return seg
    }

    private val PAGE_EXTS = arrayOf(
        ".html", ".htm", ".shtml", ".php", ".asp", ".aspx", ".jsp", ".action", ".do"
    )

    // ===== 文件读写 =====

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), md5(key) + ".toc")

    private fun write(target: File, entry: Entry) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(VERSION)
            w.write("\t"); w.write(entry.savedAt.toString())
            w.write("\t"); w.write(if (entry.complete) "1" else "0")
            w.write("\t"); w.write(esc(entry.bookKey))
            w.write("\t"); w.write(esc(entry.catalogUrl ?: ""))
            w.write("\t"); w.write(esc(entry.nextPageUrl ?: ""))
            w.write("\n")
            for (item in entry.items) {
                w.write(esc(item.title))
                w.write("\t")
                w.write(esc(item.url))
                w.write("\n")
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun read(file: File): Entry? = try {
        file.bufferedReader(Charsets.UTF_8).use { r ->
            val head = r.readLine()
                ?: return null
            val meta = head.split('\t')
            if (meta.size < 3 || meta[0] != VERSION) return null
            val savedAt = meta[1].toLongOrNull() ?: return null
            val complete = meta[2] == "1"
            val key = meta.getOrElse(3) { "" }
            val catalog = meta.getOrElse(4) { "" }.takeIf { it.isNotBlank() }
            val nextPage = meta.getOrElse(5) { "" }.takeIf { it.isNotBlank() }
            val items = ArrayList<UrlLoader.ChapterItem>(512)
            while (items.size < MAX_ITEMS) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val u = line.substring(tab + 1)
                if (u.isBlank()) continue
                items.add(UrlLoader.ChapterItem(line.substring(0, tab), u))
            }
            if (items.isEmpty()) null else Entry(key, items, catalog, savedAt, complete, nextPage)
        }
    } catch (_: Exception) {
        null
    }

    /** 只保留最近使用的 [MAX_BOOKS] 本书的目录缓存。 */
    private fun trimBooks(context: Context) {
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".toc") } ?: return
        if (files.size <= MAX_BOOKS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_BOOKS)
            .forEach { runCatching { it.delete() } }
    }

    /** 行式格式的分隔符都在值里出现过时会把解析搞乱，统一替换成空格。 */
    private fun esc(s: String): String {
        if (s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
