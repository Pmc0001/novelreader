package com.example.novelreader

import android.net.Uri

/**
 * 书源定义。换源时按书名在 [searchUrls] 中的候选地址依次搜索，
 * 得到候选书后切换入口 URL。每个地址中的 {q} 会被替换为按 [encoding] 编码后的书名。
 *
 * 说明：盗版小说站域名 / 搜索结构经常变动，内置源按「文献记录的真实格式」填写，
 * 并保留多个候选兜底；若全部失效，请用「换源」面板添加自定义书源（粘贴带 {q} 的搜索地址）。
 * 注意：经联网核实，biquge.com.tw、biquge.info 等域名已失效，52bqg 等站搜索结果改为 JS 动态渲染，
 * 因此内置源需在真机联网复测；App 用 WebView 执行 JS 后抽取，正好适配此类站点。
 */
data class BookSource(
    val id: String,
    val name: String,
    val searchUrls: List<String>,
    val encoding: String = "utf-8",
    val builtin: Boolean = false
) {
    /** 兼容旧调用：主搜索地址（第一个候选）。 */
    val searchUrl: String get() = searchUrls.first()

    fun candidateCount(): Int = searchUrls.size

    fun buildSearchUrl(keyword: String, index: Int = 0): String {
        val tpl = searchUrls.getOrNull(index) ?: searchUrls.first()
        return tpl.replace("{q}", encodeKeyword(keyword))
    }

    private fun encodeKeyword(s: String): String {
        val enc = encoding.lowercase()
        return if (enc == "gbk" || enc == "gb2312") {
            // 笔趣阁系站点 searchkey 需要 GBK 字节再百分号编码（不能用 UTF-8）
            s.toByteArray(charset("GBK")).joinToString("") { b ->
                "%" + (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
            }
        } else {
            Uri.encode(s)
        }
    }
}
