package com.example.novelreader

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray

/**
 * 搜索页（原生 UI）：
 * - 顶栏为原生搜索输入胶囊，键盘搜索键直接出结果，搜索引擎固定为必应；
 * - 未搜索时展示原生首页面板：品牌引导、最近搜索（长按删除）、常用网站快捷入口；
 * - 菜单提供「在阅读页打开当前页」：取当前 WebView URL 交给 ReadingActivity 解析目录并阅读；
 * - 支持网页内文件下载（TXT/EPUB 等直链）：交给系统下载管理器存到"下载/NovelReader"。
 *
 * 跳转管控（见 NavigationGuard）：小说站普遍存在脚本自动跳转、302 跳转链、弹窗、
 * intent:// 诱导装 App，这里统一拦下来——只有用户手指点中的链接才放行，
 * 被拦的跳转会在顶部提示条里给出目标域名，用户可手动「打开」。
 *
 * 返回逻辑：WebView 有历史 → 网页后退；已在结果页 → 回搜索首页；在首页 → 退出本页。
 */
class SearchActivity : AppCompatActivity() {

    /** 常用小说网站快捷入口 */
    private val quickSites = listOf(
        "起点中文网" to "https://www.qidian.com",
        "晋江文学城" to "https://www.jjwxc.net",
        "纵横中文网" to "https://www.zongheng.com",
        "七猫中文网" to "https://www.qimao.com"
    )

    private lateinit var btnBack: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var etQuery: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var homePanel: ScrollView
    private lateinit var historyRow: LinearLayout
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var btnClearHistory: TextView
    private lateinit var sitesRow: LinearLayout
    private lateinit var webView: WebView
    private lateinit var blockedBar: LinearLayout
    private lateinit var tvBlocked: TextView
    private lateinit var btnBlockedOpen: TextView
    private lateinit var btnBlockedDismiss: TextView

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var currentQuery: String? = null

    /** 跳转管控：只有点了链接才放行，自动跳转/弹窗/外部协议一律拦截 */
    private val guard: NavigationGuard by lazy { NavigationGuard(prefs) }

    /** 最后一次手指按下 WebView 的时间，用于识别「这次跳转是人点出来的」 */
    private var lastTouchDownAt = 0L

    /** 当前被拦下的跳转，「打开」按钮据此放行 */
    private var blockedTarget: NavigationGuard.Decision? = null

    /** 当前页面累计拦截次数，用于提示「本页已拦截 N 次」 */
    private var blockedCount = 0

    /** 提示条自动收起 */
    private val hideBlockedBar = Runnable {
        if (::blockedBar.isInitialized) blockedBar.visibility = View.GONE
    }

    /** Android 9 及以下下载到公共"下载"目录需要存储权限；授权后续跑挂起的下载 */
    private var pendingDownload: (() -> Unit)? = null
    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownload
            pendingDownload = null
            if (granted && action != null) {
                action()
            } else {
                Toast.makeText(this, "未授予存储权限，无法下载文件", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        initViews()
        setupInsets()
        setupWebView()
        setupSearchBox()
        setupBackHandling()
        renderHistory()
        renderSites()

        // 深链自动搜索（无此 extra 时不触发，首页入口不受影响）
        val deepQuery = intent.getStringExtra("EXTRA_QUERY")
        if (!deepQuery.isNullOrBlank()) {
            etQuery.setText(deepQuery)
            btnClear.visibility = View.VISIBLE
            doSearch(deepQuery)
        }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_search_back)
        btnMenu = findViewById(R.id.btn_menu)
        btnClear = findViewById(R.id.btn_clear)
        etQuery = findViewById(R.id.et_query)
        progressBar = findViewById(R.id.progress_bar)
        homePanel = findViewById(R.id.home_panel)
        historyRow = findViewById(R.id.history_row)
        tvHistoryEmpty = findViewById(R.id.tv_history_empty)
        btnClearHistory = findViewById(R.id.btn_clear_history)
        sitesRow = findViewById(R.id.sites_row)
        webView = findViewById(R.id.web_view)
        blockedBar = findViewById(R.id.blocked_bar)
        tvBlocked = findViewById(R.id.tv_blocked)
        btnBlockedOpen = findViewById(R.id.btn_blocked_open)
        btnBlockedDismiss = findViewById(R.id.btn_blocked_dismiss)

        btnBack.setOnClickListener { handleBack() }
        btnMenu.setOnClickListener { showMenu() }
        btnClearHistory.setOnClickListener {
            writeHistory(emptyList())
            Toast.makeText(this, "已清空搜索记录", Toast.LENGTH_SHORT).show()
        }

        // 被拦下的跳转：用户点「打开」才真的过去，并记住该站点本会话不再拦
        btnBlockedOpen.setOnClickListener {
            val target = blockedTarget ?: return@setOnClickListener
            if (target.reason == NavigationGuard.Reason.EXTERNAL_SCHEME) return@setOnClickListener
            guard.allowForSession(target.host)
            dismissBlockedBar()
            if (target.url.isNotBlank()) {
                showWeb()
                webView.loadUrl(target.url)
            }
        }
        btnBlockedDismiss.setOnClickListener { dismissBlockedBar() }
    }

