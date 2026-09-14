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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class ReadingActivity : AppCompatActivity() {

    private lateinit var readingView: ReadingView
    private lateinit var settings: ReadingSettings
    private lateinit var historyStore: HistoryStore
    private lateinit var topBarContainer: View
    private lateinit var topBar: View
    private lateinit var statusBarSpacer: View
    private lateinit var bottomBar: View
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
    private lateinit var tocPagerNavRow: View
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

    // 目录加载 single-flight：多个入口（openBook / loadToc / prefetchToc / ensureTocLoaded）
    // 共享同一个加载任务，避免并发重复爬取整条目录。
    private var tocDeferred: Deferred<UrlLoader.ChapterListResult>? = null
    private var tocDeferredKey: String? = null
    private var tocCurrentPage = 0
    private var tocPageSize = 100
    private val tocPageSizeKey = "toc_page_size"
    private var tocTargetUrl: String? = null
    private lateinit var gestureOverlay: GestureCameraOverlay
    private lateinit var gestureIndicator: TextView
    private var gesturePermissionRequested = false
    private var sessionStartMs: Long = 0L   // 阅读计时起点（onResume 设置，onPause 结算）

    // ===== 本地书（txt）阅读状态 =====
    private var isLocalBook = false
    private var localFilePath: String? = null
    private var localBookTitle: String = ""
    private var localChapters: List<LocalChapter> = emptyList()
    private var localChapterIndex: Int = 0
    private var localWholeText: String = ""

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
        tocPagerNavRow = findViewById(R.id.toc_pager_nav_row)
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

        // 本地书：从缓存 txt 直接读取，按章节切分阅读（区别于在线书 URL 逐章加载）
        if (intent.hasExtra(EXTRA_LOCAL_FILE)) {
            openLocalBook()
            return
        }

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
            // 续读：依据 intent 携带的 EXTRA_POSITION / EXTRA_SCROLL_MODE / EXTRA_TOTAL_PAGES 还原上次位置
            loadChapter(url, isForward = true, restoreSavedPosition = true)
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
            when (val toc = getToc(bookUrl)) {
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
        // 栏显示时状态栏区域是深色栏（50% 透明叠加）→ 必须用浅色图标；
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
     * - 顶/底栏背景：由 theme.barBackgroundColor 动态设置（支持 customBarColor 覆盖），
     *   50% 半透明渲染，隐约透出正文；状态栏 spacer 与栏同色。
     * - 前景：标题/图标暖白，进度/页码 67% 白（弱化）。
     * - 关键按钮（目录/上一章/下一章）：半透明白胶囊 bg_bar_btn，融入深色栏背景。
     * - 次要按钮（返回/URL/夜间/书架/设置）：borderless 白图标。
     */
    private fun applyBarTheme(theme: BackgroundTheme) {
        readingContainer.setBackgroundColor(theme.backgroundColor)

        val rawBarColor = if (settings.customBarColor != -1) settings.customBarColor else theme.barBackgroundColor
        val barColor = android.graphics.Color.argb(0x80, android.graphics.Color.red(rawBarColor), android.graphics.Color.green(rawBarColor), android.graphics.Color.blue(rawBarColor))
        topBarContainer.setBackgroundColor(barColor)
        bottomBar.setBackgroundColor(barColor)

        val fg = android.graphics.Color.WHITE
        tvTitle.setTextColor(fg)
        tvProgress.setTextColor(android.graphics.Color.argb(0xAA, 0xFF, 0xFF, 0xFF))
        tvPageInfo?.setTextColor(android.graphics.Color.argb(0xAA, 0xFF, 0xFF, 0xFF))
        btnPrevChapter.setTextColor(fg)
        btnNextChapter.setTextColor(fg)
        (btnToc as? TextView)?.setTextColor(fg)
        listOf(btnBack, btnSettings, btnNightMode, btnShelf).forEach {
            it.setColorFilter(fg)
        }
        btnLoadUrl?.setColorFilter(fg)
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

        // 翻章动画开始前：若对应章节已在缓存中，同步填充过渡内容，避免动画期间白屏
        readingView.onPrepareTransition = { isNext ->
            if (isLocalBook) {
                val has = if (isNext) localChapterIndex < localChapters.size - 1 else localChapterIndex > 0
                if (has) {
                    // 用本地章正文填充过渡内容，消除动画期间白屏；检测用 localChapterIndex
                    val idx = if (isNext) localChapterIndex + 1 else localChapterIndex - 1
                    localChapters.getOrNull(idx)?.let { ch ->
                        val text = localWholeText.substring(ch.start, ch.end)
                        if (isNext) readingView.setTransitionContent(text) else readingView.setTransitionPrevContent(text)
                    }
                    true
                } else false
            } else {
                prepareTransitionFromCache(isNext)
            }
        }
        // 轻量边界查询：供 ReadingView 拖拽时实时判断是否存在可翻的上一/下一章（无副作用）。
        // 本地书用 localChapterIndex 判断（nextChapterUrl/prevChapters 是离线状态，不可用于本地边界）。
        readingView.onHasChapter = { isNext ->
            if (isLocalBook) {
                if (isNext) localChapterIndex < localChapters.size - 1 else localChapterIndex > 0
            } else {
                if (isNext) !nextChapterUrl.isNullOrBlank() else prevChapters.isNotEmpty()
            }
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
        if (isLocalBook) {
            openLocalToc()
            return
        }
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

    // 目录 single-flight 加载：已缓存直接返回；进行中的同一 baseUrl 任务共享其结果；
    // 否则启动一次加载并填充 chapterToc，后续的 loadToc/prefetchToc 调用复用同一结果。
    private suspend fun getToc(baseUrl: String): UrlLoader.ChapterListResult {
        if (chapterToc.isNotEmpty()) {
            return UrlLoader.ChapterListResult.Success(chapterToc)
        }
        val existing = tocDeferred
        if (existing != null && tocDeferredKey == baseUrl && existing.isActive) {
            return existing.await()
        }
        val loader = UrlLoader(this)
        val deferred = lifecycleScope.async { loader.loadChapterList(baseUrl) }
        tocDeferred = deferred
        tocDeferredKey = baseUrl
        val result = deferred.await()
        if (result is UrlLoader.ChapterListResult.Success && chapterToc.isEmpty()) {
            chapterToc = result.items
        }
        return result
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
        lifecycleScope.launch {
            when (val result = getToc(baseUrl)) {
                is UrlLoader.ChapterListResult.Success -> {
                    tocLoading.visibility = View.GONE
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
        tocChangeSource.visibility = View.VISIBLE
        val targetUrl = tocTargetUrl ?: currentUrl ?: ""
        val targetTitle = currentChapterTitle
        var currentIdx = -1
        for ((idx, item) in chapterToc.withIndex()) {
            // 优先按归一化 URL 匹配；失败再用章节标题兜底（目录项标题与内容页标题通常一致），
            // 避免"下一章"链接与目录链接格式不一致时定位不到当前章。
            val hit = normUrl(item.url) == normUrl(targetUrl) ||
                (targetTitle.isNotBlank() && item.title == targetTitle)
            if (hit) { currentIdx = idx; break }
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
        val total = if (isLocalBook) localChapters.size else chapterToc.size
        if (total == 0) return 0
        return if (tocPageSize >= total) 1 else (total + tocPageSize - 1) / tocPageSize
    }

    private fun renderTocPage() {
        tocList.removeAllViews()
        tocLoading.visibility = View.GONE
        tocEmpty.visibility = View.GONE
        val density = resources.displayMetrics.density
        val isLocal = isLocalBook
        val total = if (isLocal) localChapters.size else chapterToc.size
        val totalPages = tocTotalPages()

        // 限制页码范围
        if (tocCurrentPage < 0) tocCurrentPage = 0
        if (tocCurrentPage >= totalPages && totalPages > 0) tocCurrentPage = totalPages - 1

        val start = if (tocPageSize >= total) 0 else tocCurrentPage * tocPageSize
        val end = minOf(start + tocPageSize, total)

        // 记录当前章对应的列表项，渲染完成后将其滚动到可见（居中）位置
        var currentItemView: TextView? = null

        for (idx in start until end) {
            val title: CharSequence
            val selected: Boolean
            val onClick: () -> Unit
            if (isLocal) {
                val ch = localChapters[idx]
                title = ch.title.ifBlank { "第 ${idx + 1} 章" }
                selected = idx == localChapterIndex
                onClick = {
                    closeToc()
                    if (idx != localChapterIndex) applyLocalChapter(idx)
                }
            } else {
                val item = chapterToc[idx]
                val highlightUrl = tocTargetUrl ?: currentUrl
                title = item.title
                // 同 renderToc：URL 匹配失败时用当前章节标题兜底，保证当前章被高亮。
                selected = normUrl(item.url) == normUrl(highlightUrl ?: "") ||
                    (currentChapterTitle.isNotBlank() && item.title == currentChapterTitle)
                onClick = {
                    tocTargetUrl = item.url
                    closeToc()
                    if (normUrl(item.url) != normUrl(currentUrl ?: "")) {
                        loadChapter(item.url, isForward = true)
                    }
                }
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ReadingActivity, R.color.textPrimary))
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                if (selected) {
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
            tv.setOnClickListener { onClick() }
            tocList.addView(tv)
            if (selected) currentItemView = tv
        }

        // 更新分页控件：翻页导航仅在多页时显示；每页数目选择器始终可见，避免选「全部」后无法切回
        tocPagerBar.visibility = View.VISIBLE
        tocPagerNavRow.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        tocPageInfo.text = "${tocCurrentPage + 1} / $totalPages"
        tocBtnPrevPage.alpha = if (tocCurrentPage > 0) 1f else 0.4f
        tocBtnNextPage.alpha = if (tocCurrentPage < totalPages - 1) 1f else 0.4f
        updatePageSizeButtons()

        // 打开目录时自动滚动到当前章：优先把当前章项居中；无当前项（如空目录）则回到顶部。
        // 用 post 确保在布局测量完成、各 item 的 top 已计算之后再滚动。
        tocScrollView.post {
            val v = currentItemView
            if (v != null) {
                val target = (v.top + v.height / 2 - tocScrollView.height / 2).coerceAtLeast(0)
                tocScrollView.scrollTo(0, target)
            } else {
                tocScrollView.scrollTo(0, 0)
            }
        }
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
            topBarContainer.alpha = 0f
            bottomBar.alpha = 0f
            topBarContainer.animate().alpha(1f).setDuration(200).start()
            bottomBar.animate().alpha(1f).setDuration(200).start()
        } else {
            // 栏隐藏：强制隐藏 system bars 让 status bar / nav bar 也消失
            hideSystemBars()
            topBarContainer.animate().alpha(0f).setDuration(200).withEndAction {
                topBarContainer.visibility = View.GONE
            }.start()
            bottomBar.animate().alpha(0f).setDuration(200).withEndAction {
                bottomBar.visibility = View.GONE
            }.start()
        }
    }

    private fun loadNextChapter() {
        if (isLocalBook) {
            if (localChapterIndex < localChapters.size - 1) {
                applyLocalChapter(localChapterIndex + 1)
            } else {
                Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val url = nextChapterUrl ?: run {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        currentUrl?.let { prevChapters.addLast(it) }
        loadChapter(url, isForward = true)
    }

    private fun loadPrevChapter() {
        if (isLocalBook) {
            if (localChapterIndex > 0) {
                applyLocalChapter(localChapterIndex - 1, startAtEnd = true)
            } else {
                Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val url = prevChapters.removeLastOrNull() ?: run {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(url, isForward = false)
    }

    private fun loadChapter(url: String, isForward: Boolean, restoreSavedPosition: Boolean = false) {
        chapterCache[url]?.let { cached ->
            applyChapter(cached, url, isForward)
            if (restoreSavedPosition) restorePosition()
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
                    if (restoreSavedPosition) restorePosition()
                    prefetchChapters(url, result.nextChapterUrl, prevChapters.lastOrNull())
                    ensureTocLoaded(url)
                }
                is UrlLoader.LoadResult.Error -> {
                    showLoading(false)
                    if (isForward) prevChapters.removeLastOrNull()
                    // 若本次加载是由翻章动画触发的（chapterSwapPending 已置位），
                    // 加载失败需回退到当前章画面，避免停在空白/末页快照。
                    readingView.abortChapterSwap()
                    Toast.makeText(this@ReadingActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyChapter(cached: CachedChapter, url: String, isForward: Boolean = true) {
        currentChapterTitle = cached.title
        currentUrl = url
        nextChapterUrl = cached.nextChapterUrl
        // 下一章从第一页开始；上一章回到最后一页。
        // 用 onLayoutReady 在折行排版（relayout）完成后定位，确保 totalPages 已算好，定位精确；
        // 不依赖异步的 pendingStartAtEnd 竞态，避免翻回上一章时落点回到首行。
        readingView.onLayoutReady = if (isForward) null else ({ readingView.goToLastPage() })
        readingView.setContent(cached.content)
        if (isForward) readingView.setPage(0)
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

    // 归一化 URL：去掉协议、www、query、fragment、尾斜杠并转小写，
    // 用于目录与章节 URL 的宽松匹配（不少站点目录链接与内容页"下一章"链接在 www/大小写/尾部上有差异，
    // 不归一化会导致匹配失败 → 打开目录时定位不到当前章）。
    private fun normUrl(u: String): String = u
        .substringBefore('#')
        .substringBefore('?')
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .lowercase()
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
        lifecycleScope.launch {
            val result = getToc(baseUrl)
            if (result is UrlLoader.ChapterListResult.Success) {
                // 目录加载完成后回填上一章/下一章（加载时目录可能还没就绪）
                currentUrl?.let { url ->
                    updateNextFromToc(url)
                    updatePrevFromToc(url)
                    updateNavButtons()
                }
            }
        }
    }

    // 预取章节：只走轻量通道（OkHttp，快、不启 WebView）。
    // 不再回退完整 WebView 通道：后台起 WebView 会与前台翻章动画抢主线程，得不偿失；
    // 预取失败就放弃，等用户真正翻章时由 loadChannel 正常加载。
    private suspend fun fetchChapterForCache(loader: UrlLoader, target: String): CachedChapter? {
        return when (val r = loader.fetchChapterLight(target)) {
            is UrlLoader.LoadResult.Success -> CachedChapter(r.title, r.content, r.nextChapterUrl)
            else -> null
        }
    }

    private fun storeCachedChapter(url: String, cached: CachedChapter) {
        chapterCache[url] = cached
        if (chapterCache.size > 40) {
            chapterCache.keys.firstOrNull()?.let { chapterCache.remove(it) }
        }
    }

    private fun prefetchChapters(current: String, nextUrl: String?, prevUrl: String?) {
        val targets = listOfNotNull(nextUrl, prevUrl)
            .filter { it != current && !chapterCache.containsKey(it) }
            .distinct()
        if (targets.isEmpty()) return
        val loader = UrlLoader(this)
        for (target in targets) {
            lifecycleScope.launch {
                val cached = fetchChapterForCache(loader, target) ?: return@launch
                storeCachedChapter(target, cached)
            }
        }
    }

    /**
     * 翻章动画开始前同步调用：若目标章节已在 chapterCache 中，立即用缓存正文填充
     * ReadingView 的过渡内容，使翻章滑动动画直接显示真实章节内容，避免白屏一闪。
     * 返回 true 表示存在可翻的上一/下一章（应播放动画），false 表示已到边界（不应播放动画）。
     * 若尚未预取到位，立即触发补取，尽量在换章间隙填充过渡内容、缩短空白。
     */
    private fun prepareTransitionFromCache(isNext: Boolean): Boolean {
        val url = (if (isNext) nextChapterUrl else prevChapters.lastOrNull()) ?: return false
        val cached = chapterCache[url]
        if (cached != null) {
            if (isNext) readingView.setTransitionContent(cached.content)
            else readingView.setTransitionPrevContent(cached.content)
        } else {
            prefetchTransitionContent(url, isNext)
        }
        return true
    }

    private fun prefetchTransitionContent(url: String, isNext: Boolean) {
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            val cached = fetchChapterForCache(loader, url) ?: return@launch
            storeCachedChapter(url, cached)
            // 仅当翻章间隙仍需要过渡内容时填充（动画已开始，填充后由 onDraw 的 chapterSwapPending 分支展示）
            if (isNext) readingView.setTransitionContent(cached.content)
            else readingView.setTransitionPrevContent(cached.content)
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
        val bookKey = shelfBookKey() ?: run {
            Toast.makeText(this, "请先加载章节", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentUrl!!
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
        val inShelf = shelfBookKey()?.let { historyStore.isInShelf(it) } ?: false
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
        // 本地书用完整文件路径作 bookKey（文件路径本身唯一标识一本本地书），
        // 在线书用 keyOf(url)（去掉章节文件名，按"书"去重）。二者必须与书架存储的 bookKey 一致，
        // 否则 isInShelf 永远为 false，书架进度永远不更新 → 本地书续读失败。
        val bookKey = shelfBookKey() ?: return
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
                title = if (isLocalBook) localBookTitle.ifBlank { currentChapterTitle }
                        else currentBookTitle.ifBlank { currentChapterTitle },
                url = url,
                bookKey = bookKey,
                nextChapterUrl = nextChapterUrl,
                position = position,
                totalPages = totalPages,
                scrollMode = settings.scrollMode,
                localChapter = if (isLocalBook) localChapterIndex else -1
            )
        )

        // 若已加入书架，同步阅读进度（保持书架顺序不变）
        // 关键：本地书必须同步 localChapter（章节索引），否则书架里停留在首次打开的章节，
        // 重新从书架进入会一直从第 0 章开始，表现为"不保存上次阅读位置"。
        if (historyStore.isInShelf(bookKey)) {
            historyStore.updateShelfProgress(
                bookKey,
                position,
                totalPages,
                nextChapterUrl,
                settings.scrollMode,
                chapterTitle = currentChapterTitle,
                localChapter = if (isLocalBook) localChapterIndex else -1
            )
        }
    }

    /**
     * 当前书在书架/历史里的去重键。
     * - 本地书：用完整文件路径（LocalBookLoader 按内容生成稳定缓存名，唯一标识一本书）；
     *   不能用 HistoryItem.keyOf()，因为它会把文件名截掉只留目录，与书架存储的 bookKey 不一致。
     * - 在线书：用 HistoryItem.keyOf(url)（去掉章节文件名，按"书"维度去重）。
     */
    private fun shelfBookKey(): String? {
        val url = currentUrl ?: return null
        return if (isLocalBook) url else HistoryItem.keyOf(url)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    // ===== 本地书（txt）阅读 =====

    /**
     * 打开本地 txt 书：读取缓存正文 → 按章节切分 → 定位到续读章节 → 设置内容。
     * 本地书无网络章节 URL，翻章即在 localChapters 间切换。
     */
    private fun openLocalBook() {
        val path = intent.getStringExtra(EXTRA_LOCAL_FILE) ?: run {
            currentChapterTitle = "未加载内容"
            tvTitle.text = currentChapterTitle
            showUrlInputDialog()
            return
        }
        localFilePath = path
        isLocalBook = true
        localBookTitle = intent.getStringExtra(EXTRA_LOCAL_TITLE) ?: ""
        localChapterIndex = intent.getIntExtra(EXTRA_LOCAL_CHAPTER, 0).coerceAtLeast(0)

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "本地文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        localWholeText = file.readText()  // 默认 UTF-8（LocalBookLoader 写入时即 UTF-8）
        localChapters = LocalBookLoader.splitChapters(localWholeText)
        if (localChapters.isEmpty()) {
            // 没有任何章节标题：把整本当作单章
            localChapters = listOf(LocalChapter(localBookTitle.ifBlank { "全文" }, 0, localWholeText.length))
        }
        localChapterIndex = localChapterIndex.coerceIn(0, localChapters.size - 1)
        applyLocalChapter(localChapterIndex, restore = true)
        // 本地书无在线目录，隐藏换源入口
        tocChangeSource.visibility = View.GONE
    }

    /**
     * 应用本地书某一章：切出该章正文交给 ReadingView 分页/排版。
     * restore=true 时依据传入的 EXTRA_POSITION 续读页码/百分比。
     */
    private fun applyLocalChapter(index: Int, restore: Boolean = false, startAtEnd: Boolean = false) {
        if (index !in localChapters.indices) return
        localChapterIndex = index
        val ch = localChapters[index]
        val chapterContent = localWholeText.substring(ch.start, ch.end)
        currentChapterTitle = ch.title.ifBlank { "第 ${index + 1} 章" }
        currentBookTitle = localBookTitle
        currentUrl = localFilePath  // 让 saveCurrentHistory / 书架以文件路径为唯一键
        // 排版（relayout）完成后再定位：setContent 的折行是异步的，若在旧章 totalPages 上立即
        // setPage/续读定位，totalPages 会短暂偏小，被边界 clamp 拦截，表现为“无法翻页”。
        // 改为在 onLayoutReady（relayout 完成后、内容首次绘制前）里定位，保证 totalPages 已正确。
        // 续读→restorePosition；回翻上一章→末页；下一章/目录跳转→首页。
        readingView.onLayoutReady = when {
            restore -> ({ restorePosition() })
            startAtEnd -> ({ readingView.goToLastPage() })
            else -> ({ readingView.setPage(0) })
        }
        readingView.setContent(chapterContent)
        tvTitle.text = currentChapterTitle
        updateLocalNav()
        tocTargetUrl = null
        ReadingStats.addChars(chapterContent.length)
        ReadingStats.addChapter()
    }

    private fun updateLocalNav() {
        val hasPrev = localChapterIndex > 0
        val hasNext = localChapterIndex < localChapters.size - 1
        btnPrevChapter.isEnabled = hasPrev
        btnNextChapter.isEnabled = hasNext
        val dim = 0.4f
        btnPrevChapter.alpha = if (hasPrev) 1f else dim
        btnNextChapter.alpha = if (hasNext) 1f else dim
    }

    private fun openLocalToc() {
        tocOverlay.visibility = View.VISIBLE
        tocOverlay.alpha = 0f
        tocOverlay.animate().alpha(1f).setDuration(200).start()
        tocDrawer.animate().translationX(0f).setDuration(250).start()
        renderLocalToc()
    }

    private fun renderLocalToc() {
        if (localChapters.isEmpty()) {
            tocLoading.visibility = View.GONE
            tocEmpty.visibility = View.VISIBLE
            tocList.removeAllViews()
            tocPagerBar.visibility = View.GONE
            return
        }
        // 定位到当前章节所在页（与在线目录一致的逻辑）
        if (localChapterIndex >= 0 && tocPageSize < localChapters.size) {
            tocCurrentPage = localChapterIndex / tocPageSize
        } else {
            tocCurrentPage = 0
        }
        renderTocPage()
    }

    companion object {
        const val EXTRA_LOCAL_FILE = "EXTRA_LOCAL_FILE"
        const val EXTRA_LOCAL_TITLE = "EXTRA_LOCAL_TITLE"
        const val EXTRA_LOCAL_CHAPTER = "EXTRA_LOCAL_CHAPTER"
    }
}
