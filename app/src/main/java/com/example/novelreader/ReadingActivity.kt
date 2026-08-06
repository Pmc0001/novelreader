package com.example.novelreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.example.novelreader.HistoryItem
import com.example.novelreader.HistoryStore
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ReadingActivity : AppCompatActivity() {

    private lateinit var readingView: ReadingView
    private lateinit var settings: ReadingSettings
    private lateinit var historyStore: HistoryStore
    private lateinit var topBarContainer: View
    private lateinit var topBar: View
    private lateinit var statusBarSpacer: View
    private lateinit var bottomBar: View
    private lateinit var topBarGradient: View
    private lateinit var bottomBarGradient: View
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var readingContainer: View
    private lateinit var navBarSpacer: View
    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private var tvPageInfo: TextView? = null  // 已合并到 tvProgress 单行显示；保留引用防崩溃
    private lateinit var btnBack: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnNightMode: ImageButton
    private var btnLoadUrl: ImageButton? = null
    private lateinit var btnShelf: ImageButton
    private lateinit var btnPrevChapter: TextView
    private lateinit var btnNextChapter: TextView
    private lateinit var premiumLoadingView: PremiumLoadingView
    private lateinit var tocDrawer: View
    private lateinit var tocOverlay: View
    private lateinit var tocList: ViewGroup
    private lateinit var tocScrollView: android.widget.ScrollView
    private lateinit var tocLoading: PremiumLoadingView
    private lateinit var tocEmpty: View
    private lateinit var btnToc: View
    private lateinit var tocPagerBar: View
    private lateinit var tocBtnPrevPage: TextView
    private lateinit var tocBtnNextPage: TextView
    private lateinit var tocPageInfo: TextView
    private lateinit var tocPageSizeButtons: List<TextView>
    private lateinit var tocChangeSource: TextView

    private var isBarsVisible = false
    /** 当前章节标题（顶栏显示、HistoryItem 不存这个） */
    private var currentChapterTitle: String = "示例小说"
    /** 当前书名（换源搜索 / 书架历史项 / 顶部横幅都看这个） */
    private var currentBookTitle: String = ""
    private var currentUrl: String? = null
    private var nextChapterUrl: String? = null
    private val prevChapters = ArrayDeque<String>()
    private val chapterCache = LinkedHashMap<String, CachedChapter>()
    private var chapterToc: List<UrlLoader.ChapterItem> = emptyList()
    private var tocCurrentPage = 0
    private var tocPageSize = 100
    private val tocPageSizeKey = "toc_page_size"
    private var tocTargetUrl: String? = null
    private lateinit var gestureOverlay: GestureCameraOverlay
    private lateinit var gestureIndicator: TextView
    private var gesturePermissionRequested = false
    private var sessionStartMs: Long = 0L   // 阅读计时起点（onResume 设置，onPause 结算）

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            gestureOverlay.setPermissionGranted(true)
            gestureOverlay.start(this)
        } else {
            Toast.makeText(this, "需要摄像头权限才能使用手势翻页", Toast.LENGTH_SHORT).show()
        }
    }

    private data class CachedChapter(
        val title: String,
        val content: String,
        val nextChapterUrl: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading)

        settings = ReadingSettings(this)
        historyStore = HistoryStore(this)
        ReadingStats.init(this)
        currentUrl = intent.getStringExtra("EXTRA_URL")
        nextChapterUrl = intent.getStringExtra("EXTRA_NEXT_CHAPTER")

        initViews()
        setupImmersiveMode()
        // 先绑定回调，再加载内容、再应用设置：确保 applySettings() 启动手势摄像头时
        // onSwipeLeft/onSwipeRight 等回调已就绪，避免早期帧事件丢失；同时 loadContent()
        // 触发的首次 onPageChanged 也能被 setupListeners() 捕获。
        setupListeners()
        loadContent()
        applySettings()
        updateShelfButton()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        // 开始/恢复阅读计时
        sessionStartMs = System.currentTimeMillis()
    }

    private fun initViews() {
        readingView = findViewById(R.id.reading_view)
        readingContainer = findViewById(R.id.reading_container)
        topBarContainer = findViewById(R.id.top_bar_container)
        topBar = findViewById(R.id.top_bar)
        statusBarSpacer = findViewById(R.id.status_bar_spacer)
        bottomBar = findViewById(R.id.bottom_bar)
        topBarGradient = findViewById(R.id.top_bar_gradient)
        bottomBarGradient = findViewById(R.id.bottom_bar_gradient)
        navBarSpacer = findViewById(R.id.nav_bar_spacer)
        progressTrack = findViewById(R.id.progress_track)
        progressFill = findViewById(R.id.progress_fill)

        // 阅读页栏作为浮层直接盖在正文上方，不调整 ReadingView padding。
        // 这样栏显隐不会改变分页/滚动布局，避免"漏一截内容"的问题。
        tvTitle = findViewById(R.id.tv_title)
        tvProgress = findViewById(R.id.tv_progress)
        // tv_page_info 已合并到 tvProgress 单行显示，不再有独立 View
        // tvPageInfo 保持 null（声明时已默认为 null）
        btnBack = findViewById(R.id.btn_back)
        btnSettings = findViewById(R.id.btn_settings)
        btnNightMode = findViewById(R.id.btn_night_mode)
        btnLoadUrl = findViewById(R.id.btn_load_url)
        btnShelf = findViewById(R.id.btn_shelf)
        btnPrevChapter = findViewById(R.id.btn_prev_chapter)
        btnNextChapter = findViewById(R.id.btn_next_chapter)
        gestureIndicator = findViewById(R.id.gesture_indicator)
        premiumLoadingView = findViewById(R.id.premium_loading_view)
        tocDrawer = findViewById(R.id.toc_drawer)
        tocOverlay = findViewById(R.id.toc_overlay)
        tocList = findViewById(R.id.toc_list)
        tocScrollView = findViewById(R.id.toc_scroll_view)
        tocLoading = findViewById(R.id.toc_loading)
        tocEmpty = findViewById(R.id.toc_empty)
        btnToc = findViewById(R.id.btn_toc)
        tocPagerBar = findViewById(R.id.toc_pager_bar)
        tocBtnPrevPage = findViewById(R.id.toc_btn_prev_page)
        tocBtnNextPage = findViewById(R.id.toc_btn_next_page)
        tocPageInfo = findViewById(R.id.toc_page_info)
        tocPageSizeButtons = listOf(
            findViewById(R.id.toc_page_size_50),
            findViewById(R.id.toc_page_size_100),
            findViewById(R.id.toc_page_size_200),
            findViewById(R.id.toc_page_size_500),
            findViewById(R.id.toc_page_size_all)
        )
        tocChangeSource = findViewById(R.id.toc_change_source)
        // 恢复上次选择的每页条数
        tocPageSize = getPreferences(MODE_PRIVATE).getInt(tocPageSizeKey, 100)
        // 初始化手势识别
        gestureOverlay = GestureCameraOverlay(this)
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        hideSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(topBarContainer) { v, insets ->
            // 拿 nav bar 物理尺寸——必须用 ignoringVisibility 才能在 hideSystemBars 后仍返回物理像素
            val navBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val sysGestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val displayCutout = insets.displayCutout
            // fallback: 24dp (沉浸式下系统栏已隐藏，留出安全区即可，无需完整 48dp 导航栏高度)
            val navBarFallback = (24f * resources.displayMetrics.density).toInt()
            val topInset = maxOf(systemBars.top, displayCutout?.safeInsetTop ?: 0)
            val bottomInset = maxOf(
                navBars.bottom,
                systemBars.bottom,
                sysGestures.bottom,
                displayCutout?.safeInsetBottom ?: 0,
                navBarFallback
            )
            statusBarSpacer.layoutParams.height = topInset
            statusBarSpacer.requestLayout()
            navBarSpacer.layoutParams.height = bottomInset
            navBarSpacer.requestLayout()
            insets
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun loadContent() {
        val title = intent.getStringExtra("EXTRA_TITLE")
        val content = intent.getStringExtra("EXTRA_CONTENT")
        val url = intent.getStringExtra("EXTRA_URL")

        // 先应用滚动模式，确保后续 setContent/续读定位走正确的布局分支
        readingView.scrollMode = settings.scrollMode

        // 从搜索结果打开：先加载目录、再取第一章进入阅读
        val bookUrl = intent.getStringExtra("EXTRA_BOOK_URL")
        val bookTitle = intent.getStringExtra("EXTRA_BOOK_TITLE")
        if (bookUrl != null) {
            currentUrl = bookUrl
            openBook(bookUrl, bookTitle)
        } else if (title != null && content != null) {
            currentChapterTitle = title
            currentBookTitle = intent.getStringExtra("EXTRA_BOOK_TITLE_FALLBACK") ?: currentBookTitle
            val cacheUrl = currentUrl ?: "initial"
            val cached = CachedChapter(title, content, nextChapterUrl)
            chapterCache[cacheUrl] = cached
            applyChapter(cached, cacheUrl)
            restorePosition()
            prefetchChapters(cacheUrl, nextChapterUrl, null)
            prefetchToc()
            tvTitle.text = currentChapterTitle
        } else if (url != null) {
            // 历史/书架续读：查 HistoryItem 补齐书名（之前 addOrUpdate 写的就是 currentBookTitle）
            val itemTitle = historyStore.findByUrl(url)?.title
            if (!itemTitle.isNullOrBlank() && currentBookTitle.isBlank()) currentBookTitle = itemTitle
            currentUrl = url
            loadChapter(url, isForward = true)
        } else {
            currentChapterTitle = "未加载内容"
            tvTitle.text = currentChapterTitle
            showUrlInputDialog()
        }
    }

    /**
     * 从搜索结果打开一本书：先拉取目录，再加载第一章进入阅读。
     * bookUrl 为书籍目录/主页链接；bookTitle 为搜索返回的书名（加载期间作为标题展示）。
     */
    private fun openBook(bookUrl: String, bookTitle: String?) {
        // 关键：把传入的书名固定到 currentBookTitle，后续章节切换不会覆盖它
        currentBookTitle = bookTitle?.takeIf { it.isNotBlank() } ?: ""
        currentChapterTitle = bookTitle ?: "加载中…"
        tvTitle.text = currentChapterTitle
        showLoading(true)
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val toc = loader.loadChapterList(bookUrl)) {
                is UrlLoader.ChapterListResult.Success -> {
                    val first = toc.items.firstOrNull()
                    if (first == null) {
                        showLoading(false)
                        Toast.makeText(
                            this@ReadingActivity,
                            "未在该站点找到章节，可能需登录或来源已失效",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    when (val chap = loader.loadUrl(first.url)) {
                        is UrlLoader.LoadResult.Success -> {
                            showLoading(false)
                            prevChapters.clear()
                            currentUrl = first.url
                            nextChapterUrl = chap.nextChapterUrl
                            val cached = CachedChapter(chap.title, chap.content, chap.nextChapterUrl)
                            chapterCache[first.url] = cached
                            // 复用已拉取的目录，避免重复请求
                            if (chapterToc.isEmpty()) chapterToc = toc.items
                            applyChapter(cached, first.url)
                            prefetchChapters(first.url, chap.nextChapterUrl, null)
                            updateNextFromToc(first.url)
                            updatePrevFromToc(first.url)
                            updateNavButtons()
                        }
                        is UrlLoader.LoadResult.Error -> {
                            showLoading(false)
                            Toast.makeText(this@ReadingActivity, chap.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                is UrlLoader.ChapterListResult.Error -> {
                    showLoading(false)
                    Toast.makeText(this@ReadingActivity, toc.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restorePosition() {
        val startPos = intent.getIntExtra("EXTRA_POSITION", 0)
        if (startPos <= 0) return
        // 保存进度时的阅读模式（与当前设置可能不同，跨会话切换过翻页/滚动时避免定位偏差）
        val storedScroll = intent.getBooleanExtra("EXTRA_SCROLL_MODE", settings.scrollMode)
        val storedTotal = intent.getIntExtra("EXTRA_TOTAL_PAGES", 0)
        if (storedScroll == settings.scrollMode) {
            // 模式一致：精确还原
            if (settings.scrollMode) readingView.setInitialScrollPercent(startPos / 100f)
            else readingView.setInitialPage(startPos)
        } else if (settings.scrollMode) {
            // 当时是翻页（存的是页码）→ 折算成滚动百分比
            val pct = if (storedTotal > 0) (startPos * 100f / storedTotal) else 0f
            readingView.setInitialScrollPercent(pct / 100f)
        } else {
            // 当时是滚动（存的是百分比）→ 折算成页码
            val page = ((startPos / 100f) * readingView.pageCount)
                .toInt().coerceIn(0, maxOf(0, readingView.pageCount - 1))
            readingView.setInitialPage(page)
        }
    }

    private fun showLoading(show: Boolean) {
        premiumLoadingView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun applySettings() {
        val theme = BackgroundTheme.fromId(settings.backgroundTheme)

        readingView.fontSize = settings.fontSize.toFloat()
        readingView.textColor = theme.textColor
        readingView.bgColor = theme.backgroundColor
        readingView.lineSpacing = settings.lineSpacing
        readingView.fontFamily = settings.fontFamily
        readingView.scrollMode = settings.scrollMode

        applyBrightness(settings.brightness)
        updateStatusBarColor(theme)
        applyBarTheme(theme)
        if (settings.gestureEnabled && !gestureOverlay.isActive()) {
            startGestureMode()
        } else if (!settings.gestureEnabled && gestureOverlay.isActive()) {
            gestureOverlay.stop()
        }
        // 同步灵敏度设置（手势已运行或即将启动均生效）
        gestureOverlay.setSensitivity(settings.gestureSensitivity)

        // 滚动模式下进度仅显示百分比（页码无意义），翻页模式显示"进度% · 页码"
        // 格式切换由 onPageChanged / onScrollProgress 各自控制
    }

    private fun applyBrightness(value: Int) {
        val layoutParams = window.attributes
        if (value == 50) {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            layoutParams.screenBrightness = value / 100f
        }
        window.attributes = layoutParams
    }

    private fun updateStatusBarColor(theme: BackgroundTheme) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 栏显示时状态栏区域是 90% 深棕栏 → 必须用浅色图标；
        // 栏隐藏时状态栏区域透出页面背景 → 浅色主题用深色图标，夜间用浅色图标
        controller.isAppearanceLightStatusBars =
            !isBarsVisible && theme.id != ReadingSettings.THEME_DARK
    }

    /** 底栏进度条：track 测量完成后按百分比设置 fill 宽度 */
    private fun updateProgressBar(percent: Int) {
        val p = percent.coerceIn(0, 100)
        if (progressTrack.width <= 0) {
            progressTrack.post { applyProgressWidth(p) }
        } else {
            applyProgressWidth(p)
        }
    }

    private fun applyProgressWidth(percent: Int) {
        val w = (progressTrack.width * percent / 100f).toInt()
        progressFill.layoutParams.width = w
        progressFill.requestLayout()
    }

    /**
     * 栏样式：
     * - 顶/底栏背景由 XML 中 @color/topBarOverlay / @color/bottomBarOverlay 提供（80% 深棕），
     *   配合外部 120dp 渐变过渡，避免"完全不透明"的实心板观感。
     * - 状态栏 spacer 与栏同色，避免栏显示时顶部露出页面背景色。
     * - 前景：标题/图标暖白，进度/页码 72% 白（弱化）。
     * - 关键按钮（目录/上一章/下一章）：白底深棕字胶囊 bg_bar_pill。
     * - 次要按钮（返回/URL/夜间/书架/设置）：borderless 白图标。
     */
    private fun applyBarTheme(theme: BackgroundTheme) {
        readingContainer.setBackgroundColor(theme.backgroundColor)

        val fg = android.graphics.Color.WHITE
        val fgSoft = android.graphics.Color.argb(0xAA, 0xFF, 0xFF, 0xFF)
        tvTitle.setTextColor(fg)
        tvProgress.setTextColor(fgSoft)
        tvPageInfo?.setTextColor(fgSoft)
        btnPrevChapter.setTextColor(fg)
        btnNextChapter.setTextColor(fg)
        (btnToc as? TextView)?.setTextColor(fgSoft)
        listOf(btnBack, btnSettings, btnNightMode, btnShelf).forEach {
            it.setColorFilter(fgSoft)
        }
        btnLoadUrl?.setColorFilter(fgSoft)
    }

    private fun setupListeners() {
        readingView.onPageChanged = { current, total ->
            val progress = if (total > 0) ((current + 1) * 100 / total) else 0
            tvProgress.text = "$progress% · ${current + 1} / $total"
            updateProgressBar(progress)

            // 接近章节末尾时预取下一章用于翻章过渡
            if (total > 0 && current >= total - 2 && nextChapterUrl != null) {
                prefetchTransitionContent(nextChapterUrl!!, isNext = true)
            }
            // 在第一页时预取上一章用于翻章过渡
            if (current <= 1) {
                prevChapters.lastOrNull()?.let { prefetchTransitionContent(it, isNext = false) }
            }
        }

        readingView.onTapCenter = {
            toggleBars()
        }

        // 滚动模式进度：仅百分比（无页码概念）
        readingView.onScrollProgress = { percent ->
            val p = percent.toInt()
            tvProgress.text = "$p%"
            updateProgressBar(p)
            if (percent >= 80 && nextChapterUrl != null) {
                prefetchTransitionContent(nextChapterUrl!!, isNext = true)
            }
        }

        readingView.onNextChapter = {
            loadNextChapter()
        }

        readingView.onPrevChapter = {
            loadPrevChapter()
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnNightMode.setOnClickListener {
            toggleNightMode()
        }

        btnLoadUrl?.setOnClickListener {
            showUrlInputDialog()
        }

        btnShelf.setOnClickListener {
            toggleShelf()
        }

        btnPrevChapter.setOnClickListener {
            loadPrevChapter()
        }

        btnNextChapter.setOnClickListener {
            loadNextChapter()
        }

        gestureOverlay.onSwipeLeft = {
            runOnUiThread {
                readingView.commitCurrentPage()
                if (readingView.currentPage < readingView.pageCount - 1) {
                    readingView.setPage(readingView.currentPage + 1)
                } else {
                    loadNextChapter()
                }
            }
        }

        gestureOverlay.onSwipeRight = {
            runOnUiThread {
                readingView.commitCurrentPage()
                if (readingView.currentPage > 0) {
                    readingView.setPage(readingView.currentPage - 1)
                } else {
                    loadPrevChapter()
                }
            }
        }

        gestureOverlay.onGestureStateChanged = { active ->
            runOnUiThread {
                gestureIndicator.visibility = if (active) View.VISIBLE else View.GONE
            }
        }

        gestureOverlay.onError = { message ->
            runOnUiThread {
                settings.gestureEnabled = false
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        btnToc.setOnClickListener {
            openToc()
        }

        tocOverlay.setOnClickListener {
            closeToc()
        }

        findViewById<View>(R.id.toc_close).setOnClickListener {
            closeToc()
        }

        tocChangeSource.setOnClickListener {
            openSourcePicker()
        }

        tocBtnPrevPage.setOnClickListener {
            if (tocCurrentPage > 0) {
                tocCurrentPage--
                renderTocPage()
            }
        }

        tocBtnNextPage.setOnClickListener {
            val totalPages = tocTotalPages()
            if (tocCurrentPage < totalPages - 1) {
                tocCurrentPage++
                renderTocPage()
            }
        }

        val pageSizeValues = intArrayOf(50, 100, 200, 500, Int.MAX_VALUE)
        tocPageSizeButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                tocPageSize = pageSizeValues[idx]
                tocCurrentPage = 0
                getPreferences(MODE_PRIVATE).edit().putInt(tocPageSizeKey, tocPageSize).apply()
                renderTocPage()
                updatePageSizeButtons()
            }
        }
    }

    private fun openToc() {
        tocOverlay.visibility = View.VISIBLE
        tocOverlay.alpha = 0f
        tocOverlay.animate().alpha(1f).setDuration(200).start()
        tocDrawer.animate().translationX(0f).setDuration(250).start()
        if (chapterToc.isNotEmpty()) {
            renderToc()
        } else {
            loadToc()
        }
    }

    private fun closeToc() {
        tocDrawer.animate().translationX(-tocDrawer.width.toFloat()).setDuration(250).start()
        tocOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            tocOverlay.visibility = View.GONE
        }.start()
    }

    // ===== 多书源换源 =====
    private fun openSourcePicker() {
        val sources = SourceManager.all(this)
        val names = sources.map { it.name }.toMutableList()
        names.add("＋ 添加自定义书源")
        AlertDialog.Builder(this)
            .setTitle("换源（换成其它站点阅读本书）")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == sources.size) {
                    showAddSourceDialog()
                } else {
                    runSourceSearch(sources[which])
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSourceSearch(source: BookSource) {
        // 关键：用 currentBookTitle 当搜索词，不是章节标题
        val keyword = currentBookTitle.takeIf { it.isNotBlank() }
            ?: currentChapterTitle.takeIf { it.isNotBlank() }
            ?: "未知书名"
        val progress = AlertDialog.Builder(this)
            .setMessage("正在「${source.name}」搜索：$keyword")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val result = UrlLoader(this@ReadingActivity).searchBooks(source, keyword)
            progress.dismiss()
            when (result) {
                is UrlLoader.SearchResult.Success -> showSearchResults(result.items, source)
                is UrlLoader.SearchResult.Error -> Toast.makeText(
                    this@ReadingActivity, result.message, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSearchResults(items: List<UrlLoader.SearchItem>, source: BookSource) {
        if (items.isEmpty()) {
            Toast.makeText(this, "未找到可切换的书源结果", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = items.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择「${currentBookTitle.ifBlank { currentChapterTitle }}」的源（${source.name}）")
            .setItems(titles) { _, which ->
                val chosen = items[which]
                // 用新源的书 URL 重新打开本书，复用现有加载逻辑
                val intent = Intent(this@ReadingActivity, ReadingActivity::class.java).apply {
                    putExtra("EXTRA_URL", chosen.url)
                    putExtra("EXTRA_NEXT_CHAPTER", "")
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showAddSourceDialog() {
        val ctx = this
        val nameEdit = EditText(ctx).apply {
            hint = "书源名称（如：我的书源）"
            setText("自定义书源")
        }
        val urlEdit = EditText(ctx).apply {
            hint = "搜索地址，含 {q} 占位符"
            setText("https://example.com/s.php?q={q}")
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(nameEdit)
            addView(urlEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle("添加自定义书源")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val url = urlEdit.text.toString().trim()
                if (name.isBlank() || !url.contains("{q}")) {
                    Toast.makeText(ctx, "名称不能为空且地址需包含 {q}", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SourceManager.addCustom(
                    ctx,
                    BookSource(
                        id = "custom_${System.currentTimeMillis()}",
                        name = name,
                        searchUrls = listOf(url),
                        encoding = "utf-8"
                    )
                )
                Toast.makeText(ctx, "已保存，重新选择书源", Toast.LENGTH_SHORT).show()
                openSourcePicker()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadToc() {
        val baseUrl = currentUrl ?: run {
            Toast.makeText(this, "请先加载章节", Toast.LENGTH_SHORT).show()
            closeToc()
            return
        }
        if (chapterToc.isNotEmpty()) {
            renderToc()
            return
        }
        tocLoading.visibility = View.VISIBLE
        tocEmpty.visibility = View.GONE
        tocList.removeAllViews()
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadChapterList(baseUrl)) {
                is UrlLoader.ChapterListResult.Success -> {
                    tocLoading.visibility = View.GONE
                    chapterToc = result.items
                    currentUrl?.let { url ->
                        updateNextFromToc(url)
                        updatePrevFromToc(url)
                        updateNavButtons()
                    }
                    if (result.items.isEmpty()) {
                        tocEmpty.visibility = View.VISIBLE
                    } else {
                        renderToc()
                    }
                }
                is UrlLoader.ChapterListResult.Error -> {
                    tocLoading.visibility = View.GONE
                    tocEmpty.visibility = View.VISIBLE
                    Toast.makeText(this@ReadingActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderToc() {
        val targetUrl = tocTargetUrl ?: currentUrl ?: ""
        var currentIdx = -1
        for ((idx, item) in chapterToc.withIndex()) {
            if (normUrl(item.url) == normUrl(targetUrl)) { currentIdx = idx; break }
        }
        // 定位到当前章节所在页
        if (currentIdx >= 0 && tocPageSize < chapterToc.size) {
            tocCurrentPage = currentIdx / tocPageSize
        } else {
            tocCurrentPage = 0
        }
        renderTocPage()
    }

    private fun tocTotalPages(): Int {
        if (chapterToc.isEmpty()) return 0
        val size = if (tocPageSize >= chapterToc.size) 1 else (chapterToc.size + tocPageSize - 1) / tocPageSize
        return size
    }

    private fun renderTocPage() {
        tocList.removeAllViews()
        tocLoading.visibility = View.GONE
        tocEmpty.visibility = View.GONE
        val density = resources.displayMetrics.density
        val highlightUrl = tocTargetUrl ?: currentUrl
        val totalPages = tocTotalPages()

        // 限制页码范围
        if (tocCurrentPage < 0) tocCurrentPage = 0
        if (tocCurrentPage >= totalPages && totalPages > 0) tocCurrentPage = totalPages - 1

        val start = if (tocPageSize >= chapterToc.size) 0 else tocCurrentPage * tocPageSize
        val end = minOf(start + tocPageSize, chapterToc.size)

        for (idx in start until end) {
            val item = chapterToc[idx]
            val tv = TextView(this).apply {
                text = item.title
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ReadingActivity, R.color.textPrimary))
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                if (normUrl(item.url) == normUrl(highlightUrl ?: "")) {
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@ReadingActivity, R.color.colorPrimary))
                    background = ContextCompat.getDrawable(this@ReadingActivity, R.drawable.bg_toc_item_selected)
                } else {
                    background = ContextCompat.getDrawable(this@ReadingActivity, R.drawable.bg_toc_item)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }
            tv.setOnClickListener {
                tocTargetUrl = item.url
                closeToc()
                if (normUrl(item.url) != normUrl(currentUrl ?: "")) {
                    loadChapter(item.url, isForward = true)
                }
            }
            tocList.addView(tv)
        }

        // 更新分页控件
        tocPagerBar.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        tocPageInfo.text = "${tocCurrentPage + 1} / $totalPages"
        tocBtnPrevPage.alpha = if (tocCurrentPage > 0) 1f else 0.4f
        tocBtnNextPage.alpha = if (tocCurrentPage < totalPages - 1) 1f else 0.4f
        updatePageSizeButtons()

        // 滚动到顶部
        tocScrollView.scrollTo(0, 0)
    }

    private fun updatePageSizeButtons() {
        val values = intArrayOf(50, 100, 200, 500, Int.MAX_VALUE)
        tocPageSizeButtons.forEachIndexed { idx, btn ->
            val selected = tocPageSize == values[idx]
            btn.alpha = if (selected) 1f else 0.5f
            btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun toggleBars() {
        isBarsVisible = !isBarsVisible
        // 栏显隐会影响状态栏区域的底色（深棕栏 vs 页面背景），图标颜色要跟着切
        updateStatusBarColor(BackgroundTheme.fromId(settings.backgroundTheme))
        if (isBarsVisible) {
            topBarContainer.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            topBarGradient.visibility = View.VISIBLE
            bottomBarGradient.visibility = View.VISIBLE
            topBarContainer.alpha = 0f
            bottomBar.alpha = 0f
            topBarGradient.alpha = 0f
            bottomBarGradient.alpha = 0f
            topBarContainer.animate().alpha(1f).setDuration(200).start()
            bottomBar.animate().alpha(1f).setDuration(200).start()
            topBarGradient.animate().alpha(1f).setDuration(200).start()
            bottomBarGradient.animate().alpha(1f).setDuration(200).start()
        } else {
            // 栏隐藏：强制隐藏 system bars 让 status bar / nav bar 也消失
            hideSystemBars()
            topBarContainer.animate().alpha(0f).setDuration(200).withEndAction {
                topBarContainer.visibility = View.GONE
                topBarGradient.visibility = View.GONE
            }.start()
            bottomBar.animate().alpha(0f).setDuration(200).withEndAction {
                bottomBar.visibility = View.GONE
                bottomBarGradient.visibility = View.GONE
            }.start()
        }
    }

    private fun loadNextChapter() {
        val url = nextChapterUrl ?: run {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        currentUrl?.let { prevChapters.addLast(it) }
        loadChapter(url, isForward = true)
    }

    private fun loadPrevChapter() {
        val url = prevChapters.removeLastOrNull() ?: run {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(url, isForward = false)
    }

    private fun loadChapter(url: String, isForward: Boolean) {
        chapterCache[url]?.let { cached ->
            applyChapter(cached, url, isForward)
            prefetchChapters(url, cached.nextChapterUrl, prevChapters.lastOrNull())
            ensureTocLoaded(url)
            return
        }
        showLoading(true)
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadUrl(url)) {
                is UrlLoader.LoadResult.Success -> {
                    val cached = CachedChapter(result.title, result.content, result.nextChapterUrl)
                    chapterCache[url] = cached
                    showLoading(false)
                    applyChapter(cached, url, isForward)
                    prefetchChapters(url, result.nextChapterUrl, prevChapters.lastOrNull())
                    ensureTocLoaded(url)
                }
                is UrlLoader.LoadResult.Error -> {
                    showLoading(false)
                    if (isForward) prevChapters.removeLastOrNull()
                    Toast.makeText(this@ReadingActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyChapter(cached: CachedChapter, url: String, isForward: Boolean = true) {
        currentChapterTitle = cached.title
        currentUrl = url
        nextChapterUrl = cached.nextChapterUrl
        readingView.setContent(cached.content)
        if (isForward) readingView.setPage(0) else readingView.goToLastPage()
        tvTitle.text = currentChapterTitle
        updateNextFromToc(url)
        updatePrevFromToc(url)
        updateNavButtons()
        tocTargetUrl = null
        // 统计：累计阅读字数与翻阅章节数
        ReadingStats.addChars(cached.content.length)
        ReadingStats.addChapter()
    }

    private fun updateNavButtons() {
        val hasPrev = prevChapters.isNotEmpty()
        val hasNext = !nextChapterUrl.isNullOrBlank()
        btnPrevChapter.isEnabled = hasPrev
        btnNextChapter.isEnabled = hasNext
        val dim = 0.4f
        btnPrevChapter.alpha = if (hasPrev) 1f else dim
        btnNextChapter.alpha = if (hasNext) 1f else dim
    }

    private fun updateNextFromToc(url: String) {
        if (chapterToc.isEmpty()) return
        val idx = chapterToc.indexOfFirst { normUrl(it.url) == normUrl(url) }
        if (idx >= 0 && idx < chapterToc.size - 1) {
            nextChapterUrl = chapterToc[idx + 1].url
        }
    }

    private fun updatePrevFromToc(url: String) {
        if (chapterToc.isEmpty()) return
        val idx = chapterToc.indexOfFirst { normUrl(it.url) == normUrl(url) }
        if (idx > 0) {
            val prevUrl = chapterToc[idx - 1].url
            if (prevChapters.none { normUrl(it) == normUrl(prevUrl) }) {
                prevChapters.addLast(prevUrl)
                updateNavButtons()
            }
        }
    }

    // 归一化 URL：去掉协议、query、fragment、尾斜杠，用于目录与章节 URL 的宽松匹配
    private fun normUrl(u: String): String = u
        .substringBefore('#')
        .substringBefore('?')
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    // 从历史记录进入时 prevChapters 为空，加载目录以补全上一章入口
    private fun ensureTocLoaded(url: String) {
        if (chapterToc.isNotEmpty()) {
            updatePrevFromToc(url)
            return
        }
        prefetchToc()
    }

    private fun prefetchToc() {
        if (chapterToc.isNotEmpty()) return
        val baseUrl = currentUrl ?: return
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadChapterList(baseUrl)) {
                is UrlLoader.ChapterListResult.Success -> {
                    if (chapterToc.isEmpty()) {
                        chapterToc = result.items
                    }
                    // 目录加载完成后回填上一章/下一章（加载时目录可能还没就绪）
                    currentUrl?.let { url ->
                        updateNextFromToc(url)
                        updatePrevFromToc(url)
                        updateNavButtons()
                    }
                }
                else -> { }
            }
        }
    }

    private fun prefetchChapters(current: String, nextUrl: String?, prevUrl: String?) {
        val targets = listOfNotNull(nextUrl, prevUrl).filter { it != current && !chapterCache.containsKey(it) }
        if (targets.isEmpty()) return
        val loader = UrlLoader(this)
        for (target in targets) {
            lifecycleScope.launch {
                when (val result = loader.loadUrl(target)) {
                    is UrlLoader.LoadResult.Success -> {
                        chapterCache[target] = CachedChapter(result.title, result.content, result.nextChapterUrl)
                        if (chapterCache.size > 40) {
                            chapterCache.keys.firstOrNull()?.let { chapterCache.remove(it) }
                        }
                    }
                    is UrlLoader.LoadResult.Error -> { }
                }
            }
        }
    }

    private fun prefetchTransitionContent(url: String, isNext: Boolean) {
        if (chapterCache.containsKey(url)) {
            val cached = chapterCache[url]
            if (cached != null) {
                if (isNext) readingView.setTransitionContent(cached.content)
                else readingView.setTransitionPrevContent(cached.content)
            }
            return
        }
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadUrl(url)) {
                is UrlLoader.LoadResult.Success -> {
                    chapterCache[url] = CachedChapter(result.title, result.content, result.nextChapterUrl)
                    if (chapterCache.size > 40) {
                        chapterCache.keys.firstOrNull()?.let { chapterCache.remove(it) }
                    }
                    if (isNext) readingView.setTransitionContent(result.content)
                    else readingView.setTransitionPrevContent(result.content)
                }
                is UrlLoader.LoadResult.Error -> { }
            }
        }
    }

    private fun showUrlInputDialog() {
        val dialog = UrlInputDialog.newInstance(presetUrl = currentUrl ?: "")
        dialog.setOnLoadListener(
            onSubmit = { url ->
                showLoading(true)
                val loader = UrlLoader(this)
                lifecycleScope.launch {
                    when (val result = loader.loadUrl(url)) {
                        is UrlLoader.LoadResult.Success -> {
                            showLoading(false)
                            prevChapters.clear()
                            val cached = CachedChapter(result.title, result.content, result.nextChapterUrl)
                            chapterCache[url] = cached
                            applyChapter(cached, url)
                            prefetchChapters(url, result.nextChapterUrl, null)
                        }
                        is UrlLoader.LoadResult.Error -> {
                            showLoading(false)
                            Toast.makeText(this@ReadingActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
        dialog.show(supportFragmentManager, UrlInputDialog.TAG)
    }

    private fun toggleNightMode() {
        val current = settings.backgroundTheme
        if (current == ReadingSettings.THEME_DARK) {
            // 从夜间切回浅色：恢复到进入夜间前记录的浅色主题，而不是固定白色
            settings.backgroundTheme = settings.lastLightTheme
        } else {
            // 进入夜间：先把当前浅色主题记下来，再切到深色
            settings.lastLightTheme = current
            settings.backgroundTheme = ReadingSettings.THEME_DARK
        }
        applySettings()
    }

    // 书架（收藏）开关：按 bookKey 去重，同一本小说只收藏一次
    private fun toggleShelf() {
        val url = currentUrl ?: run {
            Toast.makeText(this, "请先加载章节", Toast.LENGTH_SHORT).show()
            return
        }
        val bookKey = HistoryItem.keyOf(url)
        if (historyStore.isInShelf(bookKey)) {
            historyStore.removeFromShelf(bookKey)
            Toast.makeText(this, "已移出书架", Toast.LENGTH_SHORT).show()
        } else {
            historyStore.addToShelf(
                HistoryItem(
                    title = currentBookTitle.ifBlank { currentChapterTitle },
                    url = url,
                    bookKey = bookKey,
                    nextChapterUrl = nextChapterUrl,
                    position = 0,
                    totalPages = 0
                )
            )
            Toast.makeText(this, "已加入书架", Toast.LENGTH_SHORT).show()
        }
        updateShelfButton()
    }

    private fun updateShelfButton() {
        val inShelf = currentUrl?.let { historyStore.isInShelf(HistoryItem.keyOf(it)) } ?: false
        btnShelf.setImageResource(if (inShelf) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        btnShelf.contentDescription = if (inShelf) "移出书架" else "加入书架"
    }

    private fun startGestureMode() {
        if (gestureOverlay.isActive()) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            settings.gestureEnabled = false
            Toast.makeText(this, "设备没有摄像头，无法使用手势翻页", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            gestureOverlay.setPermissionGranted(true)
            gestureOverlay.start(this)
        } else {
            if (!gesturePermissionRequested) {
                gesturePermissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                settings.gestureEnabled = false
                Toast.makeText(this, "请在系统设置中授予摄像头权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        readingView.commitCurrentPage()
        settings.lastReadPosition = readingView.currentPage
        flushReadingTime()
        saveCurrentHistory()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentHistory()
    }

    override fun onDestroy() {
        gestureOverlay.destroy()
        super.onDestroy()
    }

    /** 结算本次前台阅读时长并写入统计 */
    private fun flushReadingTime() {
        if (sessionStartMs > 0) {
            val delta = System.currentTimeMillis() - sessionStartMs
            ReadingStats.addReadingTime(delta)
            sessionStartMs = 0
        }
    }

    private fun saveCurrentHistory() {
        val url = currentUrl ?: return
        // 滚动模式：position 存阅读百分比（0~100），totalPages 记为 0 以示百分比进度
        val position: Int
        val totalPages: Int
        if (settings.scrollMode) {
            position = (readingView.scrollPercent * 100).toInt()
            totalPages = 0
        } else {
            position = readingView.currentPage
            totalPages = readingView.pageCount
        }
        historyStore.addOrUpdate(
            HistoryItem(
                title = currentBookTitle.ifBlank { currentChapterTitle },
                url = url,
                bookKey = HistoryItem.keyOf(url),
                nextChapterUrl = nextChapterUrl,
                position = position,
                totalPages = totalPages,
                scrollMode = settings.scrollMode
            )
        )

        // 若已加入书架，同步阅读进度（保持书架顺序不变）
        val bookKey = HistoryItem.keyOf(url)
        if (historyStore.isInShelf(bookKey)) {
            historyStore.updateShelfProgress(bookKey, position, totalPages, nextChapterUrl, settings.scrollMode)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }
}
