package com.example.novelreader

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

object ClipboardHelper {

    fun getClipboardText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getClipboardHtml(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0)?.htmlText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    // 从文本（含 HTML）中提取第一个完整的 URL 链接
    fun extractUrl(source: String?): String? {
        if (source.isNullOrBlank()) return null

        // 优先匹配 href / src 属性中的链接
        val attrRegex = Regex("""(?:href|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (m in attrRegex.findAll(source)) {
            val url = m.groupValues[1].trim()
            if (isLikelyNovelUrl(url)) return url
        }

        // 兜底：匹配任意 http(s):// 形式的完整链接
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        for (m in urlRegex.findAll(source)) {
            val url = m.value.trim()
            if (isLikelyNovelUrl(url)) return url
        }

        return null
    }

    // 综合判断剪切板：若包含 HTML 且其中是完整 URL 链接，则返回该链接
    fun detectNovelUrl(context: Context): String? {
        getClipboardHtml(context)?.let { html ->
            extractUrl(html)?.let { return it }
        }
        return extractUrl(getClipboardText(context))
    }

    fun isLikelyNovelUrl(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val url = text.trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return false

        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = uri.host ?: return false
        if (host.isBlank()) return false

        val lower = ((uri.path ?: "") + uri.lastPathSegment.orEmpty()).lowercase()
        val mediaExt = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            ".mp4", ".mkv", ".mov", ".avi", ".webm",
            ".mp3", ".wav", ".ogg", ".m4a",
            ".pdf", ".zip", ".rar", ".7z", ".apk",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
        )
        if (mediaExt.any { lower.endsWith(it) }) return false

        return true
    }
}
