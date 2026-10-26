package com.example.novelreader

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * 本地小说文件解析：把 .txt / .epub / .umd 解析成纯文本，写入缓存文件后交给 ReadingActivity 打开。
 *
 * 设计要点：整本小说可能很大（数 MB），直接通过 Intent 传递文本会触发 Binder 的
 * TransactionTooLargeException。因此这里把解析结果写成缓存文件，只把「文件路径」传给阅读页，
 * 由阅读页自行读取，规避 IPC 体积限制。
 */

/**
 * 本地 txt 切分出的章节：标题 + 在整本纯文本中的字符起止偏移。
 * 阅读页据此生成目录，并按偏移切片出单章内容（无需任何网络请求）。
 */
data class LocalChapter(val title: String, val start: Int, val end: Int)

object LocalBookLoader {

    sealed class Result {
        data class Ok(val title: String, val filePath: String) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun load(context: Context, uri: Uri, displayName: String): Result = withContext(Dispatchers.IO) {
        try {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrDefault("") ?: ""
            val text = when {
                ext == "epub" || mime.contains("epub", true) -> readEpub(context, uri)
                ext == "umd" || mime.contains("umd", true) -> readUmd(context, uri)
                else -> readText(context, uri)
            }
            if (text.isBlank()) {
                return@withContext Result.Err("文件内容为空或无法识别，请确认是 .txt / .epub / .umd 文件")
            }
            // 用「书名 + 正文采样」做稳定文件名：同一本小说再次从文件管理器打开时复用同一缓存，
            // 既避免重复解析，又让书架里的本地书能据此稳定路径续读（缓存文件不再打开后删除）。
            val file = File(context.cacheDir, "localbook_${stableKey(displayName, text)}.txt")
            // 缓存文件按「内容稳定命名」，但同名文件不一定可信：writeText 不是原子操作，上次导入如果
            // 进程被杀/写盘中断，会留下「只有前半本」的残缺缓存；而仅凭 file.exists() 复用，就会让这本
            // 书永远少最后一截（读者看到的正是"字数少了"）。这里用同名 .len 侧车文件记录应有多少字，
            // 缺失/不一致就重写，且写入走「临时文件 + rename」保证原子性。
            val lenFile = File(file.parentFile, file.name + ".len")
            val expectLen = text.length.toString()
            val cachedOk = file.exists() && file.length() > 0 &&
                runCatching { lenFile.readText().trim() == expectLen }.getOrDefault(false)
            if (!cachedOk) {
                writeAtomic(file, text)
                runCatching { lenFile.writeText(expectLen) }
            }
            // 顺手把章节目录算好落到 .idx（当前已在 IO 线程，且整本正文就在内存里）：
            // 阅读页打开同一本书时直接读索引，不必再把整本重扫一遍 —— 这是本地书「第二次打开秒开」的关键。
            // 首次打开也不会白算：阅读页稍后要用的正是这份结果。
            runCatching { chaptersFor(file, text) }
                .onFailure { Log.w("LocalBookLoader", "build chapter index failed", it) }
            Result.Ok(stripExtension(displayName), file.absolutePath)
        } catch (e: Exception) {
            Log.w("LocalBookLoader", "load failed", e)
            Result.Err("无法打开该文件：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stripExtension(name: String): String {
        if (name.isBlank()) return "本地小说"
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ===== 章节索引缓存（.idx 侧车文件） =====
    // 整本切章是 O(全书字数) 的扫描：几 MB 的 txt 在手机上要几百毫秒。缓存正文旁边放一份
    // 「标题 + 起止偏移」的索引，第二次打开直接读索引（几十毫秒），不必再扫一遍全书。
    // 索引以「正文字数 + 缓存文件大小 + 修改时间」三重校验，正文被重写/截断即自动失效重建。

    private const val INDEX_VERSION = "v1"
    private const val MAX_TITLE_LEN = 60
    /** 原始行（含缩进）超过这个长度就不可能是标题，直接跳过，避免对每个正文段做 trim + 正则。 */
    private const val MAX_TITLE_RAW_LEN = 160

    private fun indexFile(cacheFile: File) = File(cacheFile.parentFile, cacheFile.name + ".idx")

    /**
     * 取这本书的章节列表：优先读索引，缺失/失效则重新切章并回写索引。
     * 必须在 IO 线程调用（会读盘、写盘）。
     */
    fun chaptersFor(cacheFile: File, text: String): List<LocalChapter> {
        val cached = readChapterIndex(cacheFile, text)
        if (cached != null) return cached
        val chapters = splitChapters(text)
        if (chapters.isNotEmpty()) {
            runCatching { writeChapterIndex(cacheFile, text, chapters) }
                .onFailure { Log.w("LocalBookLoader", "write chapter index failed", it) }
        }
        return chapters
    }

    /** 读索引；版本/长度/修改时间任一不符或解析异常都返回 null（调用方回退重新切章）。 */
    fun readChapterIndex(cacheFile: File, text: String): List<LocalChapter>? {
        val idx = indexFile(cacheFile)
        if (!idx.exists() || idx.length() <= 0) return null
        return try {
            val lines = idx.readLines()
            val head = lines.firstOrNull()?.split('\t') ?: return null
            if (head.size < 5 || head[0] != INDEX_VERSION) return null
            if (head[1].toIntOrNull() != text.length) return null
            if (head[2].toLongOrNull() != cacheFile.length()) return null
            if (head[3].toLongOrNull() != cacheFile.lastModified()) return null
            val out = ArrayList<LocalChapter>(lines.size - 1)
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val t1 = line.indexOf('\t')
                val t2 = if (t1 >= 0) line.indexOf('\t', t1 + 1) else -1
                if (t1 <= 0 || t2 <= t1) return null
                val start = line.substring(0, t1).toIntOrNull() ?: return null
                val end = line.substring(t1 + 1, t2).toIntOrNull() ?: return null
                if (end <= start || end > text.length) return null
                out.add(LocalChapter(line.substring(t2 + 1), start, end))
            }
            if (head[4].toIntOrNull() != out.size) null else out
        } catch (e: Exception) {
            null
        }
    }

    /** 写索引（临时文件 + rename，避免写一半被系统杀掉后留下坏索引）。 */
    fun writeChapterIndex(cacheFile: File, text: String, chapters: List<LocalChapter>) {
        val target = indexFile(cacheFile)
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            java.io.BufferedWriter(
                java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8),
                64 * 1024
            ).use { w ->
                w.write(
                    "$INDEX_VERSION\t${text.length}\t${cacheFile.length()}\t${cacheFile.lastModified()}\t${chapters.size}\n"
                )
                for (c in chapters) {
                    // 标题里出现制表符/换行会破坏行格式，统一折成空格
                    val title = c.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    w.write(c.start.toString())
                    w.write("\t")
                    w.write(c.end.toString())
                    w.write("\t")
                    w.write(title)
                    w.write("\n")
                }
            }
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(target)) tmp.delete()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 原子写：先写临时文件再 rename，避免写到一半被系统杀掉后留下「半截正文」缓存
     * （半截缓存一旦被 up 复用，读者会永远看不到后半本的内容）。
     */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                // 删不掉就退回直接覆盖写，至少保证内容完整
                target.writeText(text, Charsets.UTF_8)
                return
            }
            if (!tmp.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ===== 章节切分（本地 txt 目录） =====

    /**
     * 按章节标题行把整本纯文本切成多章。
     * 识别「第X章 / 第X回 / Chapter X / 序章 / 楔子 ...」等常见标题，
     * 跳过正文前密集的「书前目录」标题簇（相邻标题间几乎无正文），避免把目录条目当成空章。
     *
     * 缩进兼容：不同下载站的 txt 排版差异很大——有的标题顶格、正文缩进；有的标题带 1 个半角空格
     * 而正文缩进 4 个空格（如《龙藏》）。因此这里不再「见缩进就排除」，而是先探测全书的正文段缩进，
     * 只把「缩进与正文段一致」的行判为正文（典型是紧贴正文的重复标题），详见 [isBodyParagraph]。
     *
     * 实现上刻意「不建段落列表」：整本几 MB 的 txt 用 split('\n') 会造出十几万个 String，
     * 加上逐行 trim() 的复制，在手机上是几百毫秒的主线程/GC 开销。这里改成按下标单遍扫描，
     * 只有「真的可能是标题」的短行才 substring 出来跑正则，其余长正文一行都不分配。
     *
     * @return 空列表表示无法识别章节（调用方回退为整本单章）。
     */
    fun splitChapters(text: String): List<LocalChapter> {
        if (text.isBlank()) return emptyList()
        // 先探测全书「正文段」的行首缩进，用于区分标题行与正文行。
        val bodyIndent = dominantBodyIndent(text)
        // 收集所有「像章节标题」的段落及其字符偏移
        val headings = ArrayList<Heading>()
        // 仅因「缩进与正文一致」被排除的候选（见下方安全网）
        val indentRejected = ArrayList<Heading>()
        var lineStart = 0
        val len = text.length
        while (lineStart <= len) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = len
            // 标题行通常较短：先按「原始行长」做廉价排除，正文段落一行都不用 trim / 跑正则。
            // 阈值放宽到 160，是为了容纳「标题带缩进」的排版（60 字标题 + 几十个空格）。
            val rawLen = lineEnd - lineStart
            if (rawLen in 1..MAX_TITLE_RAW_LEN) {
                // 先看首个非空白字符：正文段落几乎不可能以「第 / C / 序 / 楔 …」开头，
                // 这一步把 99% 的正文行挡在 substring + 正则之前（中文小说段落实测就是这么挡掉的）。
                var s = lineStart
                while (s < lineEnd && text[s].isWhitespace()) s++
                if (s < lineEnd && isTitleLeadChar(text[s])) {
                    // t = p.trim()：Kotlin 的 trim() 按 Char.isWhitespace 裁，这里手工算边界避免复制
                    var e = lineEnd
                    while (e > s && text[e - 1].isWhitespace()) e--
                    if (e - s in 1..MAX_TITLE_LEN) {
                        val title = text.substring(s, e)
                        if (isChapterTitle(title)) {
                            val heading = Heading(title, lineStart)
                            if (isBodyParagraph(text, lineStart, bodyIndent)) {
                                indentRejected.add(heading)
                            } else {
                                headings.add(heading)
                            }
                        }
                    }
                }
            }
            if (lineEnd >= len) break
            lineStart = lineEnd + 1
        }
        // 安全网：若按正文缩进排除后「一个标题都不剩」，但确实存在章节样式的行，
        // 说明这本书的标题与正文用了同一种缩进（无法用缩进区分）。此时接受它们，
        // 也远比整本退化成「第N段」兜底更贴近用户预期。
        if (headings.isEmpty() && indentRejected.size >= 3) {
            headings.addAll(indentRejected)
            headings.sortBy { it.start }
        }
        if (headings.isEmpty()) {
            // 识别不到章节标题（很多下载 txt 没有「第X章」标记，或只有卷首一处）：
            // 不再回退为「整本单章」（会导致整本书在主线程一次性排版、打开极慢甚至卡死），
            // 改为按「空白段簇 + 固定字数窗口」启发式切成多章，既保留目录可跳，又让每章体量可控。
            return fallbackSplit(text)
        }

        val minBody = 200 // 相邻标题间正文少于此值，判定为「书前目录簇」或密集短章，不成章
        val result = mutableListOf<LocalChapter>()
        for (i in headings.indices) {
            val (title, start) = headings[i]
            val nextStart = if (i + 1 < headings.size) headings[i + 1].start else text.length
            val bodyLen = nextStart - (start + title.length)
            val prevEnd = if (i > 0) headings[i - 1].start + headings[i - 1].title.length else -1
            val prevBody = if (i > 0) start - prevEnd else Int.MAX_VALUE
            // 跳过书前目录簇 / 密集短章：本条自身到下个标题间距过短，且处于「开头或密集簇」中。
            // 这样孤立的短章（前后都是长正文）仍会被保留为独立章节。
            val leadingOrClustered = (i == 0) || (prevBody < minBody)
            if (i < headings.size - 1 && bodyLen < minBody && leadingOrClustered) continue
            result.add(LocalChapter(title, start, nextStart))
        }
        // 极端兜底：全部被判定为目录簇时，回退为全部成章，保证至少有目录可跳
        if (result.isEmpty()) {
            for (i in headings.indices) {
                val (title, start) = headings[i]
                val nextStart = if (i + 1 < headings.size) headings[i + 1].start else text.length
                result.add(LocalChapter(title, start, nextStart))
            }
        }
        // 首个标题之前的内容（简介 / 楔子 / 书头信息）必须被某章覆盖，否则这段字会被整段丢掉、
        // 与原文对不上字数。
        val firstStart = result.first().start
        if (firstStart > 60) {
            // 内容可观（如简介 / 楔子正文）：单独补一个「（正文）」章，目录里可直接跳转。
            result.add(0, LocalChapter("（正文）", 0, firstStart))
        } else if (firstStart > 0) {
            // 只有一小段（典型是"《书名》\n作者：xx\n来源：xx\n网址：xx"这类书头元信息，51~57 字）：
            // 不值得单列成章，但也绝不能丢。把它并入第一章（起点从 0 开始），
            // 这样既不污染目录、不改变章节总数（避免书架续读下标整体错位），又保证零丢字。
            val first = result.first()
            result[0] = LocalChapter(first.title, 0, first.end)
        }
        return result
    }

