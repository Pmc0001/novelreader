package com.example.novelreader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class UrlLoader(private val context: Context) {

    // 共享 OkHttp 单例：连接池复用 TCP/TLS、HTTP 响应缓存加速重复请求，避免每次 Jsoup.connect()
    // 都新建连接（无 keep-alive、无缓存），这是轻量通道提速的关键。
    companion object {
        @Volatile
        private var sharedClient: OkHttpClient? = null

        private fun client(ctx: Context): OkHttpClient {
            sharedClient?.let { return it }
            synchronized(this) {
                sharedClient?.let { return it }
                val cache = try {
                    Cache(File(ctx.applicationContext.cacheDir, "http_cache"), 20L * 1024 * 1024)
                } catch (_: Exception) {
                    null
                }
                val builder = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // OkHttp 默认「同一 host 最多 5 个并发」，而目录分页是 8 路并发抓的 ——
                    // 默认值会让每批多等一个 RTT（3 个请求排队），几十页的目录就是好几秒。
                    .dispatcher(okhttp3.Dispatcher().apply {
                        maxRequests = 32
                        maxRequestsPerHost = 8
                    })
                cache?.let { builder.cache(it) }
                return builder.build().also { sharedClient = it }
            }
        }

        /**
         * 预热 WebView 的 CookieManager（主线程调一次即可）。
         *
         * 为什么需要它：很多站点的反爬是「JS 算出 cookie 才能看正文」，WebView 能过、裸 OkHttp 过不了。
         * CookieManager 是全进程共享的，WebView 加载过的 cookie 我们直接拿来给 OkHttp 用，
         * 轻量通道的命中率会明显提升 —— 而轻量通道能不能用，直接决定「预取」能不能生效。
         */
        fun prewarmCookies() {
            try {
                android.webkit.CookieManager.getInstance()
            } catch (_: Throwable) {
            }
        }

        /** 取 WebView 已持有的 cookie，用于 OkHttp 请求；取不到就返回 null（不影响请求本身） */
        private fun cookiesFor(url: String): String? = try {
            android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }

        /** 把 WebView 内存里的 cookie 落盘，下次启动 App 依然有效 */
        private fun flushCookies() {
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
        }

        /**
         * 判断一段「正文」其实是浏览器/WebView 自带的错误页。
         *
         * 「网页无法打开」这类错误页只有几十个字的提示语，但会被正文提取逻辑当成一章内容抓出来。
         * 一旦它进了缓存，后面每次翻到这一章都会命中缓存、**永远不会重新请求** ——
         * 用户看到的现象就是「这章一直显示网页无法打开，而且怎么翻都不重试」。
         *
         * 判据：总长度很短（真实章节不可能 < 600 字）+ 命中错误页特征串。
         */
        internal fun looksLikeErrorPage(text: String, title: String = ""): Boolean {
            if (text.isBlank()) return true
            if (text.length > 600) return false
            // 去掉所有空白后再匹配：错误页文案里常夹着换行/不换行空格
            val t = (title + " " + text).replace(Regex("[\\s\\u00A0\\u200B\\uFEFF]+"), "")
            val keys = arrayOf(
                "网页无法打开", "无法访问此网站", "网页无法访问", "网页走丢了", "无法显示此网页",
                "net::ERR", "ERR_CONNECTION", "ERR_NAME_NOT_RESOLVED", "ERR_INTERNET_DISCONNECTED",
                "ERR_ADDRESS_UNREACHABLE", "ERR_TIMED_OUT", "ERR_SSL", "ERR_EMPTY_RESPONSE",
                "Webpagenotavailable", "Thissitecan'tbereached", "Checkyourconnection",
                "Thispageisn'tworking", "找不到网页"
            )
            return keys.any { t.contains(it, ignoreCase = true) }
        }

        // ===== 轻量通道的「站点记忆」=====
        // 反爬站点的轻量通道会一直失败，而用户读一章要一两分钟：
        // 若每章都先试一次轻量（再等满 5s 超时）才退 WebView，翻章永远慢半拍。
        // 这里按 host 记失败次数，连续失败到阈值就判定「这个站点只能走 WebView」，
        // 后续翻章直接跳过轻量尝试。
        private const val LIGHT_FAIL_HOST_THRESHOLD = 3
        private const val LIGHT_FAIL_SKIP_MS = 45_000L
        private val lightFailCount = HashMap<String, Int>()
        private val webViewOnlyHosts = HashSet<String>()
        private val lightFailAt = HashMap<String, Long>()

        private fun hostOf(url: String): String = try {
            android.net.Uri.parse(url).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }

        /** 这一章是否有必要再试轻量通道 */
        @Synchronized
        fun shouldSkipLight(url: String): Boolean {
            val host = hostOf(url)
            if (host.isNotBlank() && webViewOnlyHosts.contains(host)) return true
            val at = lightFailAt[url] ?: return false
            if (System.currentTimeMillis() - at > LIGHT_FAIL_SKIP_MS) {
                lightFailAt.remove(url)
                return false
            }
            return true
        }

        /** 该站点是否已被判定为「只能走 WebView」 */
        @Synchronized
        fun isWebViewOnly(url: String): Boolean {
            val host = hostOf(url)
            return host.isNotBlank() && webViewOnlyHosts.contains(host)
        }

        @Synchronized
        private fun noteLightResult(url: String, result: LoadResult) {
            val host = hostOf(url)
            if (result is LoadResult.Success) {
                if (host.isNotBlank()) {
                    lightFailCount.remove(host)
                    webViewOnlyHosts.remove(host)
                }
                lightFailAt.remove(url)
                return
            }
            lightFailAt[url] = System.currentTimeMillis()
            if (host.isBlank()) return
            // 过期条目顺手清掉，长篇阅读时这张表不会无限长
            if (lightFailAt.size > 400) {
                val cutoff = System.currentTimeMillis() - LIGHT_FAIL_SKIP_MS
                lightFailAt.entries.removeAll { it.value < cutoff }
            }
            // 只有「站点明确拒绝了内容」才算反爬证据：正文为空、Cloudflare 人机验证。
            // 网络异常 / 超时 / DNS 失败一律不算 —— 那些是网络问题，不是站点策略。
            // 把慢站的超时也算进来，会导致整站被误判成「只能走 WebView」，
            // 明明 2s 能出内容的轻量通道被彻底跳过，翻章反而更慢。
            val msg = (result as? LoadResult.Error)?.message.orEmpty()
            val siteRejected = msg.contains("正文为空") || msg.contains("反爬") || msg.contains("验证")
            if (!siteRejected) return
            val failures = (lightFailCount[host] ?: 0) + 1
            lightFailCount[host] = failures
            if (failures >= LIGHT_FAIL_HOST_THRESHOLD) webViewOnlyHosts.add(host)
        }

        // 正文容器候选（取「清洗后文字最多」的那个，顺序不影响结果）
        private val CONTENT_SELECTORS = arrayOf(
            // 中文站最常见的一批正文容器（novelcontent / content_novel 这类命名命中率很高）
            "#novelcontent", ".novelcontent", ".content_novel", "#chaptercontent", ".chapter_content",
            "#booktxt", "#booktext", "#nr", "#nr1", ".nr", ".showtxt",
            "div.content", ".content", "#content", "div.chaptercontent", ".chaptercontent",
            "#chaptercontent", "article", ".article", "#article", "div.read", ".read", "#read",
            "div.txt", ".txt", ".booktext", ".read-content", ".novel-content", ".chapter-content"
        )

        private val BLOCK_TAGS = setOf(
            "p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "section", "article", "dd", "dt", "tr", "td", "th",
            "pre", "figcaption", "main"
        )

        // 整词匹配（- / _ / 空格 / 首尾都算边界）。旧实现用 cls.contains("ad")，
        // 会把 class="read" / "load" / "head" / "shadow" 这类正常容器判成广告整块删掉。
        private val JUNK_CLASS_RE = Regex(
            "(^|[\\s_-])(ad|ads|adv|advert\\w*|adsbygoogle|recommend\\w*|nav|navbar|navi|menu|footer|header|" +
                "breadcrumb|pagination|page-link|bottom-link|top-link|popup|modal|comment\\w*|share|" +
                "sidebar|copyright|toolbar|banner)([\\s_-]|$)"
        )
        private val JUNK_ID_RE = Regex(
            "(^|[\\s_-])(ad|ads|nav|navbar|navi|menu|footer|header|comment|share|sidebar|banner)([\\s_-]|$)"
        )

        // 章内分页拼接用的占位分隔符（正文里不会出现 NUL）：清洗时靠它识别「页界」，
        // 判断跨页处是不是把一句话劈成了两半，需要续写而不是硬断段。
        private const val PAGE_SEP = "\u0000"
        // 句末标点：上页以这些字符结尾说明句子已结束，下页另起一段
        private val SENTENCE_END_CHARS = setOf(
            '。', '！', '？', '…', '”', '』', '」', '’', '.', '!', '?', '"'
        )
    }

    // 轻量 HTTP 通道（OkHttp 单例 + Jsoup 解析），用于搜索/章节/目录的快速尝试，避免每次都起 WebView。
    // 轻量通道一旦成功即可跳过昂贵的 WebView，因此给予较充裕的预算让其完整抓取（含章内分页），
    // 避免被 5s 掐断后整体回退到更慢的 WebView 通道。
    private val LIGHT_FETCH_TIMEOUT_MS = 15000L
    // 目录分页单页硬超时：站点在并发抓取时会丢掉一部分连接，不设超时的话一个卡住的页
    // 会把整批（8 页）一起拖到 10s 的读超时，白丢一整批时间。
    private val TOC_PAGE_TIMEOUT_MS = 6000L
    // 目录分页补全的独立预算：移动站目录常见「每页 20 章、上百页」，7s 只能抓几十页，
    // 大书就永远停在首页那 20 章。这里给足时间，并且超时也保留已抓到的部分（可断点续抓）。
    private val TOC_PAGINATION_BUDGET_MS = 90_000L
    // 每批并发抓多少页：与 OkHttp 的 maxRequestsPerHost 对齐；再高会被站点限流（实测 8 路时
    // 部分请求直接超时），6 路是"快"和"稳"的平衡点
    private val TOC_PAGE_BATCH = 6
    private val LIGHT_CONN_TIMEOUT_MS = 10000
    // 单页抓取的硬超时区间：低于这个值几乎必然失败，高于这个值就等于放弃预算控制
    private val MIN_PAGE_FETCH_MS = 1500L
    private val MAX_PAGE_FETCH_MS = 10000L

    private val UA = "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

    // 轻量通道通用正则/常量
    private val lightNextRe = Regex("下一页|下一章|下一節|下一节|下章|下一|后一章|後一章|下篇|下一篇|次回|继续阅读|繼續閱讀|next chapter|next", RegexOption.IGNORE_CASE)
    private val lightJunkRe = Regex("首页|书架|排行|分类|完本|书库|搜索|登录|注册|上一页|下一页|尾页|末页|加入书架|收藏|投票|推荐|月票|打赏|评论|最新章节|返回|下载|txt下载|阅读器|手机阅读|广告|客服|标签|作者|目录|更多|热门|从头阅读|开始阅读|立即阅读|点击阅读|阅读记录|阅读历史|浏览记录|浏览历史|上一章|下一章|上一節|下一節", RegexOption.IGNORE_CASE)
    private val lightChapterFileRe = Regex("\\.(html?|php|asp|aspx|jsp|shtml|do|action)$", RegexOption.IGNORE_CASE)

    // 章节标题特征（与 WebView 层 chapterTitleRe 等价的轻量版）：用于把「同书链接」之外
    // 的候选限制为真正的章节样式，避免把目录页上的站点名/排行榜/阅读记录等全站导航当章节。
    private val lightChapterTitleRe = Regex(
        "(?i)(第\\s*[0-9〇零一二三四五六七八九十百千万两]+\\s*[章回卷节篇集部话])" +
            "|^(?:chapter|ch|part|episode|ep|vol)[\\s.\\-]*[0-9]{1,5}" +
            "|^\\s*[0-9]{1,5}\\s*[、.．:：,，]"
    )

    // 纯导航/分页标题（整词匹配）：JS 提取层偶发把「上一页/下一页」等翻页链接当章节收进目录，
    // 用整词精确匹配过滤，避免误伤含这些词的真实章节名（如「第10章 大推荐」不受影响）。
    private val tocNavTitleRe = Regex(
        "(?i)^[\\s‹›«»\\[\\]()（）·・\\-—_=~/\\\\]*((上一页)|(下一页)|(上頁)|(下頁)|(上一章)|(下一章)|(首页)|(首頁)|(尾页)|(尾頁)|(末页)|(末頁)|(第一页)|(最後一页)|(最后一页)|((返回)?目录)|(返回书页)|(书架)|(書架)|(返回)|(封面)|(最新章节)|(全本)|(完本)|(报错)|(加入书签)|(从头阅读)|(开始阅读)|(立即阅读)|(点击阅读)|(阅读记录)|(阅读历史)|(浏览记录)|(浏览历史)|(回到顶部)|(继续阅读))[\\s‹›«»\\[\\]()（）·・\\-—_=~/\\\\]*\$"
    )

    // 反爬混淆归一化（仅用于导航词/目录入口/翻页的匹配过滤，不改变展示标题）：
    // 部分站点（如 kudushu.org）把「上一章」写成「上—章」（U+2014 一字线冒充「一」）、
    // 把「目录」写成「返&nbsp回&nbsp目&nbsp录」（&nbsp 实体混入字缝），令导航词黑名单、
    // 「目录」入口识别与章内翻页识别全部失效，最终目录里只剩「上一页/下一页」式假条目。
    // 匹配前统一处理：先剥掉 &nbsp 类实体残留与所有空白/\u00A0/零宽字符，再把破折号类字符归一为「一」。
    private val entityNbspRe = Regex("&(#160|#x0*a0|nbsp)(;)?", RegexOption.IGNORE_CASE)
    private val invisibleWsRe = Regex("[\\s\u00A0\u2007\u202F\u200B\uFEFF]+")
    private val dashLikeRe = Regex("[\u00AD\u2010\u2011\u2013\u2014\u2015\u2043\u2212\uFF0D\uFE63-]")

    private fun stripInvisibleWs(s: String): String =
        s.replace(entityNbspRe, "").replace(invisibleWsRe, "")

    private fun navNorm(s: String): String =
        stripInvisibleWs(s).replace(dashLikeRe, "一")

    // 资源拦截：正文/目录提取只关心 DOM 文本，图片/音视频/字体与提取无关，
    // 拦截后可大幅缩短 WebView 的页面加载耗时（不必等广告图、封面等大资源下载）。
    private val blockableResRe = Regex(
        "\\.(png|jpe?g|gif|webp|bmp|svg|mp3|mp4|webm|ogg|woff2?|ttf|otf|eot|ico)(\\?|#|$)",
        RegexOption.IGNORE_CASE
    )

    private fun isBlockableResource(url: String): Boolean {
        val u = url.substringBefore('#').substringBefore('?')
        return blockableResRe.containsMatchIn(u)
    }

    // 折叠空白用的正则：目录页动辄几千个链接，旧实现在循环里现写 "\\s+".toRegex()，
    // 等于每条链接都编译一次正则 —— 几千条就是几千次正则编译，纯属白烧 CPU。
    private val wsCollapseRe = Regex("\\s+")

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    sealed class LoadResult {
        data class Success(
            val title: String,
            val content: String,
            val nextChapterUrl: String? = null,
            val bookTitle: String? = null,
            // 章节页上解析出的「上一章」。旧行为只解析「下一章」，导致从目录直接跳章后
            // 回翻栈为空、按钮变灰 —— 明明页面上就有「上—章」链接却没被用上。
            val prevChapterUrl: String? = null
        ) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    data class ChapterItem(val title: String, val url: String)

    sealed class ChapterListResult {
        /**
         * @param catalogUrl 真正解析出章节列表的那个页面（书页/目录页）。调用方把它记下来，
         *   下次刷新目录直接从这一页起爬，能省掉「先抓章节页 → 再找目录入口」的整整一个 RTT。
         * @param complete 是否已经把全部分页都抓完了。false 表示只拿到前几页（大书目录动辄上百页），
         *   调用方不能把它当成"最终目录"长期缓存，下次还得接着补。
         * @param nextPageUrl 下一个还没抓的分页地址（断点续抓用）；complete=true 时为 null。
         */
        data class Success(
            val items: List<ChapterItem>,
            val catalogUrl: String? = null,
            val complete: Boolean = true,
            val nextPageUrl: String? = null
        ) : ChapterListResult()
        data class Error(val message: String) : ChapterListResult()
    }

    private data class PageData(
        val title: String,
        val content: String,
        val nextPage: String,
        val totalPages: Int?,
        val docTitle: String = "",
        val bodyLen: Int = 0,
        val isChallenge: Boolean = false,
        // 页面上所有「下一页 / 下一章」链接（已解析成绝对地址、已去重）。
        // 章内分页链接和「下一章」在 DOM 里通常并存，旧实现只取第一条命中的 a 标签，
        // 常常先命中顶部导航的「下一章」→ 被当成跨章链接直接 break → 每章只抓到第 1 页。
        val nextCandidates: List<String> = emptyList(),
        // 页面上的「上一章」候选（不含「上一页」分页链接）
        val prevCandidates: List<String> = emptyList()
    )

    /**
     * 加载一章正文。
     *
     * @param skipLight 跳过轻量通道、直接走 WebView。调用方已知该章刚刚在轻量通道失败过时使用
     *   （反爬站点常见）：省掉一次「注定失败、还要等满超时」的尝试，这一等往往是十几秒。
     * @param lightBudgetMs 轻量通道总预算（毫秒），<=0 用默认值。前台翻章给较短预算，
     *   失败就立刻退 WebView；后台预取给足预算（慢点无妨，成功一次就能省掉后面所有等待）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadUrl(
        url: String,
        skipLight: Boolean = false,
        lightBudgetMs: Long = 0L
    ): LoadResult = withContext(Dispatchers.Main) {
        // 外层兜底：WebView 构造、attachWebView 原本在 try 之外，一旦抛异常会绕过
        // catch(Exception) 直接冒泡到 lifecycleScope.launch，导致整个应用崩溃（回到书架主页）。
        // 这里用外层 try + catch(Throwable) 把"加载失败"统一收敛为 Error，绝不向上抛。
        // 轻量通道优先：OkHttp+Jsoup 直接拉取并解析正文，成功则跳过 WebView（快、省电、不转圈）。
        // 站点级记忆：这个 host 已经被判定「轻量过不去」时直接跳过，省掉一次注定超时的等待。
        val skip = skipLight || shouldSkipLight(url)
        if (!skip) {
            try {
                val light = fetchChapterLight(url, lightBudgetMs)
                if (light is LoadResult.Success) {
                    Log.d("UrlLoader", "loadUrl: light channel hit, len=${'$'}{light.content.length}")
                    return@withContext light
                }
            } catch (e: Exception) {
                Log.d("UrlLoader", "loadUrl: light channel failed, fallback to WebView: $e")
            }
        } else {
            Log.d("UrlLoader", "loadUrl: light channel skipped (site marked WebView-only)")
        }
        var webViewRef: WebView? = null
        var rootRef: ViewGroup? = null
        try {
        val webView = WebView(context.applicationContext)
        webViewRef = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.userAgentString = UA

        val host = Uri.parse(url).host ?: ""
        val allowedSuffix = if (host.count { it == '.' } >= 2) host.substringAfter('.') else host

        var pageFinishedCallback: (() -> Unit)? = null
        // 主文档的网络层错误（DNS 失败 / 连接被拒 / 超时 / 证书错误）。
        // 关键：WebView 加载失败时也会触发 onPageFinished，页面内容是它自带的「网页无法打开」，
        // 如果不管这个标记，这段错误页会被当成正文提取、甚至写进缓存 —— 之后就再也不会重新请求了。
        var mainFrameError: String? = null

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqHost = request?.url?.host ?: return null
                if (!isAllowedRequest(reqHost, host, allowedSuffix)) {
                    return emptyResponse()
                }
                if (isBlockableResource(request.url.toString())) {
                    return emptyResponse()
                }
                return null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 只看主文档：广告/统计脚本的失败不该让整章判定为失败
                if (request?.isForMainFrame == true) {
                    mainFrameError = "${error?.errorCode}: ${error?.description}"
                    Log.d("UrlLoader", "mainframe error [$mainFrameError] url=${request.url}")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                super.onReceivedSslError(view, handler, error)
                if (mainFrameError == null) mainFrameError = "SSL ${error?.primaryError}"
                // 不放行：证书有问题就换协议重试，不静默忽略安全错误
                handler?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 忽略新建 WebView 初始的 about:blank，避免提前恢复导致提取到空页面
                if (url == null || url == "about:blank") return
                pageFinishedCallback?.invoke()
            }
        }

        val root = (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)
        rootRef = root
        attachWebView(webView, root)

        try {
            val result = withTimeout(45000) {
            suspend fun navigate(target: String) {
                withTimeout(20000) {
                    suspendCancellableCoroutine { cont ->
                        var done = false
                        pageFinishedCallback = {
                            if (!done && cont.isActive) {
                                done = true
                                pageFinishedCallback = null
                                cont.resume(Unit)
                            }
                        }
                        cont.invokeOnCancellation {
                            pageFinishedCallback = null
                            webView.stopLoading()
                        }
                        webView.loadUrl(target)
                    }
                }
            }

            suspend fun extract(): PageData = withTimeout(15000) {
                suspendCancellableCoroutine { cont ->
                    val js = """(function(){
                        function cleanText(s){
                            var r=(s||'');
                            // 页码标记「(第1/3页)」不能连后面的空白一起吃掉：章节标题和正文首句
                            // 本来只靠这处换行分隔，整段删除会把它们粘成一行。换成换行即可。
                            r=r.replace(/[ \t]*[（(]\s*第\s*\d+\s*[\/／]\s*\d+\s*页\s*[）)][ \t]*/g,'\n');
                            r=r.replace(/[ \t]+/g,' ');
                            r=r.replace(/\n+/g,'\n');
                            r=r.replace(/^\s+|\s+$/g,'');
                            return r;
                        }
                        // 反爬混淆归一化：「下—页」(U+2014 冒充一)、「&nbsp」实体混入等会令翻页链接识别失效，
                        // 导致章内分页断链、正文只抓到第 1 页。仅用于匹配，不改变正文内容。
                        function normTxt(s){
                            return (s||'').replace(/&(#160|x0*a0|nbsp);?/gi,'')
                                .replace(/[\s\u00A0\u2007\u202F\u200B\uFEFF]+/g,'')
                                .replace(/[\u00AD\u2010\u2011\u2013\u2014\u2015\u2043\u2212\uFF0D\uFE63-]/g,'一');
                        }
                        function isJunk(el){
                            var t=(el.tagName||'').toLowerCase();
                            if(t==='script'||t==='style'||t==='nav'||t==='header'||t==='footer'||t==='aside'||t==='iframe'||t==='ins') return true;
                            if(t==='img'||t==='figure'||t==='video'||t==='audio') return true;
                            var c=(el.className||'').toString().toLowerCase();
                            if(/recommend|page-link|bottom-link|top-link|\bad\b|popup|modal|sidebar|comment|share|header|footer|breadcrumb|pagination|copyright/.test(c)) return true;
                            return false;
                        }
                        // 只拆掉标签、保留其中的文字（用于 <a>）。
                        // 直接 remove() 会连带删掉链接里的文字，正文出现内链就会缺字。
                        function unwrapEls(root, sel){
                            var list=root.querySelectorAll(sel);
                            for(var i=0;i<list.length;i++){
                                var n=list[i], p=n.parentNode;
                                if(!p) continue;
                                while(n.firstChild) p.insertBefore(n.firstChild, n);
                                p.removeChild(n);
                            }
                        }
                        function isEmpty(el){
                            if(!el) return true;
                            var t=(el.textContent||'').trim();
                            if(t) return false;
                            if(el.querySelector('img,video,audio,canvas,svg')) return false;
                            return true;
                        }
                        function collectText(el){
                            if(isJunk(el)) return '';
                            var tag=(el.tagName||'').toLowerCase();
                            if(tag==='br') return '\n';
                            if(tag==='p'||tag==='div'||tag==='li'||tag==='h1'||tag==='h2'||tag==='h3'||tag==='h4'||tag==='h5'||tag==='h6'){
                                var inner='';
                                var ch=el.childNodes;
                                for(var i=0;i<ch.length;i++){
                                    var n=ch[i];
                                    if(n.nodeType===3){ var v=n.nodeValue||''; v=v.trim(); if(v) inner+=v; }
                                    else if(n.nodeType===1){ inner+=collectText(n); }
                                }
                                if(!inner.trim()) return '';
                                return inner.trim()+'\n';
                            }
                            var txt='';
                            var ch2=el.childNodes;
                            for(var j=0;j<ch2.length;j++){
                                var n2=ch2[j];
                                if(n2.nodeType===3){ var v2=n2.nodeValue||''; v2=v2.trim(); if(v2) txt+=v2+' '; }
                                else if(n2.nodeType===1){ txt+=collectText(n2); }
                            }
                            return txt.trim();
                        }
                        var cands=[
                            document.getElementById('content'),
                            document.getElementById('chaptercontent'),
                            document.getElementById('booktxt'),
                            document.getElementById('txt'),
                            document.getElementById('readcontent'),
                            document.querySelector('#content,.content,#con,.con,#booktxt,#chaptercontent,#readcontent,#booktext,.booktext,.novel-content,.read-content,.book-text,.chapter-content,#chapter_box,#acontent,.article-content,.post-content,.read-text,.txtcontent,.article,.chapter,#novelcontent,.novelcontent,.content_novel,.chapter_content,.showtxt')
                        ];
                        var best=null,bestLen=0,lens=[];
                        for(var i=0;i<cands.length;i++){
                            var el=cands[i]; if(!el) continue;
                            var len=collectText(el).trim().length;
                            lens.push([el,len]);
                            if(len>bestLen) bestLen=len;
                        }
                        // 多个候选命中时优先「声明靠前、层级更内」的容器：只要文字量不低于最大值的 60%
                        // 就选它，避免父容器（把页内导航一起包住）因为更长而胜出。
                        for(var i3=0;i3<lens.length;i3++){
                            if(lens[i3][1]>=bestLen*0.6){ best=lens[i3][0]; break; }
                        }
                        if(bestLen<50){
                            // 兜底：从 body 起逐层下沉到「独占父级大半文字量」的子节点。
                            // 旧实现取「全页文字最多的元素」，结果永远命中 html/body（它包住了
                            // 整站导航），正文里就混进了书名、护眼/关灯、上一章/下一章。
                            var cur=document.body, curLen=cur?collectText(cur).trim().length:0, guard=0;
                            while(cur && guard++<12){
                                var nxt=null, nlen=0, ch=cur.children||[];
                                for(var j2=0;j2<ch.length;j2++){
                                    if(isJunk(ch[j2])) continue;
                                    var l2=collectText(ch[j2]).trim().length;
                                    if(l2>nlen){ nlen=l2; nxt=ch[j2]; }
                                }
                                if(!nxt || nlen<curLen*0.5) break;
                                cur=nxt; curLen=nlen;
                            }
                            if(cur && cur!==document.body){ best=cur; bestLen=curLen; }
                        }
                        var x='', t='', total=null;
                        if(best){
                            var clone=best.cloneNode(true);
                            // 注意：不能把 <a> 放进 remove 列表。节点 remove() 会把里面的文字一起删掉，
                            // 而很多站点会把正文里的书名/人名/关键词包成内链 —— 删掉就是整段少字。
                            clone.querySelectorAll('script,style,nav,header,footer,aside,img,figure,iframe,ins,.recommend,.page-link,.bottom-link,.top-link,.ad,.popup,.modal,.comment,.share,.breadcrumb,.pagination,.copyright').forEach(function(n){n.remove();});
                            unwrapEls(clone,'a');
                            var imgs=clone.querySelectorAll('div,p,span,strong,em,i,b,h1,h2,h3,h4,h5,h6,li,td,th,blockquote,pre');
                            for(var k=0;k<imgs.length;k++){
                                if(!imgs[k].querySelector('img') && imgs[k].textContent.trim()===''){ imgs[k].remove(); }
                            }
                            x=cleanText(collectText(clone));
                            var h=best.querySelector('h1,h2,h3,h4')||document.querySelector('#chaptertitle,#chaptername,.chapter-title,.bookname h1,h1');
                            t=(h?h.innerText:'')||document.title||'';
                            t=cleanText(t);
                        }
                        if(!x){
                            var body=document.body?document.body.cloneNode(true):null;
                            if(body){
                                body.querySelectorAll('script,style,nav,header,footer,aside,img,figure,iframe,.recommend,.page-link,.bottom-link,.ad,.popup,.modal').forEach(function(n){n.remove();});
                                unwrapEls(body,'a');
                                x=cleanText(collectText(body));
                            }
                            t=document.title||'';
                        }
                        var mm=t.match(/第\d+\/(\d+)页/);
                        if(mm) total=parseInt(mm[1],10);
                        // 收集全部「下一页 / 下一章」候选：由 Kotlin 侧按 URL 基名区分
                        // 「同章分页」与「下一章」，避免第一条就是「下一章」时章内分页被漏抓。
                        var links=document.querySelectorAll('a');
                        var next='', nexts=[];
                        var nextRe=/下一页|下一頁|下页|下頁|下一章|下一節|下一节|下章|下一|后一章|後一章|下篇|下一篇|次回|继续阅读|繼續閱讀|next chapter|next/i;
                        for(var k=0;k<links.length;k++){
                            var lt=normTxt((links[k].textContent||'')+' '+(links[k].getAttribute('title')||''));
                            var cls=(links[k].className||'').toString().toLowerCase();
                            var rel=(links[k].getAttribute('rel')||'').toLowerCase();
                            if(nextRe.test(lt) || cls.indexOf('next')>=0 || rel==='next'){
                                var href=links[k].getAttribute('href')||'';
                                if(href && href.indexOf('javascript:')!==0){
                                    try{
                                        var nu=new URL(href, location.href).href;
                                        if(nu.split('#')[0]!==location.href.split('#')[0] && nexts.indexOf(nu)<0) nexts.push(nu);
                                    }catch(e){}
                                }
                            }
                        }
                        if(!nexts.length && typeof nextpage !== 'undefined' && nextpage){ if((''+nextpage).indexOf('javascript:')!==0){ try{ nexts.push(new URL(''+nextpage, location.href).href); }catch(e){ nexts.push(''+nextpage); } } }
                        next=nexts.length?nexts[0]:'';
                        // 单独收集「上一章」：只认章级词（上一章/上章/上一篇），
                        // 不能把「上一页」算进来 —— 那是章内分页，不是上一章。
                        var prevs=[];
                        var prevRe=/上一章|上一頁|上章|上一節|上一节|上一篇|前一章|回上一章|prev/i;
                        for(var k3=0;k3<links.length;k3++){
                            var lt3=normTxt((links[k3].textContent||'')+' '+(links[k3].getAttribute('title')||''));
                            if(prevRe.test(lt3)){
                                var href3=links[k3].getAttribute('href')||'';
                                if(href3 && href3.indexOf('javascript:')!==0){
                                    try{
                                        var nu3=new URL(href3, location.href).href;
                                        if(nu3.split('#')[0]!==location.href.split('#')[0] && prevs.indexOf(nu3)<0) prevs.push(nu3);
                                    }catch(e){}
                                }
                            }
                        }
                        var dt=document.title||'';
                        var bl=document.body?document.body.innerHTML.length:0;
                        var bd=document.body?document.body.innerText:'';
                        var dtl=(dt||'').toLowerCase();
                        var bdl=(bd||'').toLowerCase();
                        var cf=0;
                        if(dtl.indexOf('just a moment')>=0 || dtl.indexOf('attention required')>=0 || bdl.indexOf('enable javascript and cookies')>=0 || bdl.indexOf('checking your browser')>=0){ cf=1; }
                        return JSON.stringify({t:t,x:x,next:next,nexts:nexts,prevs:prevs,total:total,dt:dt,bl:bl,cf:cf});
                    })()"""
                    webView.evaluateJavascript(js) { raw ->
                        if (cont.isActive) cont.resume(parsePage(raw))
                    }
                }
            }

            suspend fun waitForContent(): PageData {
                var data = extract()
                var attempt = 0
                var lastLen = -1
                var stableRounds = 0
                // 轮询有两个目的：
                // 1. 内容为空 / 还是验证页 → 继续等 JS 渲染；
                // 2. **内容长度稳定后才算完成** —— Cloudflare 验证通过后页面是逐步填充的，
                //    旧实现「非空即返回」会抓到只渲染了一半的正文，用户看到的就是「这章少了半截」。
                //    代价约 300ms，只发生在 WebView 这条本来就慢的路径上，值得。
                while (attempt < 30) {
                    if (data.isChallenge || data.content.isBlank()) {
                        delay(200)
                    } else if (data.content.length == lastLen) {
                        stableRounds++
                        if (stableRounds >= 2) break
                        delay(150)
                    } else {
                        stableRounds = 0
                        lastLen = data.content.length
                        delay(150)
                    }
                    data = extract()
                    attempt++
                }
                return data
            }

            navigate(url)
            var effectiveUrl = url
            var first = waitForContent()
            Log.d("UrlLoader", "page1 len=${first.content.length} next=${first.nextPage} title=[${first.title}] challenge=${first.isChallenge} err=$mainFrameError")

            // 主文档失败（网络错误，或抓到的是 WebView 自带的「网页无法打开」错误页）：换协议再试一次。
            // 小说站目录里大量链接是 http://，实际站点只稳定支持 https（或反过来），
            // WebView 加载 http 会被拒、弹出「网页无法打开」——换协议往往一次就通。
            // 判据必须带 mainFrameError：不能因为「内容为空」就重试 ——
            // 那多半只是页面还在渲染，重试会打断它，反而抓到半截内容。
            val firstLooksBad = first.content.isBlank() || looksLikeErrorPage(first.content, first.title)
            if (firstLooksBad && !first.isChallenge && mainFrameError != null) {
                val alt = swapScheme(url)
                if (alt != null) {
                    Log.d("UrlLoader", "retrying with $alt (previous error: $mainFrameError)")
                    mainFrameError = null
                    try {
                        navigate(alt)
                        effectiveUrl = alt
                    } catch (e: TimeoutCancellationException) {
                        Log.d("UrlLoader", "retry navigate timeout: $alt")
                    }
                    first = waitForContent()
                }
            }
            if (first.content.isBlank() && mainFrameError != null) {
                return@withTimeout LoadResult.Error("网页无法打开（$mainFrameError）")
            }
            if (first.isChallenge && first.content.isBlank()) {
                return@withTimeout LoadResult.Error("该网站启用了 Cloudflare 人机验证，WebView 无法自动通过。请先在系统浏览器中打开该链接、通过验证后复制正文，或改用不受验证的站点。")
            }
            if (looksLikeErrorPage(first.content, first.title)) {
                return@withTimeout LoadResult.Error("网页无法打开（页面返回的是浏览器错误页）")
            }
            // 用「实际加载成功的那个 URL」算章节基准：换过协议后若还用原 URL 的 base，
            // 页面上 https 的分页链接会被判成「不同章」，结果只抓到第 1 页 —— 正文就少了一大截。
            val originalBase = chapterBase(effectiveUrl)
            val sb = StringBuilder(first.content)
            var title = first.title
            var idx = 1
            var total = first.totalPages
            var guard = 0
            // 同章分页与「下一章」分开取：页面上两者常常同时存在，若把第一条命中当分页用，
            // 一旦这条是「下一章」就会 break，只留下第 1 页（用户看到的就是"这章少了一截"）。
            var next: String? = pickSameChapterPage(first.nextCandidates, originalBase)
            var nextChapterUrl: String? = pickNextChapter(first.nextCandidates, originalBase)
            // 第 1 页上的「上一章」才是真正的上一章（后几页只有「上一页」分页链接）
            val prevChapterUrl: String? = pickPrevChapter(first.prevCandidates, originalBase)
            val visitedPages = mutableSetOf(normPageKey(effectiveUrl))

            while (guard < 30) {
                val page = next?.takeIf { it.isNotBlank() } ?: break
                guard++
                if (total != null && idx >= total) break
                if (!visitedPages.add(normPageKey(page))) break
                navigate(page)
                val p = waitForContent()
                Log.d("UrlLoader", "page$idx len=${p.content.length} next=${p.nextPage} dt=[${p.docTitle}] bl=${p.bodyLen}")
                if (p.content.isBlank()) {
                    // 正文没抓到也要先把这一页的「下一章」链接收下来：
                    // 分页章节往往只有最后一页才放「下一章」，这一页恰好被反爬拦一下，
                    // 整章就会变成「没有下一章」（按钮变灰、提示已经是最后一章）。
                    if (nextChapterUrl.isNullOrBlank()) {
                        nextChapterUrl = pickNextChapter(p.nextCandidates, originalBase)
                    }
                    break
                }
                if (total == null && p.totalPages != null) total = p.totalPages
                if (title.isBlank()) title = p.title
                sb.append(PAGE_SEP).append(p.content)
                idx++
                if (nextChapterUrl.isNullOrBlank()) {
                    nextChapterUrl = pickNextChapter(p.nextCandidates, originalBase)
                }
                next = pickSameChapterPage(p.nextCandidates, originalBase)
            }

            if (sb.isBlank()) {
                Log.d("UrlLoader", "result: EMPTY dt=[${first.docTitle}] bl=${first.bodyLen}")
                val hint = if (first.bodyLen > 2000) "（页面已加载但未能识别正文，可能是非常规排版）" else "（页面可能未加载/被反爬拦截）"
                LoadResult.Error("未能提取到正文$hint")
            } else {
                Log.d("UrlLoader", "merged pages=$idx len=${sb.length} title=[$title] next=[$nextChapterUrl]")
                val cleaned = sanitizeContent(sb.toString(), title)
                // 最后一道闸：WebView 的「网页无法打开」错误页也会被提取成文本，
                // 一旦当成正文返回，就会被缓存下来，之后永远不再请求（用户看到的就是"有缓存也不重试"）。
                if (looksLikeErrorPage(cleaned, title)) {
                    Log.d("UrlLoader", "result looks like browser error page, reject")
                    LoadResult.Error("网页无法打开（页面返回的是浏览器错误页）")
                } else {
                    // WebView 已经把站点 cookie 拿到手了：落盘 + 交给后续的轻量请求复用，
                    // 这样同一个站点后面的章节就不必再起 WebView
                    flushCookies()
                    LoadResult.Success(
                        title = title.ifBlank { "未知标题" },
                        content = cleaned,
                        nextChapterUrl = nextChapterUrl,
                        prevChapterUrl = prevChapterUrl
                    )
                }
            }
            }
            result
        } catch (e: TimeoutCancellationException) {
            LoadResult.Error("加载超时（45秒），该站点可能启用了反爬验证或网络较慢")
        } catch (e: Exception) {
            LoadResult.Error("加载失败: ${e.message}")
        } finally {
            try { root?.removeView(webView) } catch (_: Exception) { }
            webView.destroy()
        }
        } catch (e: Throwable) {
            // 兜底：捕获 WebView 构造/attachWebView 阶段或任何 Error（如 OOM）抛出的异常，
            // 避免冒泡到 lifecycleScope.launch 导致整个应用崩溃、阅读页被回收回主页。
            try { webViewRef?.let { rootRef?.removeView(it) } } catch (_: Exception) { }
            try { webViewRef?.destroy() } catch (_: Exception) { }
            LoadResult.Error("加载失败: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * 加载整条目录。
     *
     * @param onFirstPage 目录「首页」解析完成的回调（在 IO 线程触发）。多页目录要抓到第 2、3… 页
     *   才能凑齐全部章节，但第一章一定在首页里 —— 打开一本书时先用这份首页列表进入正文，
     *   不必等整条目录爬完（大书目录动辄几十页，能省下数秒的空白等待）。
     */
    suspend fun loadChapterList(
        url: String,
        onFirstPage: ((List<ChapterItem>) -> Unit)? = null,
        // 断点续抓：上次抓到哪一页（大书目录有上百页，一次会话抓不完，下次从这里接着抓）
        resumeFrom: String? = null,
        // 已经抓到的章节（通常是磁盘缓存里的）：续抓时要以它为底，否则"续抓"回来的
        // 只有后面那几十页，反而把前面已经有的章节丢了
        baseItems: List<ChapterItem> = emptyList(),
        // 边抓边回吐：(合并后的目录, 目录页地址, 下一个待抓分页)。调用方据此增量刷新 UI 并保存断点
        onProgress: ((List<ChapterItem>, String, String?) -> Unit)? = null
    ): ChapterListResult = withContext(Dispatchers.Main) {
        // webView 创建与销毁兜底由下方 return@withContext try/catch/finally 统一处理
        // 轻量通道优先：直接拉目录页解析章节列表，命中则跳过 WebView。
        try {
            val light = fetchChapterListLight(url, onFirstPage, resumeFrom, baseItems, onProgress)
            if (light != null && light.items.isNotEmpty()) {
                Log.d(
                    "UrlLoader",
                    "loadChapterList: light hit, ${light.items.size} chapters, complete=${light.complete}"
                )
                return@withContext ChapterListResult.Success(
                    light.items, light.catalogUrl, light.complete, light.nextPageUrl
                )
            }
        } catch (e: Exception) {
            Log.d("UrlLoader", "loadChapterList: light channel hit failed, fallback to WebView: $e")
        }
        // 已经有目录缓存了：轻量通道这次失败（站点限流/临时故障）就先算了。
        // WebView 通道最多只爬 60 页、比轻量通道慢十几倍，用它重爬一本上千章的书
        // 只会把"已经有的 1500 章"换成"更少的 1200 章"，得不偿失。
        if (baseItems.isNotEmpty()) {
            return@withContext ChapterListResult.Error("目录刷新失败，继续使用已有缓存")
        }
        var webViewRef: WebView? = null
        var rootRef: ViewGroup? = null
        val webView = WebView(context.applicationContext)
        webViewRef = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.userAgentString = UA

        val host = Uri.parse(url).host ?: ""
        val allowedSuffix = if (host.count { it == '.' } >= 2) host.substringAfter('.') else host

        var pageFinishedCallback: (() -> Unit)? = null
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqHost = request?.url?.host ?: return null
                if (!isAllowedRequest(reqHost, host, allowedSuffix)) {
                    return emptyResponse()
                }
                if (isBlockableResource(request.url.toString())) {
                    return emptyResponse()
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank") return
                pageFinishedCallback?.invoke()
            }
        }

        val root = (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)
        rootRef = root
        attachWebView(webView, root)

        suspend fun navigateAndWait(target: String) {
            withTimeout(20000) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    pageFinishedCallback = {
                        if (!done && cont.isActive) {
                            done = true
                            pageFinishedCallback = null
                            cont.resume(Unit)
                        }
                    }
                    cont.invokeOnCancellation {
                        pageFinishedCallback = null
                        webView.stopLoading()
                    }
                    webView.loadUrl(target)
                }
            }
        }

        return@withContext try {
            val parsed = Uri.parse(url)
            val path = parsed.path ?: ""
            val scheme = parsed.scheme ?: "https"
            val host = parsed.host ?: ""
            val baseUrl = "$scheme://$host"

            var listBaseUrl = url

            // 策略1：先导航到当前章节页面，提取"目录"链接（最可靠）
            Log.d("UrlLoader", "chapterList: first navigating to chapter page: $url")
            navigateAndWait(url)

            val tocLinkJs = """(function(){
                // norm：剥掉「返&nbsp回&nbsp目&nbsp录」式实体/空白混淆，并把「—」类一字线归一为「一」，再整词匹配
                function norm(s){
                    return (s||'').replace(/&(#160|x0*a0|nbsp);?/gi,'')
                        .replace(/[\s\u00A0\u2007\u202F\u200B\uFEFF]+/g,'')
                        .replace(/[\u00AD\u2010\u2011\u2013\u2014\u2015\u2043\u2212\uFF0D\uFE63-]/g,'一');
                }
                var all=document.getElementsByTagName('a');
                for(var i=0;i<all.length;i++){
                    var t=norm(all[i].textContent);
                    var h=all[i].getAttribute('href')||'';
                    if(!h || /^javascript:/i.test(h) || h.charAt(0)==='#') continue;
                    if(/^(目录|全文目录|全部目录|章节目录|小说目录|查看目录|完整目录|返回目录|返回书页|全部章节|查看全部章节|查看全部|展开全部|全部更新|正文章节|开始阅读|立即阅读|点击阅读|继续阅读|最新章节|章节列表|倒序|正序|默认排序|卷[一二三四五六七八九十两0-9]+)$/.test(t)){
                        try{ return new URL(h, location.href).href; }catch(e){ return ''; }
                    }
                }
                return '';
            })()"""

            val rawTocLink = suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(tocLinkJs) { r -> if (cont.isActive) cont.resume(r) }
            }
            val tocLink = decodeJsonString(rawTocLink ?: "").trim()
            if (tocLink.isNotBlank() && tocLink.startsWith("http")) {
                listBaseUrl = tocLink
                Log.d("UrlLoader", "chapterList: found 目录 link on chapter page: $listBaseUrl")
            } else {
                // 策略2：从URL模式推断目录页
                Log.d("UrlLoader", "chapterList: no 目录 link found, inferring from URL pattern")
                // 若当前 url 本身已像是目录/书籍主页（以 / 结尾，或没有章节文件扩展名），
                // 直接作为目录页处理，避免把 /book/123/ 之类错改成 /123/ 导致打不开
                val looksLikeChapterFile = Regex(
                    """\.(html?|php|asp|aspx|jsp|shtml|do|action)$""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(path) && !path.endsWith('/')
                if (!looksLikeChapterFile) {
                    listBaseUrl = url
                    Log.d("UrlLoader", "chapterList: url already looks like a catalog, keep as-is: $listBaseUrl")
                } else {
                    val chapterExtMatch =
                        Regex("""^/(\d+)/(\d+)(?:-\d+)?\.html?$""", RegexOption.IGNORE_CASE).find(path)
                    if (chapterExtMatch != null) {
                        val bookId = chapterExtMatch.groupValues[1]
                        listBaseUrl = "$baseUrl/$bookId/"
                    } else {
                        val bookDirMatch = Regex("""^/(\d+)/(\d+)/?$""").find(path)
                        if (bookDirMatch != null) {
                            val bookId = bookDirMatch.groupValues[1]
                            listBaseUrl = "$baseUrl/$bookId/"
                        } else {
                            // 尝试从章节URL推断（如 /book/123/chapter/456 -> /book/123/）
                            val segs = path.split('/').filter { it.isNotEmpty() }
                            if (segs.size >= 2) {
                                val bookSeg = segs.firstOrNull { it.all { c -> c.isDigit() } }
                                if (bookSeg != null) {
                                    listBaseUrl = "$baseUrl/$bookSeg/"
                                } else {
                                    listBaseUrl = "$baseUrl/${segs.dropLast(1).joinToString("/")}/"
                                }
                            }
                        }
                    }
                }
            }

            // 兜底：listBaseUrl 落到站点首页 / 通用入口时，强制在 listBaseUrl 上再次找
            // 「章节目录/全部章节/开始阅读」等同义入口，找不到就放弃，避免把全站 nav 当成章节。
            // 策略 1 在原 chapterUrl 上已经找过，但当 listBaseUrl ≠ url 时仍需复检；
            // 另外 bookUrl 本身是首页、策略 1 找不到的情况下，也需要在这里再找一次。
            val isHomePage = run {
                val p = Uri.parse(listBaseUrl).path ?: ""
                p.isEmpty() || p == "/" || p == "/index.html" || p == "/index.htm" || p == "/index.php" || p == "/default.html" || p == "/default.htm" || p == "/default.php"
            }
            if (isHomePage) {
                Log.d("UrlLoader", "chapterList: listBaseUrl is home page ($listBaseUrl), re-searching for toc link")
                navigateAndWait(listBaseUrl)
                val homeTocRaw = suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript(tocLinkJs) { r -> if (cont.isActive) cont.resume(r) }
                }
                val homeToc = decodeJsonString(homeTocRaw ?: "").trim()
                if (homeToc.isNotBlank() && homeToc.startsWith("http")) {
                    listBaseUrl = homeToc
                    Log.d("UrlLoader", "chapterList: home page → found toc link: $listBaseUrl")
                } else {
                    Log.d("UrlLoader", "chapterList: listBaseUrl is home and no toc link found, abort")
                    return@withContext ChapterListResult.Error(
                        "传入的链接是站点首页（$listBaseUrl），未找到「章节目录/全部章节/开始阅读」入口。请换源，或在换源面板添加自定义书源（粘贴具体的书目录页 URL）。"
                    )
                }
            }

            val diagJs = """(function(){
                try{
                    var res=[];
                    var all=document.getElementsByTagName('a');
                    for(var i=0;i<all.length && res.length<40;i++){
                        var t=(all[i].textContent||'').trim();
                        var h=all[i].getAttribute('href')||'';
                        if(/目录|全书|完整目录|章节列表|全部章节|小说目录/.test(t)) res.push(t+' => '+h);
                    }
                    var html=(document.documentElement&&document.documentElement.outerHTML)||'';
                    return JSON.stringify({title:document.title, a:all.length, htmlLen:html.length, diag:res, bodyLen:(document.body?document.body.innerText.length:0)});
                }catch(e){ return 'ERR:'+e.message; }
            })()"""

            suspend fun diag(tag: String) {
                val raw = suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript(diagJs) { r -> if (cont.isActive) cont.resume(r) }
                }
                Log.d("UrlLoader", "diag[$tag] ${raw ?: "null"}")
            }

            // 已在目录页上（策略1导航的就是它）时不再重复加载同一页，省一次整页加载
            val currentWvUrl = webView.url?.substringBefore('#') ?: ""
            if (listBaseUrl.substringBefore('#') != currentWvUrl) {
                Log.d("UrlLoader", "chapterList: navigating to catalog: $listBaseUrl")
                navigateAndWait(listBaseUrl)
            } else {
                Log.d("UrlLoader", "chapterList: already on catalog page, skip re-navigation")
            }
            diag("catalog")

            // 多层兜底提取脚本：
            //   章节链接 L1 已知容器 → L2 链接密度聚类 → L3 章节样式文本 → L4 宽松同域扫描
            //   分页地址 P1 select 下拉 → P2 分页容器 → P3 下一页/尾页/页码链接 → P4 模板展开
            val extractJs = """(function(){
                function resolve(u){ try{ return new URL(u, location.href).href; }catch(e){ return ''; } }
                // 反爬混淆归一化（仅用于导航/分页匹配，不改变展示标题）：
                // 「上—章」(U+2014 冒充一)、「返&nbsp回&nbsp目&nbsp录」(&nbsp 实体混入字缝) 等
                // 会令黑名单与翻页识别失效，导致目录只剩上一页/下一页式假条目。
                function normTxt(s){
                    return (s||'').replace(/&(#160|x0*a0|nbsp);?/gi,'')
                        .replace(/[\s\u00A0\u2007\u202F\u200B\uFEFF]+/g,'')
                        .replace(/[\u00AD\u2010\u2011\u2013\u2014\u2015\u2043\u2212\uFF0D\uFE63-]/g,'一');
                }
                var origin = location.origin;
                var curNoHash = location.href.split('#')[0];
                var seg = (location.pathname||'').split('/').filter(function(x){return x.length>0;});
                var bookPrefix = '/' + (seg[0]||'') + '/';
                var dirPath = location.pathname;
                var cut = dirPath.lastIndexOf('/');
                if(cut>=0) dirPath = dirPath.substring(0,cut+1);
                function sameOrigin(u){ return !!u && u.indexOf(origin)===0; }
                // 整词 nav 黑名单：覆盖全站顶部/侧边导航词、分类词、行动词等。整词匹配（前后必须是
                // 非汉字字符或字符串边界），避免把"第一章"等真章节名误伤。
                var navWords = ['首页','主页','书架','书城','书库','排行','排行总','排行榜','排行榜','分类','完本','全本','新书','免费','最新','热搜','搜索','更多','全部','展开','收起','详情','简介','下载','txt','TXT','加入','收藏','投票','月票','打赏','评论','返回','回到顶部','手机阅读','阅读器','换源','上一','下一','上一页','下一页','上一頁','下一頁','上章','下章','上页','下页','上篇','下篇','尾页','末页','首1页','首页','封面','原版','正版','书包网','搜书','搜','榜','单','玄幻','奇幻','武侠','仙侠','修真','都市','言情','历史','军事','网游','竞技','游戏','体育','科幻','灵异','恐怖','女生','男生','同人','穿越','重生','校园','职场','官场','商场','社会','情感','明星','动漫','二次元','轻小说','悬疑','推理','冒险','魔幻','玄幻奇幻','修真仙侠','武侠仙侠','都市言情','历史军事','网游竞技','科幻灵异','女生频道','男生频道','频道','完本榜','新书榜','收藏榜','排行榜','点我下载','下载APP','客户端','APP','广告','友链','友情链接','站点地图','网站地图','关于我们','联系我们','帮助','反馈','建议','公告','新闻','VIP','登录','注册','注销','退出','历史记录','我的书架','永久书架','我的收藏','我的阅读','换源阅读','友情链接','合作','作者','标签','专题','书评','书友','读者','其他栏目','栏目','栏目导航','展开全部','收起全部','查看更多','更多频道','更多分类','所有分类','全部作品','全部书籍','全部小说','小说榜','新书榜','畅销榜','热门','最热','推荐','强烈推荐','本周强推','本周推荐','编辑推荐','主编推荐','小编推荐','官方推荐','签约','VIP会员','会员','充值','充值中心','商城','购物车','我的订单','通知','系统通知','我的消息','私信','消息','设置','账号','个人中心','我的账户','账户','兑换','兑换码','礼品码','登录注册','免费阅读','正版阅读','最新章节','最新更新','最新章节列表','最新章节抢先看','抢先看','本章已更新','本书已更新','上一章目录','下一章目录','上一章按钮','下一章按钮','关闭','扫码','扫一扫','二维码','点击下载','立即下载','马上下载','立即打开','打开APP','在APP内打开','在浏览器打开','在浏览器中打开','继续阅读本文','继续阅读','继续浏览','继续访问','继续','从头阅读','开始阅读','立即阅读','点击阅读','阅读记录','阅读历史','浏览记录','浏览历史','返回书页','返回目录','加入书签'];
                function isCnCharCode(c){ return c>=0x4e00 && c<=0x9fff; }
                function isLikelyNavText(t){
                    if(!t) return false;
                    // 归一化反爬混淆后再做整词黑名单匹配（上—章 / 返 回 目 录 等）
                    t=normTxt(t);
                    if(!t) return false;
                    for(var i=0;i<navWords.length;i++){
                        var w=navWords[i];
                        var idx=t.indexOf(w);
                        if(idx<0) continue;
                        var leftOK = idx===0 || !isCnCharCode(t.charCodeAt(idx-1));
                        var rightEnd = idx+w.length;
                        var rightOK = rightEnd>=t.length || !isCnCharCode(t.charCodeAt(rightEnd));
                        if(leftOK && rightOK) return true;
                    }
                    return false;
                }
                // 分类聚合页前缀：直接拒绝（不属于某本书的章节）
                var catPrefixRe = /[\/\?](category|sort|class|classify|cate|fenlei|tag|topic|genre|type|list_[a-z]+|sort_[a-z]+|class_[a-z]+|cat_|tag_|type_|rank|ranking|top|hot|new|free|complete|over|finished)(\b|\/|\.|\?|&)/i;
                // 站首页 / 通用入口判定
                var homeRe = /^https?:\/\/[^\/]+\/?(\?.*)?(\#.*)?$/i;
                function isHomeUrl(u){
                    if(!u) return false;
                    var noHash=u.split('#')[0];
                    return /^https?:\/\/[^\/]+\/?(\?[^#]*)?$/i.test(noHash);
                }
                function sameBook(u){
                    if(!u) return false;
                    if(catPrefixRe.test(u)) return false;
                    if(dirPath==='/' || bookPrefix==='/') return false; // 站首页没有"同书"概念
                    if((u.indexOf(origin+dirPath)===0) || (u.indexOf(origin+bookPrefix)===0)) return true;
                    return false;
                }
                function pageLike(u){
                    var full=(u||'').split('#')[0];
                    var p=full.split('?')[0].toLowerCase();
                    if(p.indexOf(origin.toLowerCase())===0) p=p.substring(origin.length);
                    if(p==='/' || p==='' || p==='/index.html' || p==='/index.htm' || p==='/index.php' || p==='/default.html') return false; // 站首页不算 pageLike
                    var exts=['.html','.htm','.shtml','.php','.asp','.aspx','.jsp','.action','.do','.json','.xml','.txt'];
                    for(var i=0;i<exts.length;i++){ var e=exts[i]; if(p.length>e.length && p.lastIndexOf(e)===p.length-e.length) return true; }
                    var ss=p.split('/').filter(function(x){return x.length>0;});
                    var last=ss.length?ss[ss.length-1]:'';
                    if(last && last.indexOf('.')<0 && /[0-9]/.test(last)) return true;
                    if(full.indexOf('?')>=0 && /[0-9]/.test(full.split('?')[1]||'')) return true;
                    if(ss.length>=2 && /[0-9]/.test(last)) return true;
                    if(last && /^\d+[-_]\d+/.test(last)) return true;
                    if(last && /^chapter[-_]?\d+/i.test(last)) return true;
                    if(last && /^ch[-_]?\d+/i.test(last)) return true;
                    return false;
                }
                var chapterTitleRe=/第\s*[0-9〇零一二三四五六七八九十百千万两]+\s*[章回卷节篇集部话]|^\s*(chapter|ch|卷|卷[一二三四五六七八九十]+|part|episode|ep|vol)[\s.\-]*[0-9〇零一二三四五六七八九十百千万两]+|^\s*[0-9]{1,5}\s*[、.．:：,，\-\s]|^[0-9〇零一二三四五六七八九十百千万两]+\s*[、.．:：,，\-]|^Vol[\s.]*\d+/i;
                function mkLink(a){
                    var h=a.getAttribute('href')||'';
                    if(!h || /^javascript:/i.test(h) || h.charAt(0)==='#') return null;
                    var u=resolve(h); if(!u) return null;
                    var t=(a.textContent||'').replace(/\s+/g,' ').trim();
                    if(!t || t.length>80 || t.length<2) return null;
                    if(isLikelyNavText(t)) return null;            // 整词 nav 黑名单（首页导航/分类/行动词）
                    if(catPrefixRe.test(u)) return null;            // 分类聚合页（/category/1.html 等）
                    if(isHomeUrl(u)) return null;                   // 站首页 / 通用入口
                    if(u.split('#')[0]===curNoHash) return null;
                    return {t:t,u:u};
                }
                function isChapterUrl(u){ return sameOrigin(u) && pageLike(u); }

                // ---- 章节链接：五层兜底 ----
                var layers=[];
                // L0 JS 渲染目录：兼容通过 onclick/data-url/data-chapter 等属性组织的动态目录
                (function(){
                    var out=[]; var seen={};
                    var els=document.querySelectorAll('[data-url],[data-chapter],[data-href],[data-link],[data-id],[data-nid]');
                    for(var i=0;i<els.length;i++){
                        var el=els[i];
                        var u=el.getAttribute('data-url')||el.getAttribute('data-chapter')||el.getAttribute('data-href')||el.getAttribute('data-link')||'';
                        var nid=el.getAttribute('data-id')||el.getAttribute('data-nid')||'';
                        if(!u && nid){
                            var base=location.href.replace(/\/[^\/]*$/,'/');
                            u=base+nid+(nid.indexOf('.')<0?'.html':'');
                        }
                        if(!u) continue;
                        var fu=resolve(u); if(!fu) continue;
                        var t=(el.textContent||'').replace(/\s+/g,' ').trim();
                        if(!t || t.length>80) continue;
                        if(!sameOrigin(fu)) continue;
                        if(!sameBook(fu) && !chapterTitleRe.test(t)) continue;
                        if(seen[fu]) continue; seen[fu]=1;
                        out.push({t:t,u:fu});
                    }
                    layers.push(out);
                })();
                // L1 已知容器（覆盖常见小说站的目录容器写法）
                (function(){
                    var out=[]; var seen={};
                    var sel='#list,#list1,.listmain,#chapterlist,.chapterlist,#chapterList,.chapter-list,.chapter_list,#catalog,.catalog,#chapter_list,.dir,.ml_list,.mulu,#mulu,.mulu_list,#box,.book_list,#playlist,#allchapter,#chapters,#chapterbox,.novel-list,.list-chapter,.section_box,.section-box,.volume-wrap,.volume,#readerlist,.zjlist,#zjlist,.zjbox,#chapter,.directoryArea,.uclist,.pc_list,.book_con_list,.info_menu,#chapters-list,.catalog-list,#TextContent,.text-content,#chapter-container,.chapter-container,.book-chapter-list,#book-chapter-list,.list_content,.list-content,#list_content,.sort-list,.sort_list,.booklist,.book-list,#booklist,#BookList,.booklistcon,.mulu_main,#mulu_main,.catalog_main,.volume_list,.volumeList,.chapter_section,#chapterSection,.chapter-group,.chapterGroup,#chapterGroup,#directory,.directory,.dirlist,.dir-list,.chapterlistbox,#chapterlistbox,.bookchapterlist,#bookchapterlist,.text_list,.textlist,#catalogue,#catalogList,.catalogue-list,#bookText,.book-text,#bookContent,.book-content,.content_box,.content-box,#content_box,.readlist,.read-list,.book_catalog,#bookCatalog,.main_cont,.main-cont,#main_cont,.con_list,.con-list,.listpage,.book_directory,.book-directory';
                    var conts=document.querySelectorAll(sel);
                    for(var c=0;c<conts.length;c++){
                        var as=conts[c].querySelectorAll('a');
                        for(var k=0;k<as.length;k++){
                            var L=mkLink(as[k]); if(!L) continue;
                            if(!(sameBook(L.u)&&pageLike(L.u)) && !(isChapterUrl(L.u)&&chapterTitleRe.test(L.t))) continue;
                            if(seen[L.u]) continue; seen[L.u]=1; out.push(L);
                        }
                    }
                    layers.push(out);
                })();
                // L2 链接密度聚类：不依赖 class/id，自动找章节链接最密集的区块（兼容 table/dl/自定义容器）
                (function(){
                    var all=document.getElementsByTagName('a');
                    var cand=[];
                    for(var i=0;i<all.length;i++){
                        var L=mkLink(all[i]); if(!L) continue;
                        if(!sameOrigin(L.u)) continue;
                        if(!pageLike(L.u) && !chapterTitleRe.test(L.t)) continue;
                        if(!sameBook(L.u) && !chapterTitleRe.test(L.t)) continue;
                        cand.push({a:all[i],L:L});
                    }
                    var holders=[];
                    for(var j=0;j<cand.length;j++){
                        var p=cand[j].a.parentElement, d=0;
                        while(p && d<6){
                            if(p.__cc===undefined){ p.__cc=0; holders.push(p); }
                            p.__cc++;
                            p=p.parentElement; d++;
                        }
                    }
                    var best=null;
                    for(var h2=0;h2<holders.length;h2++){
                        var el=holders[h2];
                        if(el.__cc<5) continue;
                        if(!best || el.__cc>best.__cc || (el.__cc===best.__cc && best.contains(el))) best=el;
                    }
                    var out=[]; var seen={};
                    if(best){
                        for(var k2=0;k2<cand.length;k2++){
                            if(best.contains(cand[k2].a) && !seen[cand[k2].L.u]){ seen[cand[k2].L.u]=1; out.push(cand[k2].L); }
                        }
                    }
                    for(var hh=0;hh<holders.length;hh++){ try{ delete holders[hh].__cc; }catch(e){} }
                    layers.push(out);
                })();
                // L2.5 列表元素扫描：兼容用 <ol>/<ul>/<li> 组织章节列表的站点
                (function(){
                    var out=[]; var seen={};
                    var lists=document.querySelectorAll('ol,ul');
                    for(var li=0;li<lists.length;li++){
                    var items=lists[li].querySelectorAll('li');
                    if(items.length<3) continue;
                    var ownItems=[];
                    for(var ci=0;ci<items.length;ci++){ if(items[ci].parentNode===lists[li]) ownItems.push(items[ci]); }
                    items=ownItems;
                    if(items.length<3) continue;
                        var hits=0;
                        var lis=[];
                        for(var ii=0;ii<items.length;ii++){
                            var a=items[ii].querySelector('a');
                            if(!a) continue;
                            var L=mkLink(a); if(!L) continue;
                            if(!sameOrigin(L.u)) continue;
                            // 关键：必须 sameBook，或标题强匹配章节格式。否则首页/分类页的 <ul><li> 同类推荐会全收。
                            if(!sameBook(L.u) && !chapterTitleRe.test(L.t)) continue;
                            lis.push({a:a,L:L});
                            if(pageLike(L.u) || chapterTitleRe.test(L.t)) hits++;
                        }
                        if(hits>=3 && hits>=lis.length*0.4){
                            for(var jj=0;jj<lis.length;jj++){
                                if(!seen[lis[jj].L.u]){ seen[lis[jj].L.u]=1; out.push(lis[jj].L); }
                            }
                        }
                    }
                    layers.push(out);
                })();
                // L3 章节样式文本：全文匹配“第N章/Chapter N/数字、”样式的链接（URL 规则完全未知时兜底）
                (function(){
                    var out=[]; var seen={};
                    var all=document.getElementsByTagName('a');
                    for(var i=0;i<all.length;i++){
                        var L=mkLink(all[i]); if(!L) continue;
                        if(!sameOrigin(L.u)) continue;
                        if(!chapterTitleRe.test(L.t)) continue;
                        if(seen[L.u]) continue; seen[L.u]=1; out.push(L);
                    }
                    layers.push(out);
                })();
                // L4 宽松同域扫描：最后兜底（允许任意同源链接，只要URL路径有数字或章节特征）
                (function(){
                    var out=[]; var seen={};
                    var all=document.getElementsByTagName('a');
                    for(var i=0;i<all.length;i++){
                        var L=mkLink(all[i]); if(!L) continue;
                        if(!sameOrigin(L.u)) continue;
                        // 关键：必须 sameBook，或标题强匹配章节格式。否则首页/分类页 nav 全部入坑。
                        if(!sameBook(L.u) && !chapterTitleRe.test(L.t)) continue;
                        var p=(L.u.split('#')[0].split('?')[0]||'').toLowerCase();
                        var hasNum=/[0-9]/.test(p);
                        var hasChapter=/chapter|ch|vol|part|ep|section/i.test(p);
                        var hasText=L.t.length>=2 && L.t.length<=40;
                        if((hasNum || hasChapter) && hasText){
                            if(seen[L.u]) continue; seen[L.u]=1; out.push(L);
                        }
                    }
                    layers.push(out);
                })();
                var links=[], layer=0;
                for(var li=0;li<layers.length;li++){ if(layers[li].length>=3){ links=layers[li]; layer=li+1; break; } }
                if(links.length===0){ for(var lj=0;lj<layers.length;lj++){ if(layers[lj].length>links.length){ links=layers[lj]; layer=lj+1; } } }

                // ---- 分页地址：四层兜底 ----
                var pages=[]; var seenP={};
                function addPage(u){ if(u && sameOrigin(u) && !seenP[u.split('#')[0]] && u.split('#')[0]!==curNoHash){ seenP[u.split('#')[0]]=1; pages.push(u); } }
                // 从页面已有链接推断分页 URL 模板（如 p-2.html / index_2.html / list_2/ / ?page=2）
                var pageTemplate='';
                (function(){
                    var all=document.getElementsByTagName('a');
                    for(var i=0;i<all.length;i++){
                        var h=all[i].getAttribute('href')||'';
                        if(!h || /^javascript:/i.test(h)) continue;
                        var m=h.match(/(p-|page[_=-]?|index[_-]|list[_-])([0-9]+)/i);
                        if(m){ var ru=resolve(h); if(sameBook(ru)){ pageTemplate=ru.replace(m[1]+m[2], m[1]+'@N@'); break; } }
                    }
                })();
                // P1 select 下拉（value 为 URL / 相对路径 / 纯数字均兼容）
                var selects=document.querySelectorAll('select');
                for(var s=0;s<selects.length;s++){
                    var opts=selects[s].querySelectorAll('option');
                    for(var o=0;o<opts.length;o++){
                        var ov=(opts[o].getAttribute('value')||'').trim();
                        var ot=(opts[o].textContent||'').trim();
                        if(!ov && ot.indexOf('页')<0) continue;
                        var pu='';
                        if(ov.indexOf('://')>=0 || ov.charAt(0)==='/' || ov.charAt(0)==='.'){ pu=resolve(ov); }
                        else if(/\.x?html?/i.test(ov) || ov.indexOf('p-')===0 || ov.indexOf('index_')===0 || ov.indexOf('list_')===0){ pu=resolve(ov); }
                        else if(/^[0-9]+/.test(ov) && !/[^0-9]/.test(ov)){
                            var n=parseInt(ov,10);
                            if(!isNaN(n)&&n>1){
                                if(pageTemplate){ pu=pageTemplate.replace('@N@',''+n); }
                                else { pu=resolve(bookPrefix.substring(0,bookPrefix.length-1)+'/p-'+n+'.html'); }
                            }
                        }
                        if(pu && (sameBook(pu)||pageLike(pu))) addPage(pu);
                    }
                }
                // P2 分页容器内的链接
                var pgConts=document.querySelectorAll('.pagination,.pages,.pagelist,#pagelink,.pg,.paging,.pagelink,.page-link,#pages,.index_block,.listpage,.book_more,.page,.page_nav,.pageNav,#page_nav,#pageNav,.pager,.pagebar,.page-bar,#pagebar,.pagesplit,.page-split,.nextprev,.next-prev');
                for(var pc=0;pc<pgConts.length;pc++){
                    var pas=pgConts[pc].querySelectorAll('a');
                    for(var pa=0;pa<pas.length;pa++){
                        var ph=pas[pa].getAttribute('href')||'';
                        if(!ph || /^javascript:/i.test(ph) || ph.charAt(0)==='#') continue;
                        var ppu=resolve(ph);
                        if(sameOrigin(ppu)&&pageLike(ppu)) addPage(ppu);
                    }
                }
                // P3 下一页/尾页/页码 文本链接（normTxt 兼容「下—页」等一字线反爬变体）
                var allA=document.getElementsByTagName('a');
                for(var na=0;na<allA.length;na++){
                    var ntRaw=((allA[na].textContent||'')+' '+(allA[na].getAttribute('title')||'')).trim();
                    var nt=normTxt(ntRaw);
                    var nh=allA[na].getAttribute('href')||'';
                    if(!nh || /^javascript:/i.test(nh) || nh.charAt(0)==='#') continue;
                    var isNav=/下一页|下页|后一页|尾页|末页|最后一页|>>|»/.test(nt);
                    var core=nt.replace(/[第页\s]/g,'');
                    var isNum=core.length>0 && core.length<=4 && !/[^0-9]/.test(core);
                    if(isNav || isNum){
                        var nu=resolve(nh);
                        // 放宽 sameBook：部分站点目录在 /book/{id}/ 而分页在 /html/{id}/asc-N/，
                        // 前缀不同但同属本书；addPage 已确保同源，pageLike 确保是分页样式的地址。
                        if(pageLike(nu)) addPage(nu);
                    }
                }
                // P4 模板展开：由已发现分页的最大页码补全整段页码（如仅有“尾页”时）
                (function(){
                    var tmpl='', maxN=0;
                    for(var i=0;i<pages.length;i++){
                        var m=pages[i].match(/(p-|page[_=-]?|index[_-]|list[_-]|-)([0-9]+)(\.x?html?|\/)/i);
                        if(m){ var n=parseInt(m[2],10); if(n>maxN){ maxN=n; tmpl=pages[i].replace(m[1]+m[2]+m[3], m[1]+'@N@'+m[3]); } }
                    }
                    if(tmpl && maxN>=2 && maxN<=200){
                        for(var n2=2;n2<=maxN;n2++) addPage(tmpl.replace('@N@',''+n2));
                    }
                })();
                if(pages.length>60) pages=pages.slice(0,60);
                return JSON.stringify({links:links,pages:pages,layer:layer});
            })()"""

            suspend fun runExtract(): Triple<List<ChapterItem>, List<String>, Int> {
                val raw = suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript(extractJs) { r -> if (cont.isActive) cont.resume(r) }
                }
                return try {
                    val inner = decodeJsonString(raw ?: "")
                    val obj = org.json.JSONObject(inner)
                    val links = mutableListOf<ChapterItem>()
                    val arr = obj.optJSONArray("links")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val t = o.optString("t", "").trim()
                            val u = o.optString("u", "").trim()
                            if (t.isNotBlank() && u.isNotBlank()) links.add(ChapterItem(t, u))
                        }
                    }
                    val pages = mutableListOf<String>()
                    val pagesArr = obj.optJSONArray("pages")
                    if (pagesArr != null) {
                        for (i in 0 until pagesArr.length()) {
                            val pu = pagesArr.optString(i, "").trim()
                            if (pu.isNotBlank()) pages.add(pu)
                        }
                    }
                    Triple(links, pages, obj.optInt("layer", 0))
                } catch (e: Exception) {
                    Log.d("UrlLoader", "chapterList parse error: ${e.message}")
                    Triple(emptyList(), emptyList(), 0)
                }
            }

            // 动态渲染兜底：JS 生成目录的站点首屏可能为空，轮询重试直到出现章节。
            // 间隔同样只在章节未出现时消耗，出现即停。
            suspend fun extractWithRetry(): Triple<List<ChapterItem>, List<String>, Int> {
                var result = runExtract()
                var attempt = 0
                while (result.first.size < 3 && attempt < 10) {
                    delay(300)
                    val again = runExtract()
                    if (again.first.size > result.first.size) result = again
                    if (result.first.size >= 3) break
                    attempt++
                }
                return result
            }

            val first = extractWithRetry()
            val allLinks = first.first.toMutableList()
            Log.d("UrlLoader", "chapterList: page1 layer=${first.third} links=${allLinks.size} pages=${first.second.size}")

            val initialPages = first.second.map { it.substringBefore('#') }.distinct()
            val concurrentLinks = if (initialPages.isNotEmpty()) crawlTocPages(initialPages) else emptyList()
            if (concurrentLinks.isEmpty() && initialPages.isNotEmpty()) {
                // 轻量通道拿不到分页内容（常见于纯 JS 渲染站点），回退到 WebView 逐页串行抓取
                Log.d("UrlLoader", "chapterList: light pagination empty, fallback to WebView sequential crawl")
                val wvVisited = mutableSetOf(listBaseUrl.substringBefore('#'))
                val wvQueue = ArrayDeque(initialPages.filter { it !in wvVisited })
                var pageGuard = 0
                while (wvQueue.isNotEmpty() && pageGuard < 60) {
                    val pageUrl = wvQueue.removeFirst()
                    if (!wvVisited.add(pageUrl)) continue
                    pageGuard++
                    try {
                        navigateAndWait(pageUrl)
                        val p = extractWithRetry()
                        allLinks.addAll(p.first)
                        for (np in p.second) {
                            val n = np.substringBefore('#')
                            if (n !in wvVisited && wvQueue.none { it == n }) wvQueue.addLast(n)
                        }
                    } catch (e: Exception) {
                        Log.d("UrlLoader", "chapterList page fail $pageUrl: ${e.message}")
                    }
                }
            } else {
                allLinks.addAll(concurrentLinks)
            }

            // 过滤纯导航/分页链接（如「上一页/下一页」「上—章」「返 回 目 录」等反爬变体），
            // 避免它们被当成章节混进目录；原始标题与归一化标题任一命中即过滤。
            val usableLinks = allLinks.filter {
                val raw = it.title.trim()
                !tocNavTitleRe.containsMatchIn(raw) &&
                    !tocNavTitleRe.containsMatchIn(stripInvisibleWs(raw)) &&
                    !tocNavTitleRe.containsMatchIn(navNorm(raw))
            }
            if (usableLinks.size < allLinks.size) {
                Log.d("UrlLoader", "chapterList: filtered ${allLinks.size - usableLinks.size} nav/pagination links")
            }

            val seen = mutableSetOf<String>()
            val deduped = mutableListOf<ChapterItem>()
            for (item in usableLinks) {
                if (seen.add(item.url)) deduped.add(item)
            }

            // 排序：优先按章节号（兼容中文数字），无章节号的按 URL 数字，再兜底保持文档顺序。
            // 顶部「最新章节预览」块（倒序）也会被归位到正确位置。
            val sorted = sortChapterItems(deduped)

            Log.d("UrlLoader", "chapterList total=${sorted.size} (deduped from ${allLinks.size}, layer=${first.third})")
            if (sorted.isEmpty()) {
                ChapterListResult.Error("未能在目录页找到章节链接（已尝试多种格式解析），该站可能需要登录或启用了反爬")
            } else {
                // 诚实上报"是否抓全"：这一通道的分页上限是 60 页（JS 侧也截到 60），
                // 只要目录本身有分页，就绝不能声称 complete —— 否则一次只抓到前 60 页的
                // 目录会被当成最终结果缓存下来，用户看到的目录就会中间缺好几段。
                val webViewComplete = initialPages.isEmpty() && !tocHasBigGaps(sorted)
                ChapterListResult.Success(sorted, listBaseUrl, webViewComplete, null)
            }
        } catch (e: TimeoutCancellationException) {
            ChapterListResult.Error("目录加载超时")
        } catch (e: Exception) {
            ChapterListResult.Error("目录加载失败: ${e.message}")
        } finally {
            try { root?.removeView(webView) } catch (_: Exception) { }
            webView.destroy()
        }
    }

    // ===== 书源搜索（换源用） =====
    data class SearchItem(val title: String, val url: String)

    sealed class SearchResult {
        data class Success(val items: List<SearchItem>) : SearchResult()
        data class Error(val message: String) : SearchResult()
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun searchBooks(source: BookSource, keyword: String): SearchResult =
        withContext(Dispatchers.Main) {
            val ua = UA
            // webView 创建与销毁兜底由下方 return@withContext try/catch/finally 统一处理
            var webViewRef: WebView? = null
            var rootRef: ViewGroup? = null
            val webView = WebView(context.applicationContext)
            webViewRef = webView
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = ua

            var currentHost = ""
            var currentSuffix = ""
            var pageFinishedCallback: (() -> Unit)? = null
            // 搜索模式下不拦截任何子资源请求，确保 JS 动态渲染的搜索结果能正常加载（阅读模式才启用拦截）
            var searchMode = true
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (searchMode) return null
                    val reqHost = request?.url?.host ?: return null
                    if (!isAllowedRequest(reqHost, currentHost, currentSuffix)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null || url == "about:blank") return
                    pageFinishedCallback?.invoke()
                }
            }

            // 轻量通道优先：用 OkHttp+Jsoup 直接拉搜索结果页并解析（大部分书源为服务端渲染）。
            // 命中关键词即返回，避免 WebView 轮询 12s 的慢与"一直转圈"。WebView 仅作兜底。
            try {
                for (ci in 0 until source.candidateCount()) {
                    val su = source.buildSearchUrl(keyword, ci)
                    val light = fetchSearchLight(su, keyword)
                    if (light != null && light.isNotEmpty()) {
                        Log.d("UrlLoader", "searchBooks: light channel hit for ${'$'}su, ${'$'}{light.size} items")
                        return@withContext SearchResult.Success(light)
                    }
                }
            } catch (e: Exception) {
                Log.d("UrlLoader", "searchBooks: light channel failed, fallback to WebView: $e")
            }

            val root = (context as? Activity)?.findViewById<ViewGroup>(android.R.id.content)
            rootRef = root
            attachWebView(webView, root)

            suspend fun navigate(target: String) {
                withTimeout(20000) {
                    suspendCancellableCoroutine { cont ->
                        var done = false
                        pageFinishedCallback = {
                            if (!done && cont.isActive) {
                                done = true
                                pageFinishedCallback = null
                                cont.resume(Unit)
                            }
                        }
                        cont.invokeOnCancellation {
                            pageFinishedCallback = null
                            webView.stopLoading()
                        }
                        webView.loadUrl(target)
                    }
                }
            }

            val kwJson = org.json.JSONObject.quote(keyword)
            val extractJs = """(function(){
                var kw=$kwJson;
                function resolve(u){ try{ return new URL(u, location.href).href; }catch(e){ return ''; } }
                function isJunk(t){ return /(首页|书架|排行|分类|完本|书库|搜索|登录|注册|上一页|下一页|尾页|末页|加入书架|收藏|投票|推荐|月票|打赏|评论|最新章节|返回|下载|txt下载|阅读器|手机阅读|广告|客服|标签|作者|目录|更多|首页|热门|推荐)/i.test(t); }
                // 覆盖常见搜索结果容器：列表块、表格行(笔趣阁系 td.odd / tr a)、卡片等
                var sels='#site_search_list,.result-list,.book-list,.search-list,.search-result,.search-result-list,.novel-list,.bookname,.book-item,.result-item,.item,.list,#list,.searchbox .book,.search-results,.mod_book_list,.bookbox,.books,.book_list,.updateList,.listBox,.search-results-list,.result-box,td.odd,td.even,tr.odd a,tr.even a,table.grid a,.grid tr a';
                var cand=[]; var seen={};
                function consider(a){
                    var h=a.getAttribute('href')||'';
                    if(!h||h.indexOf('javascript:')===0||h.charAt(0)==='#') return;
                    var u=resolve(h); if(!u) return;
                    if(u.split('#')[0]===location.href.split('#')[0]) return;
                    var t=(a.textContent||'').replace(/\s+/g,' ').trim();
                    if(!t||t.length>60||t.length<2) return;
                    if(seen[u]) return;
                    var matched = (kw && t.toLowerCase().indexOf(kw.toLowerCase())>=0);
                    cand.push({t:t,u:u,m:matched});
                    seen[u]=1;
                }
                var conts=document.querySelectorAll(sels);
                for(var c=0;c<conts.length;c++){ var as=conts[c].querySelectorAll('a'); for(var k=0;k<as.length;k++) consider(as[k]); }
                var all=document.getElementsByTagName('a');
                for(var i=0;i<all.length;i++){ var t=(all[i].textContent||'').replace(/\s+/g,' ').trim(); if(t && t.toLowerCase().indexOf((kw||'').toLowerCase())>=0) consider(all[i]); }
                // 仅返回命中关键词的链接，避免把默认榜单/热门推荐当作搜索结果
                var matched=cand.filter(function(x){return x.m;});
                var out=matched.slice(0,30).map(function(x){return {t:x.t,u:x.u};});
                return JSON.stringify({items:out});
            })()"""

            return@withContext try {
                // 依次尝试每个候选搜索地址，返回第一个命中关键词的结果。
                // 对 JS 动态渲染的站点，轮询 DOM 最长 12s 直到出现结果链接（之前只等 1.5s 常抓空）。
                var items: List<SearchItem> = emptyList()
                for (ci in 0 until source.candidateCount()) {
                    val searchUrl = source.buildSearchUrl(keyword, ci)
                    currentHost = Uri.parse(searchUrl).host ?: continue
                    currentSuffix = if (currentHost.count { it == '.' } >= 2) currentHost.substringAfter('.') else currentHost

                    navigate(searchUrl)
                    var found: List<SearchItem> = emptyList()
                    val deadline = System.currentTimeMillis() + 12000
                    while (System.currentTimeMillis() < deadline) {
                        val raw = suspendCancellableCoroutine { cont ->
                            webView.evaluateJavascript(extractJs) { r -> if (cont.isActive) cont.resume(r) }
                        }
                        found = parseSearch(raw)
                        if (found.isNotEmpty()) break
                        delay(500) // 必须保留：否则未命中时该循环会空转烧 CPU 直到超时
                    }
                    if (found.isNotEmpty()) {
                        items = found
                        break
                    }
                }
                if (items.isEmpty()) {
                    SearchResult.Error("未找到「$keyword」相关书籍（内置书源可能已变更，建议在换源面板添加自定义书源）")
                } else {
                    SearchResult.Success(items)
                }
            } catch (e: TimeoutCancellationException) {
                SearchResult.Error("书源搜索超时")
            } catch (e: Exception) {
                SearchResult.Error("书源搜索失败: ${e.message}")
            } finally {
                try { root?.removeView(webView) } catch (_: Exception) { }
                webView.destroy()
            }
    }

    private fun parseSearch(raw: String?): List<SearchItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val inner = try {
            decodeJsonString(raw)
        } catch (_: Exception) {
            raw
        }
        return try {
            val obj = org.json.JSONObject(inner)
            val arr = obj.optJSONArray("items") ?: return emptyList()
            val list = mutableListOf<SearchItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "").trim()
                val u = o.optString("u", "").trim()
                if (t.isNotBlank() && u.isNotBlank()) list.add(SearchItem(t, u))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChapterList(raw: String?): List<ChapterItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val inner = try {
            decodeJsonString(raw)
        } catch (_: Exception) {
            raw
        }
        val result = mutableListOf<ChapterItem>()
        return try {
            val obj = org.json.JSONObject(inner)
            val debug = obj.optJSONObject("debug")
            if (debug != null) {
                Log.d("UrlLoader", "chapterList debug: box=${debug.optString("box")} bookId=${debug.optString("bookId")} total=${debug.optInt("total")}")
            }
            val arr = obj.optJSONArray("links") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "").trim()
                val u = o.optString("u", "").trim()
                if (t.isNotBlank() && u.isNotBlank()) result.add(ChapterItem(t, u))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 从标题解析章节号，兼容阿拉伯数字与中文数字（第1023章 / 第一千零二十三章 / Chapter 12 / 12、xxx） */
    private fun chapterNumber(title: String): Long? {
        Regex("""第\s*(\d+)\s*[章回卷篇节部集话]""").find(title)?.let {
            return it.groupValues[1].toLongOrNull()
        }
        Regex("""第\s*([〇零一二三四五六七八九十百千万两]+)\s*[章回卷篇节部集话]""").find(title)?.let {
            val v = cnNumToLong(it.groupValues[1])
            if (v != null) return v
        }
        Regex("""^(?:chapter|ch)[\s.]*([0-9]+)""", RegexOption.IGNORE_CASE).find(title.trim())?.let {
            return it.groupValues[1].toLongOrNull()
        }
        Regex("""^(\d{1,5})\s*[、.．:：,，]""").find(title.trim())?.let {
            return it.groupValues[1].toLongOrNull()
        }
        return null
    }

    /** 中文数字转 Long（支持 万/千/百/十/零，如"一千零二十三"→1023） */
    private fun cnNumToLong(s: String): Long? {
        val digits = mapOf('〇' to 0L, '零' to 0L, '一' to 1L, '两' to 2L, '二' to 2L, '三' to 3L,
            '四' to 4L, '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L)
        val units = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L)
        var total = 0L; var section = 0L; var digit = 0L; var any = false
        for (ch in s) {
            when {
                digits.containsKey(ch) -> { digit = digits[ch]!!; any = true }
                units.containsKey(ch) -> {
                    val u = units[ch]!!
                    any = true
                    if (u == 10000L) { total = (total + section + digit) * u; section = 0L }
                    else { section += (if (digit == 0L && ch == '十' && section == 0L && total == 0L) 1L else digit) * u }
                    digit = 0L
                }
                else -> return null
            }
        }
        if (!any) return null
        return total + section + digit
    }

    /** 从章节 URL 的最后一段提取数字，用作无章节号标题的排序键 */
    private fun urlNumber(url: String): Long? {
        val last = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        return Regex("""(\d+)""").findAll(last).lastOrNull()?.value?.toLongOrNull()
    }

    private fun chapterBase(url: String): String {
        // 兼容目录式章节地址（如 /html/{book}/{chapter}/ 以“/”结尾，lastPathSegment 为空）：
        // 先去掉结尾斜杠再取最后一段，保证「134660050_2/」与「134660050/」归一到同一章基名，
        // 否则章内分页会被误判为下一章，导致正文只抓到第 1 页。
        val path = Uri.parse(url).path ?: return url
        val name = path.trimEnd('/').substringAfterLast('/')
        var base = name.substringBefore('.')
        // 下划线翻页（如 134660050_2）只页码不同，视为同一章
        base = base.substringBefore('_')
        // 中划线翻页（如 2535079-2.html / 123-2.html）只页码不同，视为同一章
        base = base.replace(Regex("""-\d+$"""), "")
        return base
    }

    /** 同章分页链接（与当前章 URL 基名相同）：优先翻它，才能把整章抓全。 */
    private fun pickSameChapterPage(candidates: List<String>, base: String): String? =
        candidates.firstOrNull { it.isNotBlank() && chapterBase(it) == base }

    /** 跨章链接（基名不同）：作为「下一章」入口。 */
    private fun pickNextChapter(candidates: List<String>, base: String): String? =
        candidates.firstOrNull { it.isNotBlank() && chapterBase(it) != base }

    /** 「上一章」入口（基名必须不同，章内分页的「上一页」会被排除） */
    private fun pickPrevChapter(candidates: List<String>, base: String): String? =
        candidates.firstOrNull { it.isNotBlank() && chapterBase(it) != base }

    private fun normPageKey(u: String): String = u.substringBefore('#').trimEnd('/')

    /** http ↔ https 互换；用于主文档加载失败后的协议重试 */
    private fun swapScheme(url: String): String? = when {
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
        url.startsWith("https://", ignoreCase = true) -> "http://" + url.substring(8)
        else -> null
    }

    private fun isAllowedRequest(reqHost: String, host: String, allowedSuffix: String): Boolean {
        if (reqHost == host) return true
        if (reqHost.endsWith(".$allowedSuffix") || reqHost.endsWith(allowedSuffix)) return true
        if (reqHost == "challenges.cloudflare.com" || reqHost.endsWith(".cloudflare.com")) return true
        if (reqHost.contains("cloudflare") ||
            reqHost.contains("hcaptcha") ||
            reqHost.contains("recaptcha") ||
            reqHost.contains("turnstile") ||
            reqHost.contains("gstatic") ||
            reqHost.contains("cdnjs")
        ) return true
        return false
    }

    private fun attachWebView(webView: WebView, root: ViewGroup?) {
        if (root == null) return
        val dm = root.resources.displayMetrics
        webView.layoutParams = ViewGroup.LayoutParams(dm.widthPixels, dm.heightPixels)
        webView.visibility = View.INVISIBLE
        webView.isClickable = false
        webView.isFocusable = false
        root.addView(webView)
    }

    private fun parsePage(raw: String?): PageData {
        if (raw.isNullOrBlank()) return PageData("", "", "", null)
        val inner = try {
            decodeJsonString(raw)
        } catch (_: Exception) {
            raw
        }
        return try {
            val obj = org.json.JSONObject(inner)
            val nexts = mutableListOf<String>()
            obj.optJSONArray("nexts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { nexts.add(it) }
                }
            }
            val nextOne = obj.optString("next", "")
            if (nexts.isEmpty() && nextOne.isNotBlank()) nexts.add(nextOne)
            val prevs = mutableListOf<String>()
            obj.optJSONArray("prevs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { prevs.add(it) }
                }
            }
            PageData(
                title = obj.optString("t", ""),
                content = obj.optString("x", ""),
                nextPage = nextOne.ifBlank { nexts.firstOrNull() ?: "" },
                totalPages = if (obj.isNull("total")) null else obj.optInt("total", -1).let { if (it < 0) null else it },
                docTitle = obj.optString("dt", ""),
                bodyLen = obj.optInt("bl", 0),
                isChallenge = obj.optInt("cf", 0) == 1,
                nextCandidates = nexts,
                prevCandidates = prevs
            )
        } catch (_: Exception) {
            PageData("", inner, "", null)
        }
    }

    private fun decodeJsonString(s: String): String {
        if (s.length < 2 || !s.startsWith("\"") || !s.endsWith("\"")) return s
        val mid = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < mid.length) {
            val ch = mid[i]
            if (ch == '\\' && i + 1 < mid.length) {
                when (mid[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(mid[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
    // ===================== 轻量 HTTP 通道（OkHttp + Jsoup） =====================

    // 统一的轻量通道：用共享 OkHttp 拉取正文再用 Jsoup 解析，复用连接池与响应缓存。
    // [callTimeoutMs] 是单次请求的硬超时：OkHttp 的 connect/read 超时管不住「DNS 卡住 + 慢响应」
    // 叠加的情况，而 execute() 是阻塞调用、协程取消打不断它 —— 少了这道闸，翻章会干等十几秒。
    private suspend fun lightFetchDoc(url: String, callTimeoutMs: Long = 0L): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        // 反爬站点会拦截「看起来不像浏览器」的请求（缺 Accept / 无 Referer 等），
        // 这里把请求头补全成常规浏览器值，提升轻量通道命中率（失败会自动回退 WebView）。
        val origin = run {
            val u = android.net.Uri.parse(url)
            "${u.scheme ?: "https"}://${u.host ?: ""}/"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", origin)
            .header("Connection", "keep-alive")
            .apply {
                // 带上 WebView 已经拿到的 cookie —— 反爬站通常就差这一口
                cookiesFor(url)?.let { header("Cookie", it) }
            }
            .build()
        val call = client(context).newCall(request)
        if (callTimeoutMs > 0) call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        val response = call.execute()
        val bodyStr = response.body?.string()
        response.close()
        if (bodyStr.isNullOrEmpty()) throw IOException("empty body for $url")
        Jsoup.parse(bodyStr, url)
    }

    /**
     * 轻量通道抓取一章。
     *
     * [budgetMs] 为总预算（<=0 用默认 15s）。章内分页是串行抓的，没有预算上限时
     * 「3 页 × 单页 10s 超时」能把一次加载拖到半分钟，所以这里给每页单独的硬超时，
     * 预算耗尽就连后面的分页一起放弃 —— 正文首屏已经在手上，不该为它拖住整章。
     */
    internal suspend fun fetchChapterLight(url: String, budgetMs: Long = 0L): LoadResult = withContext(Dispatchers.IO) {
        val result = try {
            fetchChapterLightInner(url, budgetMs)
        } catch (e: TimeoutCancellationException) {
            LoadResult.Error("轻量抓取超时")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error("轻量抓取失败: ${e.message}")
        }
        // 统一记一笔：预取与前台翻章共用这份站点记忆
        noteLightResult(url, result)
        result
    }

    private suspend fun fetchChapterLightInner(url: String, budgetMs: Long): LoadResult = withContext(Dispatchers.IO) {
        val budget = if (budgetMs > 0) budgetMs else LIGHT_FETCH_TIMEOUT_MS
        val deadline = SystemClock.elapsedRealtime() + budget
        fun remaining(): Long = deadline - SystemClock.elapsedRealtime()
        fun pageTimeout(): Long = remaining().coerceIn(MIN_PAGE_FETCH_MS, MAX_PAGE_FETCH_MS)
        withTimeout(budget) {
            // 协议兜底：目录里的链接常是 http://，而站点可能只稳定支持 https（或反过来）。
            // 首次失败就换协议再试一次 —— 这类站点靠这一下就能把轻量通道救活，预取才有意义。
            var effectiveUrl = url
            var docOrNull: org.jsoup.nodes.Document? =
                try { lightFetchDoc(url, pageTimeout()) } catch (_: Exception) { null }
            if (docOrNull == null || isCloudflareChallenge(docOrNull)) {
                val alt = swapScheme(url)
                if (alt != null) {
                    val altDoc = try { lightFetchDoc(alt, pageTimeout()) } catch (_: Exception) { null }
                    if (altDoc != null) {
                        docOrNull = altDoc
                        effectiveUrl = alt
                    }
                }
            }
            val firstDoc = docOrNull ?: return@withTimeout LoadResult.Error("轻量抓取失败")
            if (isCloudflareChallenge(firstDoc)) return@withTimeout LoadResult.Error("轻量抓取遇到反爬验证")
            val originalBase = chapterBase(effectiveUrl)
            val title = extractChapterTitle(firstDoc)
            val sb = StringBuilder(extractContentFromDoc(firstDoc))
            // 跟随章内分页（"下一页"指向同章第 2、3… 页），保持轻量通道完整且快速；
            // 当"下一页"跨入下一章（URL 基础名不同）时停止，并将其作为 nextChapterUrl。
            // 同章分页优先；跨章链接单独记下来作为「下一章」，不再因为"第一条是下一章"而提前收工。
            val firstCands = findNextLinks(firstDoc, effectiveUrl)
            var nextPage: String? = pickSameChapterPage(firstCands, originalBase)
            var nextChapterUrl: String? = pickNextChapter(firstCands, originalBase)
            val prevChapterUrl: String? = pickPrevChapter(findPrevLinks(firstDoc, effectiveUrl), originalBase)
            val visited = mutableSetOf(normPageKey(effectiveUrl))
            var guard = 0
            while (guard < 15) {
                val page = nextPage?.takeIf { it.isNotBlank() } ?: break
                if (!visited.add(normPageKey(page))) break
                // 预算已耗尽：后面的分页直接放弃，用已抓到的正文返回
                if (remaining() < MIN_PAGE_FETCH_MS) break
                guard++
                val pDoc = try {
                    lightFetchDoc(page, pageTimeout())
                } catch (e: Exception) {
                    break
                }
                if (isCloudflareChallenge(pDoc)) break
                val pc = extractContentFromDoc(pDoc)
                if (pc.isBlank()) break
                sb.append(PAGE_SEP).append(pc)
                val cands = findNextLinks(pDoc, page)
                if (nextChapterUrl.isNullOrBlank()) nextChapterUrl = pickNextChapter(cands, originalBase)
                nextPage = pickSameChapterPage(cands, originalBase)
            }
            val content = sanitizeContent(sb.toString(), title)
            if (content.isBlank()) return@withTimeout LoadResult.Error("轻量抓取正文为空")
            // 与 WebView 通道同一道闸：错误页不能被当成正文（否则会污染缓存）
            if (looksLikeErrorPage(content, title)) {
                return@withTimeout LoadResult.Error("轻量抓取遇到错误页")
            }
            val bookTitle = extractBookTitleFromDocTitle(firstDoc)
            LoadResult.Success(title, content, nextChapterUrl, bookTitle, prevChapterUrl)
        }
    }

    /** 轻量通道解析目录的返回值：章节列表 + 目录页 + 是否抓全 + 断点。 */
    private data class TocLight(
        val items: List<ChapterItem>,
        val catalogUrl: String,
        val complete: Boolean,
        val nextPageUrl: String?
    )

    /** 目录「首页」的解析结果（章节列表 + 目录页地址 + 后续分页地址）。 */
    private data class TocHead(
        val items: List<ChapterItem>,
        val catalogUrl: String,
        val pages: List<String>
    )

    /**
     * 抓目录首页（在 [LIGHT_FETCH_TIMEOUT_MS] 预算内）。
     * 传入的 url 往往是「章节页」而非目录页：先尝试直接解析，拿不到足够章节再在页内找
     * 「目录/开始阅读/全部章节」入口并抓取目录页。
     */
    private suspend fun fetchTocHead(url: String): TocHead? = withContext(Dispatchers.IO) {
        withTimeout(LIGHT_FETCH_TIMEOUT_MS) {
            val firstDoc = try {
                lightFetchDoc(url)
            } catch (e: Exception) {
                return@withTimeout null
            }
            if (isCloudflareChallenge(firstDoc)) return@withTimeout null
            var catalogUrl = url
            var items = extractChapterListFromDoc(firstDoc, url)
            // 仅当页面已确认是目录页（章节链接足够多）时才跟随分页，
            // 避免把章节页的「下一章」误当目录分页抓出一堆垃圾链接。
            var pages: List<String> = if (items.size >= 10) extractTocPaginationUrls(firstDoc, url) else emptyList()
            // 直接解析出的链接太少（章节页常见只有「下一章/返回目录」等少量链接），
            // 多半不是真正的目录，尝试在页内找目录入口并抓取目录页；仍不足则交给 WebView。
            if (items.size < 3) {
                pages = emptyList()
                val tocHref = findTocLink(firstDoc, url)
                if (tocHref != null && tocHref != url) {
                    val tocDoc = try {
                        lightFetchDoc(tocHref)
                    } catch (e: Exception) {
                        null
                    }
                    if (tocDoc != null && !isCloudflareChallenge(tocDoc)) {
                        val tocItems = extractChapterListFromDoc(tocDoc, tocHref)
                        if (tocItems.size >= 3) {
                            items = tocItems
                            catalogUrl = tocHref
                            pages = extractTocPaginationUrls(tocDoc, tocHref)
                        }
                    }
                }
            }
            if (items.size < 3) return@withTimeout null
            // 目录页顶部常有「最新章节预览」块（倒序），直接按 DOM 顺序展示会出现
            // 「第2919章 / 第2918章 / 第1章 …」这种错乱顺序，这里统一按章节号排一次。
            TocHead(sortChapterItems(items), catalogUrl, pages)
        }
    }

    private suspend fun fetchChapterListLight(
        url: String,
        onFirstPage: ((List<ChapterItem>) -> Unit)? = null,
        resumeFrom: String? = null,
        baseItems: List<ChapterItem> = emptyList(),
        onProgress: ((List<ChapterItem>, String, String?) -> Unit)? = null
    ): TocLight? {
        // 第一步：目录首页。只有这一步受 15s 预算约束。
        val head = fetchTocHead(url) ?: return null
        // 首页目录已成形：先回吐给调用方（打开书时立刻去抓第一章），再慢慢补齐后续分页。
        // 回调在 IO 线程，调用方自行切主线程；异常不能影响目录抓取本身。
        if (onFirstPage != null) runCatching { onFirstPage(head.items) }
        if (head.pages.isEmpty()) {
            return TocLight(head.items, head.catalogUrl, complete = true, nextPageUrl = null)
        }
        // 打开书的场景下，首章请求刚刚发出；先让它独占几百毫秒带宽，
        // 别让并发的目录分页把首屏正文挤慢（目录本来就在后台，用户看不见）。
        if (onFirstPage != null) delay(350)

        // 断点续抓：从上次停下的那一页继续（缓存里可能已经有几百章，不必每页都重抓）
        val startIdx = resumeFrom
            ?.let { r -> head.pages.indexOfFirst { it == r } }
            ?.takeIf { it >= 0 } ?: 0
        val queue = if (startIdx == 0) head.pages else head.pages.subList(startIdx, head.pages.size)
        Log.d(
            "UrlLoader",
            "toc pages=${head.pages.size} start=$startIdx queue=${queue.size} " +
                "base=${baseItems.size} resume=$resumeFrom"
        )

        val merged = ArrayList<ChapterItem>(baseItems.size + head.items.size + 256)
        val seen = HashSet<String>(merged.size + 512)
        // 以「已经抓到的章节」为底，再接上这次首页解析出来的 —— 断点续抓只补后面几页，
        // 不能把前面已有的章节丢掉（否则越续越少）
        for (c in baseItems) if (seen.add(c.url)) merged.add(c)
        for (c in head.items) if (seen.add(c.url)) merged.add(c)
        // 记录「抓失败的分页」：整批成功但个别页超时时不能当作已完成 ——
        // 那一页的 20 章会永久缺失，用户看到的目录会中间断几段。
        val failedPages = LinkedHashSet<String>()
        // 本批起点先记下来：即使这一批抓到一半超时，下次也是从这里续，不会丢页
        var nextPage: String? = queue.firstOrNull()
        // 连续多少批一条新章节都没抓到：站点在限流（实测抓十几批后开始整批超时），
        // 继续硬抓只是白等，留着断点下次接着来。
        var emptyBatches = 0
        // 用「截止时间」而不是 withTimeout 控制预算：超时异常会从子协程冒出去，
        // 把一次正常的"抓不完"变成一个异常，进而错误地回退到慢得多的 WebView 通道。
        val deadline = android.os.SystemClock.elapsedRealtime() + TOC_PAGINATION_BUDGET_MS
        fun outOfTime(): Boolean = android.os.SystemClock.elapsedRealtime() >= deadline
        var timedOut = false

        // async/delay 需要一个 CoroutineScope 接收者；这里用 coroutineScope 包住分页阶段，
        // 顺便保证所有并发的分页请求在函数返回前全部结束（不会有"漏网的请求"改 merged）。
        coroutineScope {
            var i = 0
            while (i < queue.size) {
                if (outOfTime()) { timedOut = true; break }
                val batchStart = i
                val to = minOf(i + TOC_PAGE_BATCH, queue.size)
                nextPage = queue[batchStart]
                val batch = queue.subList(batchStart, to)
                val results = batch.map { u ->
                    async(Dispatchers.IO) { u to fetchTocPageLight(u) }
                }.awaitAll()
                i = to
                var added = false
                for ((u, res) in results) {
                    if (res.first.isEmpty()) {
                        Log.d("UrlLoader", "toc page empty: $u")
                        failedPages.add(u)
                        continue
                    }
                    failedPages.remove(u)
                    for (c in res.first) if (seen.add(c.url)) {
                        merged.add(c)
                        added = true
                    }
                }
                Log.d(
                    "UrlLoader",
                    "toc batch ${batchStart}-${to} got=${results.sumOf { it.second.first.size }} " +
                        "added=$added total=${merged.size} failed=${failedPages.size}"
                )
                nextPage = queue.getOrNull(i)
                if (added) {
                    emptyBatches = 0
                    // 边抓边回吐：用户翻着目录就能看到章节一条条变多，而不是等到全部抓完才刷新
                    if (onProgress != null) {
                        runCatching { onProgress(sortChapterItems(merged), head.catalogUrl, nextPage) }
                    }
                } else {
                    emptyBatches++
                    if (emptyBatches >= 3) break
                    // 整批一条都没抓到：多半是站点瞬时限流（连续抓上百个分页后常见），
                    // 停 2s 再从同一批重试，而不是直接放弃这一轮
                    nextPage = queue[batchStart]
                    delay(2_000)
                    i = batchStart
                }
            }

            // 第二轮：只重试失败的分页。限流大多是瞬时的，停一下再抓经常就过了 ——
            // 不重试的话，这些页对应的章节会永久缺失（目录中间断几段）。
            if (!timedOut && failedPages.isNotEmpty()) {
                val retry = failedPages.toList()
                failedPages.clear()
                var r = 0
                while (r < retry.size) {
                    if (outOfTime()) {
                        timedOut = true
                        // 预算耗尽：还没重试的页记回去，下次从这里续
                        for (k in r until retry.size) failedPages.add(retry[k])
                        break
                    }
                    delay(1_500)
                    val rEnd = minOf(r + TOC_PAGE_BATCH, retry.size)
                    val chunk = retry.subList(r, rEnd)
                    val results = chunk.map { u ->
                        async(Dispatchers.IO) { u to fetchTocPageLight(u) }
                    }.awaitAll()
                    r = rEnd
                    var added = false
                    for ((u, res) in results) {
                        if (res.first.isEmpty()) {
                            failedPages.add(u)
                            continue
                        }
                        for (c in res.first) if (seen.add(c.url)) {
                            merged.add(c)
                            added = true
                        }
                    }
                    if (added && onProgress != null) {
                        runCatching {
                            onProgress(sortChapterItems(merged), head.catalogUrl, failedPages.firstOrNull())
                        }
                    }
                }
            }
        }

        // 还有没抓到的页 → 不算「抓全」，把断点指过去，下次从这一页续
        if (failedPages.isNotEmpty()) nextPage = failedPages.first()
        var complete = !timedOut && nextPage == null && failedPages.isEmpty()
        var resumePoint: String? = if (complete) null else nextPage
        val finalItems = sortChapterItems(merged)
        // 自检：章节号大段缺失说明有分页被"假成功"吞掉了（例如站点返回了一份重复内容）。
        // 这时不能标成"完整"，否则那些章节永远补不回来（用户看到的就是目录缺章、下一章跳章）。
        if (complete && tocHasBigGaps(finalItems)) {
            complete = false
            // 从头再验一遍：已抓到的都会按 URL 去重，只会补齐缺的那些页
            resumePoint = head.pages.firstOrNull()
            Log.d("UrlLoader", "toc gap detected: ${finalItems.size} chapters, will re-verify")
        }
        // 关键：超时/中断时**保留已经抓到的部分**（旧实现一旦超时就把整批结果丢掉，
        // 几百页的大书永远只能显示首页那 20 章）。
        return TocLight(finalItems, head.catalogUrl, complete, resumePoint)
    }

    // 轻量通道抓取单个目录分页：返回 (章节链接, 更多分页链接)。
    private suspend fun fetchTocPageLight(u: String): Pair<List<ChapterItem>, List<String>> {
        return try {
            val doc = lightFetchDoc(u, TOC_PAGE_TIMEOUT_MS)
            extractChapterListFromDoc(doc, u) to extractTocPaginationUrls(doc, u)
        } catch (e: Exception) {
            Log.d("UrlLoader", "chapterList light page fail $u: ${e.message}")
            emptyList<ChapterItem>() to emptyList()
        }
    }

    // 并发抓取目录分页（每批 8 个、上限 60 页）：目录分页多为服务端直出，OkHttp 并发拉取
    // 比在单个 WebView 上逐页串行导航快得多；对纯 JS 渲染站点由调用方回退 WebView。
    // 批大小与 OkHttp 的 maxRequestsPerHost 对齐（见 client），否则超出的请求只会在队列里多等一整个 RTT。
    private suspend fun crawlTocPages(urls: List<String>): List<ChapterItem> = coroutineScope {
        val collected = mutableListOf<ChapterItem>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(urls.filter { visited.add(it) })
        var guard = 0
        while (queue.isNotEmpty() && guard < 60) {
            val batch = mutableListOf<String>()
            while (batch.size < 8 && queue.isNotEmpty()) {
                val u = queue.removeFirst()
                if (!visited.add(u)) continue
                batch.add(u)
            }
            if (batch.isEmpty()) break
            guard += batch.size
            val results = batch.map { u ->
                async(Dispatchers.IO) { u to fetchTocPageLight(u) }
            }.awaitAll()
            for ((_, res) in results) {
                collected.addAll(res.first)
                for (np in res.second) {
                    val n = np.substringBefore('#')
                    if (n !in visited) queue.addLast(n)
                }
            }
        }
        collected
    }

    // 在章节/首页文档中找出「目录/全部章节/开始阅读」等入口链接（与 WebView 的 tocLinkJs 等价）。
    private fun findTocLink(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        val tocRe = Regex(
            "^(目录|全文目录|全部目录|章节目录|小说目录|查看目录|完整目录|返回目录|返回书页|全部章节|查看全部章节|查看全部|展开全部|全部更新|正文章节|开始阅读|立即阅读|点击阅读|继续阅读|最新章节|章节列表|倒序|正序|默认排序|卷[一二三四五六七八九十两0-9]+)$"
        )
        for (a in doc.select("a")) {
            val href = a.attr("href") ?: continue
            if (href.isEmpty() || href.lowercase().startsWith("javascript:") || href.startsWith("#")) continue
            // navNorm：剥掉「返&nbsp回&nbsp目&nbsp录」式实体/空白混淆与「目—录」式一字线混淆后再整词匹配
            val text = navNorm(a.text())
            if (tocRe.containsMatchIn(text)) {
                return try {
                    java.net.URL(java.net.URL(baseUrl), href).toString()
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private suspend fun fetchSearchLight(searchUrl: String, keyword: String): List<SearchItem>? = withContext(Dispatchers.IO) {
        withTimeout(LIGHT_FETCH_TIMEOUT_MS) {
            val doc = try {
                lightFetchDoc(searchUrl)
            } catch (e: Exception) {
                return@withTimeout null
            }
            if (isCloudflareChallenge(doc)) return@withTimeout null
            val items = extractSearchFromDoc(doc, searchUrl, keyword)
            if (items.isEmpty()) null else items
        }
    }

    private fun extractSearchFromDoc(doc: org.jsoup.nodes.Document, baseUrl: String, keyword: String): List<SearchItem> {
        val kw = keyword.lowercase()
        val res = mutableListOf<SearchItem>()
        val seen = mutableSetOf<String>()
        val sels = arrayOf(
            "#site_search_list", ".result-list", ".book-list", ".search-list", ".search-result",
            ".search-result-list", ".novel-list", ".bookname", ".book-item", ".result-item",
            ".item", ".list", "#list", ".searchbox .book", ".search-results", ".mod_book_list",
            ".bookbox", ".books", ".book_list", ".updateList", ".listBox", ".search-results-list",
            ".result-box", "td.odd", "td.even", "tr.odd a", "tr.even a", "table.grid a", ".grid tr a"
        )
        val candidates = mutableListOf<org.jsoup.nodes.Element>()
        for (sel in sels) {
            candidates.addAll(doc.select(sel).select("a"))
        }
        candidates.addAll(doc.select("a"))
        for (a in candidates) {
            val href = a.attr("href") ?: continue
            if (href.isEmpty() || href.startsWith("javascript:") || href.startsWith("#")) continue
            val abs = try { java.net.URL(java.net.URL(baseUrl), href).toString() } catch (_: Exception) { continue }
            val u0 = abs.split("#")[0]
            if (u0 == baseUrl.split("#")[0]) continue
            val t = a.text().replace(wsCollapseRe, " ").trim()
            if (t.isEmpty() || t.length > 60 || t.length < 2) continue
            if (seen.contains(u0)) continue
            if (kw.isNotEmpty() && t.lowercase().indexOf(kw) < 0) continue
            seen.add(u0)
            res.add(SearchItem(t, u0))
            if (res.size >= 30) break
        }
        return res
    }

    /**
     * 目录完整性自检：真实目录的章节号应该基本连续。
     *
     * 为什么需要：分页抓取可能出现「某页返回了重复内容 / 被站点静默降级」却不报错的情况 ——
     * 那一页的 20 章会安静地消失，而抓取流程以为全部完成了。缺章在目录里表现为
     * 「第313章 直接跳到 第317章」，用户点下一章就跳章，事后极难排查。
     *
     * 阈值放宽到 8%（且至少 20 章），避免把「作者跳号 / 番外无编号」误判成残缺。
     */
    private fun tocHasBigGaps(items: List<ChapterItem>): Boolean {
        if (items.size < 200) return false
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        var counted = 0
        for (item in items) {
            val n = chapterNumber(item.title) ?: continue
            counted++
            if (n < min) min = n
            if (n > max) max = n
        }
        // 没有统一编号（很多站用「楔子/番外」这类标题），无从判断，直接放行
        if (counted < items.size / 2) return false
        val span = max - min + 1
        if (span <= 0 || span > 200_000) return false
        val missing = span - counted
        return missing > maxOf(20, (span * 8 / 100).toInt())
    }

    /**
     * 目录排序 + 归一：按章节号升序排，没有章节号的按 URL 里的数字排，最后保持原顺序。
     *
     * 两个必须处理的现实问题：
     * 1. 目录页顶部常有「最新章节预览」块（倒序 3~5 条），DOM 顺序直接展示会出现
     *    「第2919章 / 第2918章 / 第1章」这种错乱（用户反馈的"目录坏了"就是这个）。
     * 2. 章节号在比较器里现算会退化成 O(n log n) 次正则匹配，几千章要几十毫秒；
     *    这里先算一遍 key 再排，只花 O(n)。
     */
    private fun sortChapterItems(items: List<ChapterItem>): List<ChapterItem> {
        if (items.size < 2) return items
        var numbered = 0
        val keyed = ArrayList<Triple<Long, Int, ChapterItem>>(items.size)
        for (i in items.indices) {
            val item = items[i]
            val n = chapterNumber(item.title)
            if (n != null) numbered++
            keyed.add(Triple(n ?: urlNumber(item.url) ?: Long.MAX_VALUE, i, item))
        }
        // 章节号缺失过半时保持页面原始顺序，避免把没有编号的目录排乱
        if (numbered < items.size / 2) return items
        keyed.sortWith(compareBy({ it.first }, { it.second }))
        return keyed.map { it.third }
    }

    private fun extractChapterListFromDoc(doc: org.jsoup.nodes.Document, baseUrl: String): List<ChapterItem> {
        val seen = mutableSetOf<String>()
        val res = mutableListOf<ChapterItem>()
        // 同书判定（与 WebView 层 sameBook 等价）：链接需位于「首段路径前缀」下，
        // 或标题呈章节样式；否则目录页上的全站导航（首页/排行榜/阅读记录…）会混入目录。
        val baseUri = android.net.Uri.parse(baseUrl)
        val origin = "${baseUri.scheme ?: "http"}://${baseUri.host ?: ""}"
        val bookPrefix = Regex("^/[^/]+").find(baseUri.path ?: "")?.value?.let { "$it/" } ?: ""
        val sameBookPrefix = origin + bookPrefix
        val sels = arrayOf(
            "#list", "#chapterlist", ".list", ".chapter-list", ".catalog", ".mu_lu", ".mulu",
            ".book_list", "dl", "ol", "ul", "table", ".volume", ".volume-list", ".chapters"
        )
        // 用 LinkedHashSet 去重：同一个容器常被多个选择器同时命中（.list 与 ul 常常是同一个
        // 元素），不去重就会对同一批链接重复抽取几遍文本。
        val containers = LinkedHashSet<org.jsoup.nodes.Element>()
        for (sel in sels) containers.addAll(doc.select(sel))
        if (containers.isEmpty()) containers.add(doc.body())
        for (c in containers) {
            for (a in c.select("a")) {
                val href = a.attr("href") ?: continue
                if (href.isEmpty() || href.lowercase().startsWith("javascript:") || href.startsWith("#")) continue
                val abs = try { java.net.URL(java.net.URL(baseUrl), href).toString() } catch (_: Exception) { continue }
                val u0 = abs.split("#")[0]
                if (u0 == baseUrl.split("#")[0]) continue
                // 已经收进目录的链接直接跳过：a.text() 与正则归一化是这层最贵的操作，
                // 而容器嵌套会让同一条链接被反复扫到。
                if (seen.contains(u0)) continue
                val t = a.text().replace(wsCollapseRe, " ").trim()
                if (t.isEmpty() || t.length > 60 || t.length < 2) continue
                // navNorm：先归一化反爬混淆（上—章 / 目\u00A0录 等）再过导航词黑名单
                // 导航词黑名单是「子串匹配」，本来只为滤掉页面上的「上一页/目录/推荐」这类
                // 导航链接；但它会误伤正文标题 —— 例如「第314章 登陆龙船（求月票，求订阅）」
                // 里的「月票」会让整章被当成导航丢掉，目录就出现缺章（而且全程无感知）。
                // 这里改成：看起来像章节标题的链接一律保留，只对"不像章节"的链接做导航词过滤。
                val navT = navNorm(t)
                val looksLikeChapter = lightChapterTitleRe.containsMatchIn(navT)
                if (!looksLikeChapter && lightJunkRe.containsMatchIn(navT)) continue
                if (bookPrefix.isNotEmpty() && !u0.startsWith(sameBookPrefix) &&
                    !lightChapterTitleRe.containsMatchIn(navNorm(t))
                ) continue
                seen.add(u0)
                res.add(ChapterItem(t, u0))
            }
        }
        if (res.isEmpty()) {
            for (a in doc.select("a")) {
                val href = a.attr("href") ?: continue
                if (href.isEmpty() || href.lowercase().startsWith("javascript:") || href.startsWith("#")) continue
                val abs = try { java.net.URL(java.net.URL(baseUrl), href).toString() } catch (_: Exception) { continue }
                val u0 = abs.split("#")[0]
                if (u0 == baseUrl.split("#")[0]) continue
                val t = a.text().replace(wsCollapseRe, " ").trim()
                if (t.isEmpty() || t.length > 60 || t.length < 2) continue
                // 导航词黑名单是「子串匹配」，本来只为滤掉页面上的「上一页/目录/推荐」这类
                // 导航链接；但它会误伤正文标题 —— 例如「第314章 登陆龙船（求月票，求订阅）」
                // 里的「月票」会让整章被当成导航丢掉，目录就出现缺章（而且全程无感知）。
                // 这里改成：看起来像章节标题的链接一律保留，只对"不像章节"的链接做导航词过滤。
                val navT = navNorm(t)
                val looksLikeChapter = lightChapterTitleRe.containsMatchIn(navT)
                if (!looksLikeChapter && lightJunkRe.containsMatchIn(navT)) continue
                if (bookPrefix.isNotEmpty() && !u0.startsWith(sameBookPrefix) &&
                    !lightChapterTitleRe.containsMatchIn(navNorm(t))
                ) continue
                if (seen.contains(u0)) continue
                seen.add(u0)
                res.add(ChapterItem(t, u0))
            }
        }
        return res
    }

    // 从目录页文档中提取分页链接（下一页/尾页/页码/select 下拉等），用于并发抓取后续分页。
    private fun extractTocPaginationUrls(doc: org.jsoup.nodes.Document, baseUrl: String): List<String> {
        val res = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        // 文本翻页链接：navNorm 兼容「下—页」等一字线反爬变体。
        // 注意不收「首页/上页/上一页」：目录当前页即首页，向后翻无意义；且这些链接常指向
        // 站首页/书架，被抓取后会在其上重新提取出一堆全站导航假章节。
        val pageRe = Regex("^(下一页|下页|下頁|下一頁|尾页|末页|末頁|最後一页|最后一页|第\\d+页|\\d+|>|>>|»|\\d+/\\d+)\$")
        fun addPage(href: String?) {
            if (href.isNullOrEmpty() || href.lowercase().startsWith("javascript:") || href.startsWith("#")) return
            val abs = try {
                java.net.URL(java.net.URL(baseUrl), href).toString()
            } catch (_: Exception) {
                return
            }
            val key = abs.substringBefore('#')
            if (key != baseUrl.substringBefore('#') && seen.add(key)) res.add(abs)
        }
        // 文本翻页链接：navNorm 兼容「下—页」等一字线反爬变体
        for (a in doc.select("a")) {
            val t = a.text().trim()
            val cls = (a.className() ?: "").lowercase()
            val rel = (a.attr("rel") ?: "").lowercase()
            val isPage = pageRe.containsMatchIn(navNorm(t)) || pageRe.containsMatchIn(stripInvisibleWs(t)) ||
                cls.contains("page") || rel == "next"
            if (!isPage) continue
            addPage(a.attr("href"))
        }
        // select 下拉分页（如 /html/{book}/asc-42/ 页码跳转）：一次拿到全部分页地址便于并发抓取。
        // 仅接受路径型 value（含 / 或 .），避免纯数字 value 被拼成无效相对地址。
        for (opt in doc.select("select option")) {
            val v = opt.attr("value") ?: continue
            if (v.isEmpty() || !(v.contains('/') || v.contains('.'))) continue
            addPage(v)
        }
        return res
    }

    /**
     * 从页面里提取正文。
     *
     * 这里规避了三条会「凭空少字」的坑：
     * 1. 不能用 remove() 直接删 &lt;a&gt;：节点删除会连带删掉链接里的文字，而正文内链（书名/人名/关键词）
     *    在小说站非常常见 —— 旧实现每遇到一个内链就少几个字。改为「拆壳保文字」。
     * 2. 广告/导航判定不能用 cls.contains("ad") 这种子串匹配："read"、"load"、"head"、"shadow"
     *    全都包含 "ad"，会把正文容器整块当成广告删掉。改为按整词匹配。
     * 3. 候选容器的「长度评估」和「最终输出」必须用同一套口径（都在清洗后的树上跑 collectText），
     *    否则会选中一个「看起来很长、其实大半是导航链接」的容器，真正的正文反而被丢弃。
     */
    private fun extractContentFromDoc(doc: org.jsoup.nodes.Document): String {
        val body = doc.body() ?: return ""
        val clone = body.clone()
        // 注意此处的 remove 列表里没有 <a>：<a> 交给 unwrapAnchors 拆壳处理。
        clone.select("script,style,nav,header,footer,aside,img,figure,iframe,ins,.recommend,.page-link,.bottom-link,.top-link,.ad,.popup,.modal,.comment,.share,.breadcrumb,.pagination,.copyright").remove()
        unwrapAnchors(clone)
        var best: org.jsoup.nodes.Element? = null
        var bestLen = 0
        val matched = ArrayList<Pair<org.jsoup.nodes.Element, Int>>()
        for (cand in CONTENT_SELECTORS) {
            for (e in clone.select(cand)) {
                val len = collectText(e).length
                matched.add(e to len)
                if (len > bestLen) bestLen = len
            }
        }
        // 多个候选命中时优先「更具体（声明靠前、层级更内）」的容器：只要它的文字量不低于
        // 最大值的 60% 就选它。否则像 .content_novel 这种把顶部「上—页/返回目录/下—页」
        // 导航一起包住的父容器会胜出，导航词又被当成正文。
        if (bestLen > 0) {
            best = matched.firstOrNull { it.second >= bestLen * 0.6f }?.first
                ?: matched.maxByOrNull { it.second }?.first
        }
        // 候选全未命中时不能直接把整个 body 当正文 —— 那会把站点导航（书名/护眼/关灯/大中小/
        // 上一章/下一章/返回目录）连同章节标题一起喂给阅读页，用户看到的就是「正文和书名混一块」。
        // 改成逐层下沉到「独占父级大半文字量」的子节点，通常正好落在正文容器上。
        if (best == null) best = densestTextBlock(clone)
        return cleanText(if (best != null) collectText(best) else collectText(clone))
    }

    /**
     * 兜底找正文容器：从 body 起逐层选「文字量最大的子节点」，只要该子节点独占父级一半以上
     * 文字就继续下沉，否则说明正文分散在多个同级块里，停在上层。
     *
     * 例：body → #novelbody → .content_novel → #novelcontent → &lt;p&gt;，
     * 站点导航（返回书页/护眼/关灯/大中小）在旁支上，会在下沉过程中被自然甩掉。
     */
    private fun densestTextBlock(root: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        var cur: org.jsoup.nodes.Element = root
        var curLen = collectText(cur).length
        var guard = 0
        while (guard++ < 12) {
            var next: org.jsoup.nodes.Element? = null
            var nextLen = 0
            for (child in cur.children()) {
                if (isJunkElement(child)) continue
                val len = collectText(child).length
                if (len > nextLen) { nextLen = len; next = child }
            }
            if (next == null || nextLen < curLen * 0.5f) break
            cur = next
            curLen = nextLen
        }
        return if (cur === root) null else cur
    }

    /** 拆掉 <a> 标签但保留其中的文字（直接 remove 会把正文里的内链文字一起删掉）。 */
    private fun unwrapAnchors(root: org.jsoup.nodes.Element) {
        for (a in root.select("a").toList()) {
            val t = a.text()
            if (t.isNotBlank()) a.before(org.jsoup.nodes.TextNode(t))
            a.remove()
        }
    }

    private fun extractChapterTitle(doc: org.jsoup.nodes.Document): String {
        val h = doc.selectFirst("h1,h2,h3,h4")
        val t = h?.text() ?: doc.title() ?: ""
        return cleanText(t)
    }

    /**
     * 返回页面上所有「下一页 / 下一章」候选链接（按 DOM 顺序去重）。
     * 旧实现只返回第一条命中：章内分页与「下一章」并存时，先命中「下一章」就会把整章的
     * 第 2、3 页全部漏掉，表现为这一章字数明显偏少。
     */
    private fun findNextLinks(doc: org.jsoup.nodes.Document, baseUrl: String): List<String> {
        val res = mutableListOf<String>()
        val self = normPageKey(baseUrl)
        for (a in doc.select("a")) {
            val lt = (a.text() + " " + (a.attr("title") ?: ""))
            val cls = (a.className() ?: "").lowercase()
            val rel = (a.attr("rel") ?: "").lowercase()
            val href = a.attr("href") ?: continue
            if (href.lowercase().startsWith("javascript:")) continue
            // navNorm：兼容「下—页」等一字线反爬变体，否则章内分页断链、正文只抓到第 1 页
            if (lightNextRe.containsMatchIn(navNorm(lt)) || cls.indexOf("next") >= 0 || rel == "next") {
                val abs = try { java.net.URL(java.net.URL(baseUrl), href).toString() } catch (_: Exception) { continue }
                val key = normPageKey(abs)
                if (key != self && res.none { normPageKey(it) == key }) res.add(abs)
            }
        }
        return res
    }

    /** 收集「上一章」链接（只认章级词，不认「上一页」分页） */
    private fun findPrevLinks(doc: org.jsoup.nodes.Document, baseUrl: String): List<String> {
        val res = mutableListOf<String>()
        val self = normPageKey(baseUrl)
        val prevRe = Regex(
            "上一章|上一頁|上章|上一節|上一节|上一篇|前一章|回上一章|prev",
            RegexOption.IGNORE_CASE
        )
        for (a in doc.select("a")) {
            val lt = navNorm(a.text() + " " + (a.attr("title") ?: ""))
            if (!prevRe.containsMatchIn(lt)) continue
            val href = a.attr("href")
            if (href.isEmpty() || href.lowercase().startsWith("javascript:")) continue
            val abs = try {
                java.net.URL(java.net.URL(baseUrl), href).toString()
            } catch (_: Exception) {
                continue
            }
            if (normPageKey(abs) != self && res.none { normPageKey(it) == normPageKey(abs) }) res.add(abs)
        }
        return res
    }

    private fun isCloudflareChallenge(doc: org.jsoup.nodes.Document): Boolean {
        val dt = (doc.title() ?: "").lowercase()
        val bd = (doc.body()?.text() ?: "").lowercase()
        return dt.indexOf("just a moment") >= 0 || dt.indexOf("attention required") >= 0 ||
            bd.indexOf("enable javascript and cookies") >= 0 || bd.indexOf("checking your browser") >= 0
    }

    private fun extractBookTitleFromDocTitle(doc: org.jsoup.nodes.Document): String? {
        val dt = doc.title() ?: return null
        val parts = dt.split("-", "_", "—").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        // 站点标题有两种常见排布：「书名-章节名-站点名」和「章节名-书名-站点名」。
        // 取第一个不含「第x章」的片段当书名；全都像章节名时退回倒数第二段（旧行为）。
        // 旧实现固定取倒数第二段，遇到三段式就会把「第17章 喝吐了」当书名写进历史/书架。
        val book = parts.firstOrNull { !lightChapterTitleRe.containsMatchIn(it) }
        val cand = (book ?: parts[parts.size - 2]).trim()
        return if (cand.isNotBlank()) stripBookNameBrackets(cand) else null
    }

    private fun stripBookNameBrackets(s: String): String {
        return s.replace("[(\\[【《](.*?)[)\\]】》]".toRegex(), "").trim()
    }

    private fun collectText(node: org.jsoup.nodes.Node): String {
        val sb = StringBuilder()
        fun newline() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }
        fun walk(n: org.jsoup.nodes.Node) {
            if (n is org.jsoup.nodes.TextNode) {
                // 用 wholeText 保留原始空格：旧实现既会在块内把文本 trim() 后直接拼接（吞掉词间空格），
                // 又会在非块元素里强行补 ' '（把中文句子撑成「第 一 句」）。这里既不吞也不造。
                // 只把 HTML 源码里的换行折成单个 \n，避免缩进被当成正文空格。
                val raw = n.wholeText.replace(Regex("[\\r\\n]+[ \\t]*"), "\n")
                if (raw.isNotEmpty()) {
                    if (sb.isEmpty() || sb.last() == '\n') sb.append(raw.trimStart()) else sb.append(raw)
                }
            } else if (n is org.jsoup.nodes.Element) {
                if (isJunkElement(n)) return
                val tag = n.tagName().lowercase()
                when {
                    tag == "br" -> sb.append('\n')
                    tag in BLOCK_TAGS -> {
                        newline()
                        n.childNodes().forEach { walk(it) }
                        newline()
                    }
                    else -> n.childNodes().forEach { walk(it) }
                }
            }
        }
        node.childNodes().forEach { walk(it) }
        return sb.toString()
    }

    private fun isJunkElement(e: org.jsoup.nodes.Element): Boolean {
        val tag = e.tagName()
        if (tag == "script" || tag == "style" || tag == "nav" || tag == "header" ||
            tag == "footer" || tag == "aside" || tag == "ins"
        ) return true
        val cls = (e.className() ?: "").lowercase()
        if (cls.isNotBlank() && JUNK_CLASS_RE.containsMatchIn(cls)) return true
        val id = (e.id() ?: "").lowercase()
        if (id.isNotBlank() && JUNK_ID_RE.containsMatchIn(id)) return true
        return false
    }

    private fun cleanText(s: String): String {
        return s.replace("\r\n", "\n").replace('\r', '\n')
            // 不可见空白（含 U+00A0 / U+200B / U+FEFF）→ 普通空格；不碰全角空格 U+3000（中文缩进）
            .replace(Regex("[\\t\\u00A0\\u2007\\u202F\\u200B\\uFEFF]+"), " ")
            .replace(Regex("[ ]{2,}"), " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // ---- 正文收尾清洗（抓取通道与 WebView 通道共用） ----
    // 章内分页残留的页码标记：「(第1/3页)」「（第 2/3 页）」
    private val pageMarkerRe = Regex("[（(]\\s*第\\s*\\d+\\s*[/／]\\s*\\d+\\s*页\\s*[）)]")
    // 纯导航/工具栏行（比较前先经 navNorm：「下—章」→「下一章」、「返 回 目 录」→「返回目录」）
    private val NAV_ONLY_LINES = setOf(
        "上一章", "下一章", "上一页", "下一页", "上页", "下页", "上一节", "下一节", "上一節", "下一節",
        "返回目录", "返回书页", "目录", "加入书架", "加入书签", "进入书架", "书架", "我的书架",
        "首页", "护眼", "关灯", "阅读记录", "返回", "投票", "推荐", "下载", "手机阅读", "阅读器",
        "上一章目录", "下一章目录", "章节目录", "全部章节"
    )
    // 阅读器字号按钮（只在页面上确实存在「护眼/关灯」工具栏时才按按钮处理）
    private val FONT_SIZE_LINES = setOf("大", "中", "小", "特大", "较大", "较小")
    // 站点横幅：「最新网址：xxx」「最快更新：xxx」
    private val siteBannerRe = Regex("^(最新网址|最新地址|最快更新|本站网址|记住本站|手机用户请|请记住本书)[:：]?.*$")
    // 行内横幅（导航词与横幅粘在一行时先把横幅抠掉，剩下的往往就是纯导航词）
    private val bannerInlineRe = Regex("(最新网址|最新地址|最快更新|本站网址|记住本站)[:：]?\\s*\\S*")
    // 行首可能粘连的导航词（「下一页第16章 xxx」「下—页最新网址：xxx」）
    private val NAV_PREFIX_NORMS = listOf(
        "返回目录", "返回书页", "加入书架", "加入书签", "进入书架", "手机阅读",
        "上一章", "下一章", "上一页", "下一页"
    )
    // 翻页提示：「（本章未完，请点击下一页继续阅读）」「未完待续」
    private val pageHintRe = Regex("^[（(]?本章未完.*$|^[（(]?本章尚未结束.*$|^未完待续[。.!！]?$")
    // 站点残留的孤立标点行（「.」「。。」）；不含省略号「……」—— 那是正文常用的独立段落
    private val lonePunctRe = Regex("^[.。,，;；:：!！?？]{1,3}$")

    /**
     * 正文收尾清洗。修的是四类「章节内容被站点外壳污染」的问题：
     *
     * 1. 页码标记「(第1/3页)」按整行删除会把紧跟其后的换行一起吃掉，
     *    于是「第16章 xxx」和正文第一句粘成一行（用户看到的就是「章节标题和正文混一块」）。
     *    这里改成把标记换成换行，标题与正文各占一行。
     * 2. 整页兜底（正文容器没命中）时，书名/护眼/关灯/大中小/上一章/下一章/返回目录
     *    这些站内外壳会被当成正文，按「整行等于导航词」逐行剔除。
     * 3. 多页拼接时每页开头的章节标题会重复出现，只保留第一次（章首显示一次即可）。
     * 4. 章内分页常把一句话劈成两半（上页结尾「…掀了出」＋下页开头「去，只见…」），
     *    按页界判断：上页没有句末标点就把下页第一行直接续上，避免正文中间出现硬断段。
     */
    private fun sanitizeContent(raw: String, chapterTitle: String): String {
        val fragments = raw.split(PAGE_SEP)
        val pages = fragments.map { sanitizeFragment(it, chapterTitle) }.filter { it.isNotBlank() }
        if (pages.isEmpty()) return ""
        val sb = StringBuilder(pages[0])
        for (i in 1 until pages.size) {
            val last = sb.lastOrNull()
            if (last != null && last !in SENTENCE_END_CHARS) {
                sb.append(pages[i])          // 跨页断句：直接续写
            } else {
                sb.append('\n').append(pages[i])
            }
        }
        return sb.toString().trim()
    }

    /** 单页正文的行级清洗（导航空行、页码标记、站点横幅、重复章节标题）。 */
    private fun sanitizeFragment(raw: String, chapterTitle: String): String {
        val text = raw.replace(pageMarkerRe, "\n")
        val lines = text.split("\n").map { it.trim() }
        // 只有页面上确实存在阅读器工具栏（护眼/关灯）时，才把单字「大/中/小」当字号按钮删，
        // 避免误伤正文里本来就独占一行的「大」「中」「小」。
        val hasReaderToolbar = lines.any { navNorm(it) == "护眼" || navNorm(it) == "关灯" }
        val normTitle = navNorm(chapterTitle)
        var titleKept = false
        val kept = ArrayList<String>(lines.size)
        for (rawLine in lines) {
            // 先把行内站点横幅抠掉：「下—页最新网址：xxx」抠完只剩「下—页」，可当纯导航词丢弃
            val line = rawLine.replace(bannerInlineRe, "").trim()
            if (line.isEmpty()) continue
            val norm = navNorm(line)
            if (norm.isEmpty() || norm in NAV_ONLY_LINES) continue
            if (hasReaderToolbar && norm in FONT_SIZE_LINES) continue
            if (siteBannerRe.matches(norm)) continue
            if (line.length <= 40 && pageHintRe.matches(line)) continue
            if (lonePunctRe.matches(line)) continue
            // 行首粘了导航词（「下一页第16章 xxx」）：剥掉后只剩章节标题/横幅/空，则该行没有正文价值
            val rest = stripNavPrefix(norm)
            if (rest != norm) {
                if (rest.isEmpty() || rest == normTitle || siteBannerRe.matches(rest) || rest.length <= 2) continue
            }
            if (normTitle.isNotEmpty() && norm == normTitle) {
                // 重复出现的章节标题（多页拼接、页首标题）只留第一处
                if (titleKept) continue
                titleKept = true
            }
            kept.add(line)
        }
        return kept.joinToString("\n").trim()
    }

    /** 去掉归一化行首的导航词，返回剩余部分（没命中则原样返回）。 */
    private fun stripNavPrefix(norm: String): String {
        for (p in NAV_PREFIX_NORMS) {
            if (norm.length > p.length && norm.startsWith(p)) return norm.substring(p.length)
        }
        return norm
    }
}