    // ---------- 跳转拦截 ----------

    /** 记录触摸时间：命中 <a> 与否交给 hitTestResult 判断，这里只负责「刚才有没有人点屏幕」 */
    private fun touchTimeOf(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchDownAt = SystemClock.uptimeMillis()
        }
    }

    private fun isRecentTouch(): Boolean =
        lastTouchDownAt > 0L && SystemClock.uptimeMillis() - lastTouchDownAt <= TAP_WINDOW_MS

    /**
     * 触摸落点是否压在 <a href> 上。
     * 命中链接才返回其地址（可能是 javascript: 之类的伪链接），点到空白处返回 null——
     * 这一步是区分「用户点了链接」和「点哪都跳广告」的关键。
     * 备注：ANCHOR_TYPE / IMAGE_ANCHOR_TYPE 在现代 WebView 里已不再返回，
     * 点击 http 链接只会得到 SRC_ANCHOR_TYPE（链接里套图片则是 SRC_IMAGE_ANCHOR_TYPE）。
     */
    private fun anchorHrefAt(): String? {
        val hit = webView.hitTestResult ?: return null
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> hit.extra ?: ""
            else -> null
        }
    }

    private fun shouldBlock(request: NavigationGuard.Request): NavigationGuard.Decision {
        val decision = guard.evaluate(request)
        if (!decision.allowed) showBlockedBar(decision)
        return decision
    }

    /** 顶部提示条：说明拦了什么、拦到哪，并给一次性放行入口 */
    private fun showBlockedBar(decision: NavigationGuard.Decision) {
        blockedTarget = decision
        blockedCount++
        progressBar.visibility = View.GONE
        val label = when (decision.reason) {
            NavigationGuard.Reason.AUTO_REDIRECT -> "已拦截自动跳转"
            NavigationGuard.Reason.CLICK_HIJACK -> "点击的不是链接，已拦截跳转"
            NavigationGuard.Reason.CROSS_SITE_CONFIRM -> "站外链接，确认后打开"
            NavigationGuard.Reason.POPUP -> "已拦截网页弹窗"
            NavigationGuard.Reason.EXTERNAL_SCHEME -> "已拦截外部应用跳转"
            null -> "已拦截跳转"
        }
        tvBlocked.text = buildString {
            append(label)
            if (decision.host.isNotBlank()) append(" → ").append(decision.host)
            if (decision.reason == NavigationGuard.Reason.EXTERNAL_SCHEME) {
                append("（本应用不会打开外部应用）")
            }
            if (blockedCount > 3) append("（本页已拦截 ").append(blockedCount).append(" 次）")
        }
        // 外部协议不做「打开」入口（不把用户送去系统外部应用）；
        // 拿不到地址的弹窗也给不了入口，只留「忽略」
        btnBlockedOpen.visibility =
            if (decision.reason == NavigationGuard.Reason.EXTERNAL_SCHEME || decision.url.isBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        blockedBar.visibility = View.VISIBLE
        blockedBar.removeCallbacks(hideBlockedBar)
        blockedBar.postDelayed(hideBlockedBar, BLOCKED_BAR_TTL_MS)
    }

    private fun dismissBlockedBar() {
        blockedTarget = null
        blockedBar.removeCallbacks(hideBlockedBar)
        blockedBar.visibility = View.GONE
    }

    /** 全面屏/挖孔适配：顶栏加 statusBar 顶部留白，内容区避让导航栏，浅色背景用深色状态栏图标 */
    private fun setupInsets() {
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.top_bar)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = sb.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.content_container)) { v, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nb.bottom)
            insets
        }
    }

    private fun setupSearchBox() {
        btnClear.setOnClickListener {
            etQuery.setText("")
            etQuery.requestFocus()
        }
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        etQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                doSearch(etQuery.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // 小说站常见 https 页面挂 http 图片，允许兼容混排避免图裂
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 安全加固：网页不允许读本地文件，也不许自己开新窗口
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // window.open / target=_blank 交给 onCreateWindow 统一处置，不让 WebView 自己乱开
            setSupportMultipleWindows(true)
        }

        // 记录手指按下时间，作为「这次跳转是不是人点出来的」的独立信号
        webView.setOnTouchListener { _, event ->
            touchTimeOf(event)
            false
        }

        // 设置 WebViewClient 后，点击搜索结果链接会在本 WebView 内继续加载（而非外跳浏览器）
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                // 只管顶层跳转；子框架、资源加载不干预，避免弄坏正常页面
                if (!req.isForMainFrame) return false
                val decision = shouldBlock(
                    NavigationGuard.Request(
                        url = req.url?.toString(),
                        fromUrl = view?.url,
                        reportedGesture = req.hasGesture(),
                        recentTouch = isRecentTouch(),
                        redirect = req.isRedirect(),
                        anchorHref = anchorHrefAt()
                    )
                )
                return !decision.allowed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                // 新页面开始加载：手势与拦截计数归零，只有本页上的新点击才作数
                lastTouchDownAt = 0L
                blockedCount = 0
                dismissBlockedBar()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress in 1..99) progressBar.visibility = View.VISIBLE
            }

            /**
             * 网页想开新窗口（window.open / target="_blank"）：
             * 一律不真的创建窗口，只有「刚点中的是 http(s) 链接」才就地在本 WebView 里打开，
             * 其余（广告弹窗、脚本 window.open）直接拦下并提示。
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val wv = view ?: return false
                val href = anchorHrefAt()?.takeIf {
                    isRecentTouch() && it.startsWith("http", ignoreCase = true)
                }
                if (href.isNullOrBlank()) {
                    showBlockedBar(
                        NavigationGuard.Decision(false, NavigationGuard.Reason.POPUP, "", "", "")
                    )
                    return false
                }
                val decision = shouldBlock(
                    NavigationGuard.Request(
                        url = href,
                        fromUrl = wv.url,
                        reportedGesture = isUserGesture,
                        recentTouch = true,
                        redirect = false,
                        anchorHref = href,
                        popup = true
                    )
                )
                if (decision.allowed && decision.url.isNotBlank()) {
                    wv.loadUrl(decision.url)
                }
                return false
            }
        }

        // 网页内的文件下载（小说站常见的「下载 TXT / EPUB」）：交给系统下载管理器，
        // 完成后保存在公共"下载/NovelReader"目录，可在通知栏查看进度
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }
    }

    // ---------- 搜索流程 ----------

    private fun doSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "请输入书名或作者名", Toast.LENGTH_SHORT).show()
            return
        }
        currentQuery = q
        if (etQuery.text.toString() != q) {
            etQuery.setText(q)
            etQuery.setSelection(q.length)
        }
        hideKeyboard()
        saveHistory(q)
        loadSearch(q)
    }

    private fun loadSearch(q: String) {
        showWeb()
        webView.loadUrl(BING_SEARCH_URL + URLEncoder.encode(q, "UTF-8"))
    }

    private fun openSite(url: String) {
        showWeb()
        webView.loadUrl(url)
    }

    private fun showWeb() {
        homePanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun showHome() {
        progressBar.visibility = View.GONE
        dismissBlockedBar()
        webView.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
    }

    // ---------- 搜索历史 ----------

    private fun saveHistory(q: String) {
        val list = readHistory().toMutableList()
        list.remove(q)
        list.add(0, q)
        while (list.size > HISTORY_LIMIT) list.removeAt(list.lastIndex)
        writeHistory(list)
    }

    private fun removeHistory(q: String) {
        writeHistory(readHistory().filter { it != q })
        Toast.makeText(this, "已删除「$q」", Toast.LENGTH_SHORT).show()
    }

    private fun readHistory(): List<String> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }

    private fun writeHistory(list: List<String>) {
        prefs.edit().putString(KEY_HISTORY, JSONArray(list).toString()).apply()
        renderHistory()
    }

    private fun renderHistory() {
        historyRow.removeAllViews()
        val list = readHistory()
        tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        btnClearHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEach { q ->
            val chip = makeChip(q)
            chip.setOnClickListener { doSearch(q) }
            chip.setOnLongClickListener { removeHistory(q); true }
            historyRow.addView(chip)
        }
    }

    private fun renderSites() {
        quickSites.forEach { (name, url) ->
            val chip = makeChip(name)
            chip.setOnClickListener { openSite(url) }
            sitesRow.addView(chip)
        }
    }

    private fun makeChip(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            maxWidth = dp(150)
            setBackgroundResource(R.drawable.bg_chip_off)
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            val h = dp(12)
            setPadding(h, dp(6), h, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
    }

    // ---------- 菜单与返回 ----------

    private fun showMenu() {
        val popup = PopupMenu(this, btnMenu)
        popup.menuInflater.inflate(R.menu.search_menu, popup.menu)
        popup.menu.findItem(R.id.menu_strict_mode).isChecked = guard.strictMode
        popup.menu.findItem(R.id.menu_clear_allowed).isVisible = guard.allowedCount > 0
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_home -> { showHome(); true }
                R.id.menu_open_in_reader -> { openCurrentInReader(); true }
                R.id.menu_download_manager -> {
                    startActivity(Intent(this, DownloadsActivity::class.java)); true
                }
                R.id.menu_refresh -> { if (webView.visibility == View.VISIBLE) webView.reload() else showHome(); true }
                R.id.menu_copy_url -> { copyCurrentUrl(); true }
                R.id.menu_strict_mode -> { toggleStrictMode(); true }
                R.id.menu_clear_allowed -> {
                    guard.clearSessionAllowed()
                    Toast.makeText(this, "已清除本次放行的站点", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 严格模式：连用户自己点中的站外链接也先提示，确认后才过去 */
    private fun toggleStrictMode() {
        guard.strictMode = !guard.strictMode
        val tip = if (guard.strictMode) {
            "已开启严格模式：所有站外跳转都会先提示确认"
        } else {
            "已切换为智能拦截：点击的链接可直接打开，自动跳转仍会被拦截"
        }
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
    }

    /**
     * 菜单核心功能：将 WebView 当前页面跳转到阅读页。
     * 取当前 URL（及页面标题）交给 ReadingActivity 作为书籍主页 URL 解析目录、加载首章。
     */
    private fun openCurrentInReader() {
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank" || webView.visibility != View.VISIBLE) {
            Toast.makeText(this, "请先搜索并进入小说页面", Toast.LENGTH_SHORT).show()
            return
        }
        val title = webView.title?.takeIf { it.isNotBlank() } ?: currentQuery ?: ""
        val intent = Intent(this, ReadingActivity::class.java).apply {
            putExtra("EXTRA_BOOK_URL", url)
            putExtra("EXTRA_BOOK_TITLE", title)
        }
        startActivity(intent)
    }

    private fun copyCurrentUrl() {
        val url = webView.url
        if (url.isNullOrBlank() || webView.visibility != View.VISIBLE) {
            Toast.makeText(this, "当前没有链接", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("novel_url", url))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    // ---------- 文件下载 ----------

    /** 处理网页里的文件下载链接（小说站常见的「下载 TXT / EPUB」） */
    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        // blob/data 由页面脚本在内存里生成，系统下载器无法直接取回
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "该链接由网页脚本生成，暂不支持下载", Toast.LENGTH_LONG).show()
            return
        }
        // 安装包/可执行文件常被恶意页面用脚本自动触发下载，这类必须先让用户确认
        riskyExtension(url)?.let { ext ->
            AlertDialog.Builder(this)
                .setTitle("可疑文件")
                .setMessage("这个链接是安装包或可执行文件（.$ext），可能是恶意程序。\n\n$url")
                .setPositiveButton("仍要下载") { _, _ -> startDownload(url, contentDisposition, mimeType) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startDownload(url, contentDisposition, mimeType)
    }

    /** 高危扩展名（安装包/脚本），命中则返回扩展名 */
    private fun riskyExtension(url: String): String? {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() && it in RISKY_FILE_EXTENSIONS }
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val doEnqueue = {
            try {
                enqueueDownload(url, contentDisposition, mimeType)
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败: ${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
        // Android 9 及以下写公共下载目录需要存储权限，先申请再下载
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = doEnqueue
            requestWritePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doEnqueue()
        }
    }

    private fun enqueueDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("小说文件下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NovelReader/$fileName")
            setAllowedOverMetered(true)
            mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            // 透传网页 Cookie 与 UA，部分站点需要会话/浏览器标识才能取到文件
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", webView.settings.userAgentString)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        DownloadRepo.add(this, DownloadRepo.Record(id, fileName, url, System.currentTimeMillis()))
        Toast.makeText(this, "已开始下载「$fileName」，可在菜单「文件下载管理」查看", Toast.LENGTH_LONG).show()
    }

    /** 优先取 Content-Disposition 里的文件名，其次 URL 路径，最后按类型猜扩展名 */
    private fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let { cd ->
            Regex("filename\\*?\\s*=\\s*(?:UTF-8''|\")?([^\";]+)").find(cd)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    val name = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    if (name.contains('.')) return name
                }
        }
        val lastSegment = Uri.parse(url).lastPathSegment.orEmpty()
        if (lastSegment.contains('.')) return lastSegment.substringAfterLast('/')
        val ext = when (mimeType) {
            "application/epub+zip", "application/x-epub" -> "epub"
            "application/x-umd" -> "umd"
            else -> "txt"
        }
        return "novel_${System.currentTimeMillis()}.$ext"
    }

    /**
     * 系统返回（手势/返回键）统一走 handleBack。
     * 注意：targetSdk 35+ 在 Android 15 起默认启用预测性返回（predictive back），
     * 旧的 Activity.onBackPressed() 覆写会被系统绕过（表现为按返回直接退出到 App 首页），
     * 必须用 OnBackPressedDispatcher 的 OnBackPressedCallback 才能在两种模式下都生效。
     */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    /** 统一返回逻辑：网页后退 → 回搜索首页 → 退出本页 */
    private fun handleBack() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                hideKeyboard()
                webView.goBack()
            } else {
                showHome()
            }
        } else {
            hideKeyboard()
            finish()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etQuery.windowToken, 0)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        blockedBar.removeCallbacks(hideBlockedBar)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val PREFS_NAME = "search_prefs"
        const val KEY_HISTORY = "history"
        const val HISTORY_LIMIT = 10
        const val BING_SEARCH_URL = "https://cn.bing.com/search?q="

        /** 触摸后多久内的跳转算「用户点出来的」 */
        const val TAP_WINDOW_MS = 1200L

        /** 拦截提示条停留时长 */
        const val BLOCKED_BAR_TTL_MS = 8000L

        /** 高危文件扩展名：下载前必须二次确认 */
        val RISKY_FILE_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "scr", "vbs", "jar", "dmg", "sh"
        )
    }
}
