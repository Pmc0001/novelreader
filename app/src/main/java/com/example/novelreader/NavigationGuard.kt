package com.example.novelreader

import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import java.util.Locale

/**
 * 浏览网页时的跳转管控（纯逻辑，不碰 UI）。
 *
 * 目标：只有「用户手指点中的链接」才允许跳到别的网站，其余一律拦下来，
 * 避免小说站常见的 JS 自动跳转、meta refresh、302 跳转链、弹窗、intent:// 等
 * 把用户带到恶意站点或诱导安装 App。
 *
 * 判定顺序（从宽到严）：
 * 1. about:/javascript: 这类不产生顶层跳转的伪协议 → 交还 WebView；
 * 2. 非 http(s) 协议（intent:// market:// tbopen:// …）→ 一律拦截，且不唤起外部应用；
 * 3. 同站（同域或互为子域）→ 放行，站内换页/翻页不受影响；
 * 4. 本次会话内用户点过「打开」的站点 → 放行；
 * 5. 跨站：
 *    - 没有任何手势信号 → 判定为自动跳转，拦截；
 *    - 严格模式（用户可在菜单开启）→ 一律先提示确认；
 *    - 有手势且触摸落点在 <a> 链接上，或处在「点击后的跳转链」时间窗内 → 放行；
 *    - 其余（点了空白处却跨站跳走、跳转与点击无关）→ 判定为点击劫持，拦截。
 *
 * 被拦截的跳转不会丢失：调用方用 [Decision.url] 提供「打开」入口，用户点一下即放行，
 * 并通过 [allowForSession] 记住该站点，本次会话内不再打扰。
 */
class NavigationGuard(private val prefs: SharedPreferences) {

    /** 拦截原因，文案由调用方组织 */
    enum class Reason {
        /** 无用户操作的自动跳转（JS location / meta refresh / 302 跳转链） */
        AUTO_REDIRECT,

        /** 有触摸但落点不是链接，或跳转与这次点击无关 */
        CLICK_HIJACK,

        /** 严格模式下，用户点中的站外链接也要先确认 */
        CROSS_SITE_CONFIRM,

        /** 网页弹窗（window.open / target=_blank） */
        POPUP,

        /** 非 http(s) 协议（intent:// market:// tbopen:// …） */
        EXTERNAL_SCHEME
    }

    /** 判定结果：allowed=false 时 reason/host/url 用于提示条与「打开」按钮 */
    data class Decision(
        val allowed: Boolean,
        val reason: Reason? = null,
        val url: String = "",
        val host: String = "",
        val scheme: String = ""
    )

    /**
     * 一次跳转请求的全部判定依据，由调用方（WebViewClient / WebChromeClient）填好。
     *
     * @param url           目标地址
     * @param fromUrl       发起这次跳转的页面地址（用于判断是否同站）
     * @param reportedGesture WebResourceRequest.hasGesture() / onCreateWindow 的 isUserGesture
     * @param recentTouch   本页加载后、本次判定前 1.2s 内是否发生过手指按下
     * @param redirect      WebResourceRequest.isRedirect()，服务器 30x 跳转链中的一跳
     * @param anchorHref    触摸落点是否压在 <a href> 上；命中链接时为其 href，否则 null
     * @param popup         是否来自 window.open / target=_blank 的新窗口请求
     */
    data class Request(
        val url: String?,
        val fromUrl: String?,
        val reportedGesture: Boolean,
        val recentTouch: Boolean,
        val redirect: Boolean,
        val anchorHref: String?,
        val popup: Boolean = false
    ) {
        /** 两条独立信号任一成立即认为「有用户手势」，避免单一 API 不准时误拦 */
        val gesture: Boolean get() = reportedGesture || recentTouch
    }

    /** 本次会话内被用户明确放行的站点（退出搜索页即失效） */
    private val sessionAllowed = linkedSetOf<String>()

    /** 「点击后的跳转链」时间窗：用户点完链接后，同一意图的后续重定向仍算用户跳转 */
    private var chainWindowUntil = 0L

