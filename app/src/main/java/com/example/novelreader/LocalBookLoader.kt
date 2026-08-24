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
            // 缓存文件已存在（按内容稳定命名）则跳过重复写盘：避免每次打开都重写整本多 MB 文本，
            // 减少 I/O 耗时（首次打开写入一次即可，书架续读复用同一文件）。
            if (!file.exists()) file.writeText(text, Charsets.UTF_8)
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

    // ===== 章节切分（本地 txt 目录） =====

    /**
     * 按章节标题行把整本纯文本切成多章。
     * 识别「第X章 / 第X回 / Chapter X / 序章 / 楔子 ...」等常见标题，
     * 跳过正文前密集的「书前目录」标题簇（相邻标题间几乎无正文），避免把目录条目当成空章。
     *
     * @return 空列表表示无法识别章节（调用方回退为整本单章）。
     */
    fun splitChapters(text: String): List<LocalChapter> {
        if (text.isBlank()) return emptyList()
        val paras = text.split('\n')
        // 收集所有「像章节标题」的段落及其字符偏移
        val headings = mutableListOf<Pair<String, Int>>()
        var offset = 0
        for (p in paras) {
            val t = p.trim()
            // 标题行通常较短；过长则更可能是正文段落，排除掉以减小误切。
            // 关键：真实章节标题顶格出现；带缩进（全角空格 U+3000 / 普通空格 / 制表符等）的
            // 重复标题或正文段落（如"　　第485章 重启贸易 ...正文..."）必须排除，否则一章会被切成两章。
            // 注意：Kotlin/Java 的 Char.isWhitespace() 不认全角空格（U+3000），需单独判断。
            if (t.isNotBlank() && t.length <= 60 && isChapterTitle(t) && p.isNotBlank() && !isIndentChar(p[0])) {
                headings.add(t to offset)
            }
            offset += p.length + 1 // +1 抵消被 split 吞掉的换行符
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
            val nextStart = if (i + 1 < headings.size) headings[i + 1].second else text.length
            val bodyLen = nextStart - (start + title.length)
            val prevEnd = if (i > 0) headings[i - 1].second + headings[i - 1].first.length else -1
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
                val nextStart = if (i + 1 < headings.size) headings[i + 1].second else text.length
                result.add(LocalChapter(title, start, nextStart))
            }
        }
        // 首个标题之前若有可观正文（如简介 / 楔子正文），补一个「正文」章
        val firstStart = result.first().start
        if (firstStart > 60) {
            result.add(0, LocalChapter("（正文）", 0, firstStart))
        }
        return result
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

    // ===== .txt =====

    private fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val charset = detectCharset(bytes)
        return String(bytes, charset).replace("\uFEFF", "")
    }

    /**
     * 中文网络小说常见 GBK/GB18030 编码，先按 UTF-8 解码，
     * 若出现大量 replacement char（乱码）则回退到 GB18030。
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        return if (bad > 0 && bad * 4 > bytes.size) {
            runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
        } else {
            Charsets.UTF_8
        }
    }

    // ===== .epub =====

    private fun readEpub(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                val entries = mutableListOf<Pair<String, ByteArray>>()
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".htm") || name.endsWith(".html") || name.endsWith(".xhtml")) {
                        entries.add(name to zis.readBytes())
                    }
                    entry = zis.nextEntry
                }
                // epub 内部 xhtml 按文件名排序通常即阅读顺序（严谨实现应读 OPF spine，这里做通用兜底）
                entries.sortedBy { it.first }.forEach { (_, data) ->
                    val doc = Jsoup.parse(String(data, Charsets.UTF_8))
                    val body = doc.body() ?: doc
                    val blocks = body.select("p, div, h1, h2, h3, h4, h5, h6, li")
                    if (blocks.isNotEmpty()) {
                        blocks.forEach { el ->
                            val t = el.text().trim()
                            if (t.isNotEmpty()) sb.append(t).append("\n\n")
                        }
                    } else {
                        val t = body.text().trim()
                        if (t.isNotEmpty()) sb.append(t).append("\n\n")
                    }
                }
            }
        } ?: throw Exception("无法读取 epub 文件")
        return sb.toString()
    }

    // ===== .umd =====
    // 参考 UMD 纯文本格式：文件头 0xDE9A9B89；之后是一串「功能块(0x23)」与「数据块(0x24)」。
    // 正文被切成多块，每块是 zlib 压缩的 UTF-16LE 文本，其 randVal 不会出现在任何功能块的引用里。

    private fun readUmd(context: Context, uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (data.size < 8) throw Exception("文件过小，不是有效的 UMD 文件")

        val magicLE = byteArrayOf(0x89.toByte(), 0x9B.toByte(), 0x9A.toByte(), 0xDE.toByte())
        val magicBE = byteArrayOf(0xDE.toByte(), 0x9A.toByte(), 0x9B.toByte(), 0x89.toByte())
        val head = data.copyOfRange(0, 4)
        if (!head.contentEquals(magicLE) && !head.contentEquals(magicBE)) {
            throw Exception("文件头不匹配，不是有效的 UMD 文件")
        }

        // 功能块 content 末尾 4 字节指向其数据块的 randVal；这些 randVal 对应的数据块不是正文
        val funcRandVals = mutableSetOf<Long>()
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
                if (id in arrayOf(0x81, 0x82, 0x83, 0x84, 0x87)) {
                    val c = data.copyOfRange(contentStart, contentEnd)
                    if (c.size >= 4) funcRandVals.add(readU32LE(c, c.size - 4))
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

        val sb = StringBuilder()
        for ((rv, content) in dataBlocks) {
            if (rv in funcRandVals) continue // 封面 / 目录索引 / 偏移块，跳过
            try {
                val inflated = inflate(content)
                val text = String(inflated, Charsets.UTF_16LE).replace('\u2029', '\n')
                sb.append(text)
            } catch (_: Exception) {
                // 个别块解压失败则跳过，尽量保留可读内容
            }
        }
        if (sb.isEmpty()) throw Exception("无法解压 UMD 正文，可能是加密或损坏的文件")
        return sb.toString()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                bos.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return bos.toByteArray()
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
}