    /** 扫描过程中临时用的标题节点（title + 在整本正文中的起始偏移）。 */
    private data class Heading(val title: String, val start: Int)

    /**
     * 探测全书「正文段落」的行首缩进，作为区分标题行 / 正文行的基准。
     * 只统计明显是正文的长行（长度 > 80，不可能是标题），取其中出现次数最多的行首空白串，
     * 例如 4 个半角空格 "    "、或 2 个全角空格 "　　"。
     * 若全书没有长行（如纯短句的诗歌文本）则返回空串，表示「无缩进基准」，调用方按顶格处理。
     */
    private fun dominantBodyIndent(text: String): String {
        val counter = LinkedHashMap<String, Int>()
        var lineStart = 0
        val len = text.length
        while (lineStart <= len) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = len
            if (lineEnd - lineStart > 80) {
                var ind = lineStart
                while (ind < lineEnd && isIndentChar(text[ind])) ind++
                // ind 走到行尾说明整行都是空白（等价于原先的 isNotBlank 过滤），不参与统计
                if (ind < lineEnd) {
                    // 缩进串极短（通常是 0~4 个字符），这里的 substring 代价可忽略
                    val key = text.substring(lineStart, ind)
                    counter[key] = (counter[key] ?: 0) + 1
                }
            }
            if (lineEnd >= len) break
            lineStart = lineEnd + 1
        }
        var bestKey = ""
        var bestCount = -1
        for ((k, v) in counter) {
            if (v > bestCount) {
                bestCount = v
                bestKey = k
            }
        }
        return bestKey
    }

