package com.example.novelreader

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
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var currentQuery: String? = null

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

        btnBack.setOnClickListener { handleBack() }
        btnMenu.setOnClickListener { showMenu() }
        btnClearHistory.setOnClickListener {
            writeHistory(emptyList())
            Toast.makeText(this, "已清空搜索记录", Toast.LENGTH_SHORT).show()
        }
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
        }

        // 设置 WebViewClient 后，点击搜索结果链接会在本 WebView 内继续加载（而非外跳浏览器）
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
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
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_home -> { showHome(); true }
                R.id.menu_open_in_reader -> { openCurrentInReader(); true }
                R.id.menu_download_manager -> {
                    startActivity(Intent(this, DownloadsActivity::class.java)); true
                }
                R.id.menu_refresh -> { if (webView.visibility == View.VISIBLE) webView.reload() else showHome(); true }
                R.id.menu_copy_url -> { copyCurrentUrl(); true }
                else -> false
            }
        }
        popup.show()
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

    /** 统一返回逻辑：网页后退 → 回搜索首页 → 退出本页 */
    private fun handleBack() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                showHome()
            }
        } else {
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

    override fun onBackPressed() {
        // 优先在 WebView 历史里后退，退无可退先回搜索首页，再退才退出本页
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack() else showHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val PREFS_NAME = "search_prefs"
        const val KEY_HISTORY = "history"
        const val HISTORY_LIMIT = 10
        const val BING_SEARCH_URL = "https://cn.bing.com/search?q="
    }
}