    /** 严格模式：连用户自己点的站外链接也先提示确认，默认关闭 */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_MODE, value).apply()

    /** 已放行站点数量（菜单里用来决定是否显示「清除」项） */
    val allowedCount: Int get() = sessionAllowed.size

    // ---------- 对外接口 ----------

    /** 判定一次跳转。返回 allowed=false 时调用方应取消这次跳转并提示用户。 */
    fun evaluate(req: Request): Decision {
        val url = req.url?.trim().orEmpty()
        if (url.isEmpty()) return blocked(Reason.EXTERNAL_SCHEME, req, "", "")

        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return blocked(Reason.EXTERNAL_SCHEME, req, "", "")
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()

        // 不产生顶层跳转的伪协议：javascript: 调用页面函数、about:blank 空页，交还 WebView
        if (scheme == "http" || scheme == "https") {
            // 正常网页，继续往下判定
        } else if (scheme == "javascript" || scheme == "about") {
            return Decision(true)
        } else {
            // intent:// market:// tbopen:// weixin:// file:// 等：不解析、不外跳
            return blocked(Reason.EXTERNAL_SCHEME, req, uri.host.orEmpty(), scheme)
        }

        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        // 相对地址/无 host 的异常地址交给 WebView 自己解析
        if (host.isEmpty()) return Decision(true)

        // 同站、或用户本次会话已放行的站点：直接过
        if (isSameSite(host, hostOf(req.fromUrl)) || isSessionAllowed(host)) {
            if (req.gesture) openChainWindow()
            return Decision(true)
        }

        // —— 跨站 ——

        // 1) 没有一点手势信号：JS 定时器 / meta refresh / 服务器跳转链，一律拦截
        if (!req.gesture) return blocked(Reason.AUTO_REDIRECT, req, host, scheme)

        // 2) 严格模式：站外跳转先让用户确认
        if (strictMode) return blocked(Reason.CROSS_SITE_CONFIRM, req, host, scheme)

        // 3) 手指确实点在了链接上并且就是刚才这一下 → 用户自己的选择，放行
        if (req.anchorHref != null && (req.recentTouch || inChainWindow())) {
            openChainWindow()
            return Decision(true)
        }

        // 4) 用户刚点过链接，随后被服务端 30x 转到别站 → 属于同一次点击意图，放行
        if (req.redirect && inChainWindow()) {
            openChainWindow()
            return Decision(true)
        }

        // 5) 剩下的就是「点了空白处却跳走」「跳转和点击无关」这类劫持
        return blocked(Reason.CLICK_HIJACK, req, host, scheme)
    }

    /** 用户点「打开」：记住这个站点，本次会话内不再拦它 */
    fun allowForSession(host: String?) {
        val h = host?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (h.isNotEmpty()) sessionAllowed.add(h)
    }

    /** 清空本次会话放行列表（菜单入口） */
    fun clearSessionAllowed() {
        sessionAllowed.clear()
        chainWindowUntil = 0L
    }

    // ---------- 内部工具 ----------

    private fun blocked(reason: Reason, req: Request, host: String, scheme: String): Decision {
        // 弹窗被拦时统一按弹窗文案提示，用户更容易理解
        val real = if (req.popup) Reason.POPUP else reason
        return Decision(false, real, req.url.orEmpty(), host, scheme)
    }

    private fun openChainWindow() {
        chainWindowUntil = SystemClock.uptimeMillis() + CHAIN_WINDOW_MS
    }

    private fun inChainWindow(): Boolean = SystemClock.uptimeMillis() <= chainWindowUntil

    private fun isSessionAllowed(host: String): Boolean =
        sessionAllowed.any { isSameSite(host, it) }

    /** 同站判定：完全相同，或互为子域（www.x.com ↔ x.com ↔ m.x.com） */
    private fun isSameSite(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.endsWith(".$b") || b.endsWith(".$a")
    }

    private fun hostOf(url: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u == "about:blank") return ""
        return runCatching { Uri.parse(u).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
    }

    private companion object {
        const val KEY_STRICT_MODE = "nav_strict_mode"
        const val CHAIN_WINDOW_MS = 4000L
    }
}