    /**
     * 该行是否「按正文段的方式排版」——是则视为正文，不作章节标题。
     * 判据：行首缩进与全书正文缩进完全一致。
     * 这样能排掉「紧贴正文的重复标题」（如 "    第728章 有何不敢？"），
     * 同时保留「标题自带少量缩进」的正常标题（如 " 第1233章 又有刁民想害朕"，正文缩进 4 空格）。
     * 正文缩进探测为空串（全书顶格）时不因缩进排除，避免把顶格写法的标题全判成正文。
     */
    private fun isBodyParagraph(text: String, lineStart: Int, bodyIndent: String): Boolean {
        if (bodyIndent.isEmpty()) return false
        return leadingIndent(text, lineStart) == bodyIndent
    }

    /** 取行首连续的空白字符（半角空格 / 全角空格 / 制表符等）。 */
    private fun leadingIndent(text: String, lineStart: Int): String {
        var i = lineStart
        while (i < text.length && isIndentChar(text[i])) i++
        return text.substring(lineStart, i)
    }

    /**
     * 兜底切分：识别不到章节标题时，把整本书按「空白段簇 + 固定字数窗口」切成多章。
     * - 遇到连续两个及以上空行（段间大间隔）且当前累积字数已超阈值 → 切成新章（贴近自然分章点）。
     * - 累积字数超过 [MAX_CHUNK]（默认 1.6 万字）→ 强制切章，避免单章过大导致排版/翻页压力。
     * 保证至少返回一章；章节标题用「第N段」占位，目录可用。
     */
    private fun fallbackSplit(text: String): List<LocalChapter> {
        val paras = text.split('\n')
        val chapters = mutableListOf<LocalChapter>()
        val MAX_CHUNK = 16_000
        val MIN_CHUNK = 800
        var offset = 0
        var chapterStart = 0
        var chapterLen = 0
        for (i in paras.indices) {
            val p = paras[i]
            val len = p.length + 1
            val trimmed = p.trim()
            val isBigGap = trimmed.isBlank() && i + 1 < paras.size && paras[i + 1].trim().isBlank()
            chapterLen += len
            val nextOffset = offset + len
            if ((isBigGap && chapterLen >= MIN_CHUNK) || chapterLen >= MAX_CHUNK) {
                // 末尾章节的偏移可能恰好越界 1 个字符（累计 offset 含换行占位），
                // 必须把结束偏移收敛到 text.length，否则 applyLocalChapter 切片会抛越界。
                val end = nextOffset.coerceAtMost(text.length)
                if (end > chapterStart) {
                    chapters.add(LocalChapter("第${chapters.size + 1}段", chapterStart, end))
                }
                chapterStart = nextOffset
                chapterLen = 0
            }
            offset = nextOffset
        }
        if (chapterStart < text.length) {
            chapters.add(LocalChapter("第${chapters.size + 1}段", chapterStart, text.length))
        }
        return if (chapters.isEmpty()) listOf(LocalChapter("（正文）", 0, text.length)) else chapters
    }

    /**
     * 判断一段文字「像不像章节标题」。
     * 关键修正：原本用 [Regex.matches]（要求整行完全匹配），导致「第1章 重生」这种
     * 「章节号 + 标题文字」最常见格式整行匹配失败 → 一条章节都识别不出来 → 整本书被当成单章
     * （目录里只有一项、即整本书），且几 MB 正文一次性排版导致打开极慢。
     * 改用 [Regex.containsMatchIn]（前缀匹配）即可正确识别「第N章 / 第N章 xxx / 第 N 章 xxx」等。
     */
    private fun isChapterTitle(t: String): Boolean = CHAPTER_TITLE_RE.any { it.containsMatchIn(t) }

    /**
     * 标题可能的首字符（[CHAPTER_TITLE_RE] 里每条正则的首字符集合）。
     * 只用作「跳过明显不是标题的行」的廉价前置过滤 —— 必须覆盖全部正则的起始字，
     * 否则会给某些标题格式判死刑；改正则时这里要同步。
     */
    private fun isTitleLeadChar(c: Char): Boolean = when (c) {
        '第', 'C', 'c', '序', '楔', '引', '开', '尾', '后', '番', '外', '终', '前' -> true
        else -> false
    }

    /**
     * 生成稳定的缓存文件名：书名 + 正文前 8KB 采样做 SHA-1。
     * 这样内容相同的书总是映射到同一文件，书架续读时能稳定找到缓存。
     */
    private fun stableKey(name: String, text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(text.take(8192).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 行首是否为缩进字符。
     * 关键点：Kotlin/Java 的 [Char.isWhitespace] 不认全角空格（U+3000），
     * 而中文小说常见「全角空格 + 章节标题」的重复行，必须单独判掉，否则会被误认为标题。
     */
    private fun isIndentChar(c: Char): Boolean {
        return c.isWhitespace() || c == '\u3000' || c == '\u00A0' || c == '\u2007' || c == '\u202F'
    }

    private val CHAPTER_TITLE_RE = listOf(
        // 第N章 / 第N回 / 第N卷 ...（支持数字、中文数字、中间空格）
        // 末尾负向预查：排除「第N章，正文……」这种正文行（逗号/句号/问叹号/分号/顿号紧跟章节号后，
        // 说明这一行其实是正文而非标题），避免把正文段错误切成空章节。
        Regex("""^第\s*[0-9零〇一二三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章回卷节部篇集](?![，。！？；、])"""),
        // Chapter N（英文）
        Regex("""^[Cc]hapter\s+[0-9]+"""),
        // 序章 / 楔子 / 番外 ... 等无序号结构标题
        Regex("""^(序章|序言|楔子|引子|开篇|尾声|后记|番外|外传|终章|终卷|前言)""")
    )

    /** epub 里按「块级」处理的标签：遍历时前后补换行，保证分段不粘连。 */
    private val EPUB_BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "section", "article", "dd", "dt", "tr", "td", "th", "pre", "hr", "figcaption"
    )

    // ===== .txt =====

    private fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return decodeText(bytes)
    }

    /**
     * 把原始字节解码成文本。
     *
     * 旧实现只按「UTF-8 解出的替换符数 > 总字节数的 1/4」判定 GBK，实测会漏判：
     * 一本「中文 + 网址/英文/数字」的 GBK txt（下载站书头几乎都带 www.xxx.com、更新时间之类），
     * 用 UTF-8 试解码只产生约 20% 替换符（低于 25% 阈值）→ 被当成 UTF-8 → 整本中文变问号，
     * 阅读页上就是「字数少了一大截」。另外 UTF-16 的 txt 以前完全没有处理，会被整本解成乱码。
     * 现在的判定顺序：
     *   1) BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）；
     *   2) 按 UTF-8 解，替换符为 0 → 就是 UTF-8（UTF-8 多字节结构校验很严格，GBK 文本极难整本通过）；
     *   3) 替换符极少（< 1%，多半是个别字节损坏）→ 仍按 UTF-8，不为几个坏字节把整本换编码；
     *   4) 其余用 GB18030 重解，取「坏字符更少」的结果。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return normalizeNewlines(String(bytes, 3, bytes.size - 3, Charsets.UTF_8))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return normalizeNewlines(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return normalizeNewlines(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad == 0) return normalizeNewlines(utf8)
        if (bad * 100 < bytes.size) return normalizeNewlines(utf8)
        val gb = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        val chosen = if (gb != null && gb.count { it == '\uFFFD' } < bad) gb else utf8
        return normalizeNewlines(chosen)
    }

    /**
     * 统一换行符。旧实现保留了 \r\n，导致按 '\n' 切段后每段末尾都挂着一个 \r，
     * 既影响章节标题识别，也让排版测量多算一个不可见字符。
     *
     * 现在是「先探测再动手」：几 MB 的正文若本来就是 \n（绝大多数 UTF-8 txt 如此），
     * 直接返回原串、一次复制都不做；只有真的含 \r 或 BOM 时才单遍重建，
     * 旧实现则无条件做 3 次全串 replace（等于把整本书复制 3 遍）。
     */
    private fun normalizeNewlines(s: String): String {
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == '\r' || c == '\uFEFF') break
            i++
        }
        if (i == n) return s
        val sb = StringBuilder(n)
        sb.append(s, 0, i)
        while (i < n) {
            val c = s[i]
            when (c) {
                '\r' -> {
                    sb.append('\n')
                    if (i + 1 < n && s[i + 1] == '\n') i++
                }
                '\uFEFF' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // ===== .epub =====

    /**
     * 解析 epub。
     *
     * 旧实现有三处会直接影响「字数」：
     *  1. 正文文件按「文件名排序」拼接，而不是按 OPF 的 spine（真正的阅读顺序）——顺序会乱；
     *  2. 每个 xhtml 都硬按 UTF-8 解码，非 UTF-8 的 epub 直接变乱码；
     *  3. 用 p/div 的 el.text() 拼正文：<br> 被吞掉（几段粘成一段），嵌套 div 又把子 p 的文字重复输出。
     * 这里改为「按 spine 顺序 + 单遍遍历 + br 转换行」。
     */
    private fun readEpub(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法读取 epub 文件")
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name.replace('\\', '/')] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        if (entries.isEmpty()) throw Exception("epub 内容为空或已损坏")

        val htmlFiles = entries.keys.filter { n ->
            val l = n.lowercase()
            l.endsWith(".htm") || l.endsWith(".html") || l.endsWith(".xhtml")
        }
        // spine 顺序优先；取不到 OPF 时按文件名兜底，保证不漏章节
        val ordered = LinkedHashSet<String>()
        epubSpineOrder(entries).forEach { path -> findEntry(entries, path)?.let { ordered.add(it) } }
        htmlFiles.sorted().forEach { ordered.add(it) }

        val sb = StringBuilder()
        for (name in ordered) {
            val data = entries[name] ?: continue
            val text = epubFileToText(data)
            if (text.isNotBlank()) sb.append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** 按 OPF 的 spine 返回 xhtml 的阅读顺序（相对 zip 根的路径）。 */
    private fun epubSpineOrder(entries: Map<String, ByteArray>): List<String> {
        val container = findEntry(entries, "META-INF/container.xml")?.let { entries[it] } ?: return emptyList()
        val rootPath = runCatching {
            Jsoup.parse(String(container, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                .select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return emptyList()
        val opf = findEntry(entries, rootPath)?.let { entries[it] } ?: return emptyList()
        val baseDir = rootPath.substringBeforeLast('/', "")
        val doc = runCatching {
            Jsoup.parse(String(opf, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
        }.getOrNull() ?: return emptyList()

        val manifest = HashMap<String, String>()
        val itemRefs = mutableListOf<String>()
        // 用 tagName 判定而不是选择器：部分 OPF 用了命名空间前缀（opf:item），选择器会挑不到。
        for (el in doc.getAllElements()) {
            when (el.tagName().substringAfter(':').lowercase()) {
                "item" -> {
                    val id = el.attr("id")
                    val href = el.attr("href")
                    if (id.isNotBlank() && href.isNotBlank()) manifest[id] = resolveZipPath(baseDir, href)
                }
                "itemref" -> itemRefs.add(el.attr("idref"))
                else -> {}
            }
        }
        return itemRefs.mapNotNull { manifest[it] }
    }

    private fun findEntry(entries: Map<String, ByteArray>, path: String): String? {
        val p = path.trimStart('/')
        if (entries.containsKey(p)) return p
        return entries.keys.firstOrNull { it.equals(p, ignoreCase = true) }
    }

    private fun resolveZipPath(baseDir: String, href: String): String {
        val h = href.substringBefore('#').removePrefix("./")
        val full = if (baseDir.isBlank()) h else "$baseDir/$h"
        val out = ArrayDeque<String>()
        for (seg in full.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    private fun epubFileToText(data: ByteArray): String {
        val doc = Jsoup.parse(decodeText(data))
        val body = doc.body() ?: return ""
        val sb = StringBuilder()
        fun walk(n: org.jsoup.nodes.Node) {
            when (n) {
                is org.jsoup.nodes.TextNode -> sb.append(n.wholeText)
                is org.jsoup.nodes.Element -> {
                    val tag = n.tagName().lowercase()
                    if (tag == "script" || tag == "style") return
                    if (tag == "br") {
                        sb.append('\n')
                        return
                    }
                    if (tag in EPUB_BLOCK_TAGS) {
                        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                        n.childNodes().forEach { walk(it) }
                        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    } else {
                        n.childNodes().forEach { walk(it) }
                    }
                }
                else -> {}
            }
        }
        body.childNodes().forEach { walk(it) }
        return cleanPlainText(sb.toString())
    }

    private fun cleanPlainText(s: String): String = s
        .replace("\r\n", "\n").replace('\r', '\n')
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("[ ]{2,}"), " ")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    // ===== .umd =====
    // 参考 UMD 纯文本格式：文件头 0xDE9A9B89；之后是一串「功能块(0x23)」与「数据块(0x24)」。
    // 正文被切成多块，每块通常是 zlib 压缩的 UTF-16LE 文本。

    private fun readUmd(context: Context, uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (data.size < 8) throw Exception("文件过小，不是有效的 UMD 文件")

        val magicLE = byteArrayOf(0x89.toByte(), 0x9B.toByte(), 0x9A.toByte(), 0xDE.toByte())
        val magicBE = byteArrayOf(0xDE.toByte(), 0x9A.toByte(), 0x9B.toByte(), 0x89.toByte())
        val head = data.copyOfRange(0, 4)
        if (!head.contentEquals(magicLE) && !head.contentEquals(magicBE)) {
            throw Exception("文件头不匹配，不是有效的 UMD 文件")
        }

        // 关键修正：功能块里只有 0x81(书籍信息) / 0x83(简介) / 0x87(封面) 末尾 4 字节指向「非正文」数据块；
        // 0x82(章节标题) 和 0x84(正文块索引) 引用的恰恰是正文块。旧实现把 5 种 id 全塞进
        // 「非正文」集合直接跳过，于是每遇到一个这样的功能块就整块正文消失
        // （表现为某些章节少几段、甚至整章空白）。0x84 的列表还正好是正文顺序，顺手用来排序。
        val nonBodyRandVals = mutableSetOf<Long>()
        val bodyOrder = mutableListOf<Long>()
        val dataBlocks = LinkedHashMap<Long, ByteArray>() // 保留文件中的出现顺序
        var off = 4
        while (off + 5 <= data.size) {
            val marker = data[off].toInt() and 0xFF
            if (marker == 0x23) {
                // 功能块：1B marker + 2B id + 1B unknown + 1B length + content
                val id = readU16LE(data, off + 1)
                val len = data[off + 4].toInt() and 0xFF
                val contentStart = off + 5
                val contentEnd = contentStart + (len - 5)
                if (len < 5 || contentEnd > data.size) break
                val content = data.copyOfRange(contentStart, contentEnd)
                when (id) {
                    0x81, 0x83, 0x87 -> if (content.size >= 4) {
                        nonBodyRandVals.add(readU32LE(content, content.size - 4))
                    }
                    0x84 -> {
                        var i = 0
                        while (i + 4 <= content.size) {
                            bodyOrder.add(readU32LE(content, i))
                            i += 4
                        }
                    }
                    else -> {}
                }
                off = contentEnd
            } else if (marker == 0x24) {
                // 数据块：1B marker + 4B randVal + 4B length + content
                if (off + 9 > data.size) break
                val randVal = readU32LE(data, off + 1)
                val len = readU32LE(data, off + 5)
                if (len < 9) break
                val contentStart = off + 9
                val contentEnd = (contentStart + (len - 9)).toInt()
                if (contentEnd > data.size) break
                dataBlocks[randVal] = data.copyOfRange(contentStart, contentEnd)
                off = contentEnd
            } else {
                break
            }
        }

        // 正文顺序：优先按 0x84 索引，索引缺失/不全时按文件顺序补齐，保证不漏正文块
        val ordered = LinkedHashSet<Long>()
        bodyOrder.forEach { if (dataBlocks.containsKey(it)) ordered.add(it) }
        dataBlocks.keys.forEach { if (it !in nonBodyRandVals) ordered.add(it) }

        val sb = StringBuilder()
        for (rv in ordered) {
            val text = decodeUmdBlock(dataBlocks[rv] ?: continue) ?: continue
            sb.append(text)
            if (!text.endsWith("\n")) sb.append('\n')
        }
        if (sb.isEmpty()) throw Exception("无法解压 UMD 正文，可能是加密或损坏的文件")
        return sb.toString()
    }

    /**
     * 解码单个数据块：
     *  - 图片（封面/插图）按文件头识别后跳过，比按 randVal 猜更可靠；
     *  - 压缩方式先试 zlib，再试 raw deflate（少数实现不带 zlib 头），最后按未压缩文本处理；
     *  - 正文是 UTF-16LE，段落分隔符 U+2029。
     * 旧实现只试 zlib、且失败整块丢弃，raw deflate 的块会整段消失。
     */
    private fun decodeUmdBlock(content: ByteArray): String? {
        if (content.size < 4) return null
        if (isImageBlock(content)) return null
        val raw = inflateOrNull(content, nowrap = false)
            ?: inflateOrNull(content, nowrap = true)
            ?: content
        if (raw.size < 2) return null
        val text = String(raw, Charsets.UTF_16LE)
            .replace('\u2029', '\n')
            .replace("\u0000", "")
        if (text.isBlank()) return null
        val bad = text.count { it == '\uFFFD' }
        if (bad > text.length / 8 + 4) return null
        return text
    }

    private fun isImageBlock(b: ByteArray): Boolean {
        if (b.size < 4) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        val gif = at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46
        val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
        val jpg = at(0) == 0xFF && at(1) == 0xD8
        val bmp = at(0) == 0x42 && at(1) == 0x4D
        return gif || png || jpg || bmp
    }

    private fun inflateOrNull(data: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(data)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            // 连续 0 字节输出且未 finished 时立即收工：Inflater 在某些损坏数据上会一直返回 0，
            // 不设这个保护会死循环（卡在解析线程上，表现为"打开文件永远转圈"）。
            var zeroStreak = 0
            while (!inflater.finished() && zeroStreak < 3) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    zeroStreak++
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    zeroStreak = 0
                    bos.write(buf, 0, n)
                }
            }
            if (bos.size() == 0) null else bos.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
}
