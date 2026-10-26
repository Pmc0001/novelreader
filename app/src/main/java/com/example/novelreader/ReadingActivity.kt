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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 目录的「最佳起点 URL」：通常是书页/目录页。章节目录页往往要先抓一次章节页才能找到「目录」入口，
    // 从书页起爬能少一个 RTT；打开书时记下来，后续 loadToc/prefetchToc 直接复用。
    private var tocSourceUrl: String? = null
    // 当前目录列表里是否成功定位到了「正在读的这一章」（用于全量目录补齐后决定要不要重新定位）
    private var tocCurrentFound = false
    // 当前章在目录中的下标（-1 表示没定位到）；「全部」模式据此决定首批要渲染到哪
    private var tocCurrentIndex = -1
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
        // 进程被系统在后台杀掉后重建时，Intent extras 仍是"最初启动"时的快照（已过期），
        // 直接用会回到很久前的章节/位置。这里先用 onSaveInstanceState 保存的最新导航
        // 状态覆盖 intent，使后续 loadContent() 走正确的续读分支，恢复到真正的上次阅读页。
        savedInstanceState?.let { restoreSavedInstanceState(it) }
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
     *
     * 性能：不再「等整条目录爬完才去抓第一章」。目录首页一解析出来（[getToc] 的 onFirstPage）
     * 就立刻并发抓第一章并显示，剩余分页在后台继续补 —— 几十页目录的大书能省下数秒空白。
     */
    private fun openBook(bookUrl: String, bookTitle: String?) {
        // 关键：把传入的书名固定到 currentBookTitle，后续章节切换不会覆盖它
        currentBookTitle = bookTitle?.takeIf { it.isNotBlank() } ?: ""
        currentChapterTitle = bookTitle ?: "加载中…"
        tvTitle.text = currentChapterTitle
        showLoading(true)
        // 这一版目录是从「书页」爬出来的，记下来：后面打开目录不用再从章节页绕一圈
        tocSourceUrl = bookUrl
        val loader = UrlLoader(this)
        // 首章只进一次：快通道和目录兜底可能都想进，用原子标记去重，避免抓两遍、界面闪两下
        val firstRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val enterFirstIfNeeded: (String) -> Unit = { url ->
            if (firstRequested.compareAndSet(false, true)) {
                lifecycleScope.launch { enterFirstChapter(loader, url) }
            }
        }
        lifecycleScope.launch {
            val toc = getToc(bookUrl) { items ->
                items.firstOrNull()?.url?.let { enterFirstIfNeeded(it) }
            }
            when (toc) {
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
                    // 轻量通道没回吐首页（例如走了 WebView 通道）时，这里补上首章
                    enterFirstIfNeeded(first.url)
                }
                is UrlLoader.ChapterListResult.Error -> {
                    // 首章若已由快通道显示成功，目录失败不应把正文打回加载态
                    if (!firstRequested.get()) {
                        showLoading(false)
                        Toast.makeText(this@ReadingActivity, toc.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 打开一本书的「第一章」：抓正文、应用并预取相邻章。由 [openBook] 的任意一条通道触发（只跑一次）。 */
    private suspend fun enterFirstChapter(loader: UrlLoader, firstUrl: String) {
        when (val chap = loader.loadUrl(firstUrl)) {
            is UrlLoader.LoadResult.Success -> {
                showLoading(false)
                prevChapters.clear()
                currentUrl = firstUrl
                nextChapterUrl = chap.nextChapterUrl
                val cached = CachedChapter(chap.title, chap.content, chap.nextChapterUrl)
                chapterCache[firstUrl] = cached
                applyChapter(cached, firstUrl)
                prefetchChapters(firstUrl, chap.nextChapterUrl, null)
                // 目录可能还在后台爬：等它就绪后回填上一/下一章入口
                ensureTocLoaded(firstUrl)
            }
            is UrlLoader.LoadResult.Error -> {
                showLoading(false)
                Toast.makeText(this@ReadingActivity, chap.message, Toast.LENGTH_LONG).show()
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

    // 目录 single-flight 加载：已缓存直接返回；进行中的任务（无论由哪个入口发起）共享其结果；
    // 否则启动一次加载并填充 chapterToc，后续的 loadToc/prefetchToc 调用复用同一结果。
    //
    // 注意不再按 baseUrl 区分在途任务：同一个 Activity 只对应一本书，而「打开书」用书 URL 起爬、
    // 「首章显示后补目录」用章节 URL 请求 —— 若按 key 判定就会重复爬一整条目录（多页目录尤其明显）。
    private suspend fun getToc(
        baseUrl: String,
        onFirstPage: ((List<UrlLoader.ChapterItem>) -> Unit)? = null
    ): UrlLoader.ChapterListResult {
        if (chapterToc.isNotEmpty()) {
            return UrlLoader.ChapterListResult.Success(chapterToc)
        }
        tocDeferred?.let { if (it.isActive) return it.await() }
        val loader = UrlLoader(this)
        // 包一层：目录抓取若抛异常，把它收敛成 Error 结果 —— 现在有多个入口会 await 同一个任务，
        // 裸异常会让任意一个调用方的协程直接崩掉（表现为打开书就闪退）。
        val deferred = lifecycleScope.async {
            runCatching {
                val r = loader.loadChapterList(baseUrl) { firstPage ->
                    // 目录「首页」解析出来就先交给 UI...
                    publishTocFirstPage(firstPage)
                    onFirstPage?.invoke(firstPage)
                }
                // 全量到齐：直接在这里落袋（而不是等 await 回来）—— 首个调用方可能已被取消，
                // 但目录本身仍应该补全，否则目录会永远停在首页那几十章。
                if (r is UrlLoader.ChapterListResult.Success &&
                    r.items.size > chapterToc.size && !isFinishing && !isDestroyed
                ) {
                    chapterToc = r.items
                    onTocDataRefreshed()
                }
                r
            }.getOrElse {
                UrlLoader.ChapterListResult.Error("目录加载失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
        tocDeferred = deferred
        return deferred.await()
    }

    /**
     * 目录首页到手（[UrlLoader.loadChapterList] 的 onFirstPage，回调在 IO 线程）。
     * 先落袋并让已打开的目录抽屉立刻出内容 —— 这是「打开目录要等 3 秒」的主修：
     * 痛点不是目录慢，而是旧实现非要等整条目录（含全部分页）爬完才肯渲染。
     */
    private fun publishTocFirstPage(items: List<UrlLoader.ChapterItem>) {
        if (items.size < 3) return
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@launch
            if (chapterToc.size >= items.size) return@launch
            chapterToc = items
            // 抽屉正开着且还是空的（正在转圈）：立刻把首页那段画出来，loading 收掉
            if (tocOverlay.visibility == View.VISIBLE && tocList.childCount == 0) {
                tocLoading.visibility = View.GONE
                renderToc()
            }
        }
    }

    /** 目录数据变多（首页 → 全量）后的轻量刷新：不重建整张列表，避免把用户正在看的位置跳走。 */
    private fun onTocDataRefreshed() {
        if (!isLocalBook) {
            currentUrl?.let { url ->
                updateNextFromToc(url)
                updatePrevFromToc(url)
                updateNavButtons()
            }
        }
        if (tocOverlay.visibility != View.VISIBLE) return
        if (chapterToc.isEmpty()) return
        if (tocList.childCount == 0) {
            tocLoading.visibility = View.GONE
            renderToc()
            return
        }
        // 之前没在目录里定位到当前章（当前章在首页之后）：现在全量到了，重新定位并跳过去
        if (!tocCurrentFound) {
            renderToc()
            return
        }
        // 当前这一页还有章节没显示出来（首页只到一半）→ 重画本页并保持滚动位置；否则只更新页码
        // （「全部」模式走滚动懒加载，这里只更新页码，不重建列表）
        if (tocPageEnd() > tocList.childCount) {
            renderTocPage(restoreScrollY = tocScrollView.scrollY)
        } else {
            updateTocPagerOnly()
        }
    }

    /** 只刷新分页条上的页码/按钮状态，不碰列表内容。 */
    private fun updateTocPagerOnly() {
        val totalPages = tocTotalPages()
        if (totalPages > 0 && tocCurrentPage >= totalPages) tocCurrentPage = totalPages - 1
        tocPagerBar.visibility = View.VISIBLE
        tocPagerNavRow.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        tocPageInfo.text = "${tocCurrentPage + 1} / $totalPages"
        tocBtnPrevPage.alpha = if (tocCurrentPage > 0) 1f else 0.4f
        tocBtnNextPage.alpha = if (tocCurrentPage < totalPages - 1) 1f else 0.4f
        updatePageSizeButtons()
    }

    private fun loadToc() {
        // 优先从「书页/目录页」起爬：从章节页起爬要先抓一次章节页找「目录」入口，白白多一个 RTT
        val baseUrl = tocSourceUrl ?: currentUrl ?: run {
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
            val result = getToc(baseUrl)
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is UrlLoader.ChapterListResult.Success -> {
                    tocLoading.visibility = View.GONE
                    currentUrl?.let { url ->
                        updateNextFromToc(url)
                        updatePrevFromToc(url)
                        updateNavButtons()
                    }
                    if (chapterToc.isEmpty()) {
                        tocEmpty.visibility = View.VISIBLE
                    } else if (tocList.childCount == 0) {
                        // 首页没能提前渲染（例如只能走 WebView 通道）时在这里补一次整表渲染
                        renderToc()
                    } else {
                        // 列表已由 publishTocFirstPage 画出来了，这里只把页码/总页数对齐
                        updateTocPagerOnly()
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
        // 记录定位结果：目录可能只加载了首页那一段，当前章若在更后面就定位不到，
        // 等全量目录补齐后（onTocDataRefreshed）需要重新定位并跳过去。
        tocCurrentFound = currentIdx >= 0
        tocCurrentIndex = currentIdx
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

    /** 目录列表总条数（本地/在线）。 */
    private fun tocItemCount(): Int = if (isLocalBook) localChapters.size else chapterToc.size

    /** 目录是否处于「分页」模式（选「全部」时为 false，走滚动懒加载）。 */
    private fun isTocPageable(): Boolean {
        val total = tocItemCount()
        return total > 0 && tocPageSize < total
    }

    /** 分页模式下本页最后一项的下标（+1）；「全部」模式返回 0，表示不做「补齐本页」的重画。 */
    private fun tocPageEnd(): Int {
        if (!isTocPageable()) return 0
        return minOf(tocCurrentPage * tocPageSize + tocPageSize, tocItemCount())
    }

    /**
     * 渲染目录当前页。
     * @param restoreScrollY 非 null 时表示「目录补齐后重画本页」：恢复原来的滚动位置，
     *   而不是像首次打开那样自动居中到当前章（否则用户正看着的位置会被跳走）。
     */
    private fun renderTocPage(restoreScrollY: Int? = null) {
        tocList.removeAllViews()
        tocLoading.visibility = View.GONE
        tocEmpty.visibility = View.GONE
        val total = tocItemCount()
        val totalPages = tocTotalPages()

        // 限制页码范围
        if (tocCurrentPage < 0) tocCurrentPage = 0
        if (tocCurrentPage >= totalPages && totalPages > 0) tocCurrentPage = totalPages - 1

        // 「全部」模式下不一次创建几千个 TextView（大书会直接卡住 2~3 秒），
        // 先渲染一批，滚到底再追加下一批；但至少要渲染到「当前章」附近，否则打开目录会落在书首。
        val pageable = tocPageSize < total
        val start = if (pageable) tocCurrentPage * tocPageSize else 0
        val end = if (pageable) {
            minOf(start + tocPageSize, total)
        } else {
            val needCurrent = minOf(tocCurrentIndex + 1, TOC_ALL_INITIAL_MAX)
            minOf(total, maxOf(start + TOC_RENDER_CHUNK, needCurrent))
        }

        // 记录当前章对应的列表项，渲染完成后将其滚动到可见（居中）位置
        var currentItemView: TextView? = null
        for (idx in start until end) {
            val (tv, selected) = buildTocItemView(idx)
            tocList.addView(tv)
            if (selected) currentItemView = tv
        }
        // 懒加载只对「全部」模式有意义；分页模式换页时要把监听清掉
        if (pageable) {
            tocScrollView.setOnScrollChangeListener(null)
        } else {
            tocScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val last = tocList.getChildAt(tocList.childCount - 1) ?: return@setOnScrollChangeListener
                val remaining = last.bottom - (scrollY + tocScrollView.height)
                if (remaining < (1200 * resources.displayMetrics.density).toInt()) appendTocChunk()
            }
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
            if (restoreScrollY != null) {
                tocScrollView.scrollTo(0, restoreScrollY)
                return@post
            }
            val v = currentItemView
            if (v != null) {
                val target = (v.top + v.height / 2 - tocScrollView.height / 2).coerceAtLeast(0)
                tocScrollView.scrollTo(0, target)
            } else {
                tocScrollView.scrollTo(0, 0)
            }
        }
    }

    /** 「全部」模式滚到底部时追加下一批（复用已有视图，不重建整张列表）。 */
    private fun appendTocChunk() {
        val total = tocItemCount()
        val from = tocList.childCount
        if (from >= total) return
        val to = minOf(from + TOC_RENDER_CHUNK, total)
        for (idx in from until to) tocList.addView(buildTocItemView(idx).first)
    }

    /** 创建一个目录项；返回值第二项表示它是否是「当前章」（用于自动滚动居中）。 */
    private fun buildTocItemView(idx: Int): Pair<TextView, Boolean> {
        val density = resources.displayMetrics.density
        val title: CharSequence
        val selected: Boolean
        val onClick: () -> Unit
        if (isLocalBook) {
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
        return tv to selected
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
        // 该章的预取可能正在飞（用户在本章末尾快速翻页时几乎必然如此）：直接等它，
        // 不要再发一次一模一样的请求 —— 否则预取白做，翻页还得从零等一个完整 RTT。
        val inflight = chapterJobs[url]
        lifecycleScope.launch {
            val prefetched = try {
                inflight?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (prefetched != null) {
                applyFetchedChapter(url, prefetched, isForward, restoreSavedPosition)
                return@launch
            }
            when (val result = loader.loadUrl(url)) {
                is UrlLoader.LoadResult.Success -> {
                    applyFetchedChapter(
                        url,
                        CachedChapter(result.title, result.content, result.nextChapterUrl),
                        isForward,
                        restoreSavedPosition
                    )
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

    /** 章节到手后的统一收尾：入缓存、收起 loading、上屏、续读定位、预取相邻章、补目录。 */
    private fun applyFetchedChapter(
        url: String,
        cached: CachedChapter,
        isForward: Boolean,
        restoreSavedPosition: Boolean
    ) {
        chapterCache[url] = cached
        showLoading(false)
        applyChapter(cached, url, isForward)
        if (restoreSavedPosition) restorePosition()
        prefetchChapters(url, cached.nextChapterUrl, prevChapters.lastOrNull())
        ensureTocLoaded(url)
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
        val baseUrl = tocSourceUrl ?: currentUrl ?: return
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
    // 预取失败就放弃，等用户真正翻章时由 loadChapter 正常加载。
    //
    // 章节抓取 single-flight（按 URL）：预取、翻章动画过渡内容、真正翻页三者共用同一个请求。
    // 旧实现只在发起前查一次 chapterCache，两个入口同时发起时会把同一页下载两遍
    // （预取刚发出、用户就翻页 → 真实翻页又重新请求一次，等于预取完全没用）。
    // 全部调用点都在主线程，Map 不需要加锁。
    private val chapterJobs = HashMap<String, Deferred<CachedChapter?>>()

    /**
     * 取（或发起）某章的轻量抓取任务。同一 URL 在飞期间只发一次请求；
     * 任务结束即从表中移除（成功结果由调用方写入 [chapterCache]）。
     */
    private fun chapterJob(loader: UrlLoader, url: String): Deferred<CachedChapter?> {
        chapterJobs[url]?.let { if (it.isActive) return it }
        val job = lifecycleScope.async(start = CoroutineStart.LAZY) {
            try {
                fetchChapterForCache(loader, url)
            } finally {
                // 协程体跑在主线程，这里是「同一 URL 只有一个在飞任务」的收尾点
                chapterJobs.remove(url)
            }
        }
        chapterJobs[url] = job
        job.start()
        return job
    }

    private suspend fun fetchChapterForCache(loader: UrlLoader, target: String): CachedChapter? {
        // 预取是"锦上添花"，任何异常都不该冒泡到协程作用域（那会直接闪退），一律当作"没取到"
        return try {
            when (val r = loader.fetchChapterLight(target)) {
                is UrlLoader.LoadResult.Success -> CachedChapter(r.title, r.content, r.nextChapterUrl)
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
                val cached = chapterJob(loader, target).await() ?: return@launch
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
            val cached = chapterJob(loader, url).await() ?: return@launch
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
                            // 换了一本书：丢弃上一本的目录缓存与在途目录任务，
                            // 否则目录的 single-flight 会把上一本的目录当成这本的
                            chapterToc = emptyList()
                            tocDeferred = null
                            tocSourceUrl = null
                            tocCurrentFound = false
                            tocTargetUrl = null
                            val cached = CachedChapter(result.title, result.content, result.nextChapterUrl)
                            chapterCache[url] = cached
                            applyChapter(cached, url)
                            prefetchChapters(url, result.nextChapterUrl, null)
                            ensureTocLoaded(url)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存"本次进程内"最新导航状态。进程在后台被系统杀掉后，Android 会用最初的
        // Intent 重建本 Activity（extras 是启动时的快照、早已过期）。有了这份快照，
        // restoreSavedInstanceState 才能把 intent 覆盖为最新值，避免回到很久前的阅读页。
        readingView.commitCurrentPage()
        outState.putBoolean(STATE_IS_LOCAL_BOOK, isLocalBook)
        outState.putString(STATE_CURRENT_URL, currentUrl)
        outState.putString(STATE_NEXT_CHAPTER, nextChapterUrl)
        outState.putString(STATE_CURRENT_BOOK_TITLE, currentBookTitle)
        if (isLocalBook) {
            localFilePath?.let { outState.putString(STATE_LOCAL_FILE_PATH, it) }
            outState.putString(STATE_LOCAL_TITLE, localBookTitle)
            outState.putInt(STATE_LOCAL_CHAPTER_INDEX, localChapterIndex)
        }
        // 章节内位置（翻页=页码 / 滚动=百分比），与 saveCurrentHistory 写入 HistoryStore
        // 的口径一致，restorePosition 据此还原页内定位
        val position: Int
        val totalPages: Int
        if (settings.scrollMode) {
            position = (readingView.scrollPercent * 100).toInt()
            totalPages = 0
        } else {
            position = readingView.currentPage
            totalPages = readingView.pageCount
        }
        outState.putInt(STATE_POSITION, position)
        outState.putInt(STATE_TOTAL_PAGES, totalPages)
        outState.putBoolean(STATE_SCROLL_MODE, settings.scrollMode)
    }

    /**
     * 重建时用保存的最新导航状态覆盖 intent extras。
     * 原因：进程被杀后 Android 用最初启动 Intent 重建，其 extras 是过期快照
     * （EXTRA_URL/EXTRA_POSITION/EXTRA_LOCAL_CHAPTER 等仍是第一次打开时的值），
     * 不覆盖会回到很久前的章节。这里统一改走"按 url 续读 / 本地书续读"分支。
     */
    private fun restoreSavedInstanceState(state: Bundle) {
        // 仅在确有本类保存的导航状态时才恢复；否则保持 intent 原样（首次启动等场景）
        if (!state.containsKey(STATE_CURRENT_URL) && !state.containsKey(STATE_LOCAL_FILE_PATH)) return
        // 清掉"最初启动"intent 里的快照 extras，避免 loadContent 落入 content/bookUrl 分支
        // 而回到很久前的章节
        intent.removeExtra("EXTRA_CONTENT")
        intent.removeExtra("EXTRA_BOOK_URL")
        intent.removeExtra("EXTRA_BOOK_TITLE")
        currentBookTitle = state.getString(STATE_CURRENT_BOOK_TITLE) ?: ""
        intent.putExtra("EXTRA_POSITION", state.getInt(STATE_POSITION, 0))
        intent.putExtra("EXTRA_TOTAL_PAGES", state.getInt(STATE_TOTAL_PAGES, 0))
        intent.putExtra("EXTRA_SCROLL_MODE", state.getBoolean(STATE_SCROLL_MODE, settings.scrollMode))
        if (state.getBoolean(STATE_IS_LOCAL_BOOK)) {
            state.getString(STATE_LOCAL_FILE_PATH)?.let { intent.putExtra(EXTRA_LOCAL_FILE, it) }
            intent.putExtra(EXTRA_LOCAL_TITLE, state.getString(STATE_LOCAL_TITLE) ?: "")
            intent.putExtra(EXTRA_LOCAL_CHAPTER, state.getInt(STATE_LOCAL_CHAPTER_INDEX, 0))
        } else if (state.containsKey(STATE_CURRENT_URL)) {
            intent.putExtra("EXTRA_URL", state.getString(STATE_CURRENT_URL))
            state.getString(STATE_NEXT_CHAPTER)?.let { intent.putExtra("EXTRA_NEXT_CHAPTER", it) }
        }
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
     *
     * 性能：整本正文常有几 MB，readText + 切章是纯 CPU/IO 的百毫秒级操作，
     * 旧实现在 onCreate 里同步跑完 → 打开瞬间白屏、甚至 ANR 风险。
     * 现在全部丢到 IO 线程（切章优先读 .idx 索引，见 LocalBookLoader.chaptersFor）。
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
        // 本地书无在线目录，换源入口直接隐藏（不等正文加载完，避免栏上闪一下按钮）
        tocChangeSource.visibility = View.GONE

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "本地文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentChapterTitle = localBookTitle.ifBlank { "加载中…" }
        tvTitle.text = currentChapterTitle
        showLoading(true)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val text = file.readText()  // 默认 UTF-8（LocalBookLoader 写入时即 UTF-8）
                    text to LocalBookLoader.chaptersFor(file, text)
                }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                showLoading(false)
                Toast.makeText(this@ReadingActivity, "无法读取本地文件", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val (text, chapters) = loaded
            localWholeText = text
            // 没有任何章节标题：把整本当作单章
            localChapters = chapters.ifEmpty {
                listOf(LocalChapter(localBookTitle.ifBlank { "全文" }, 0, text.length))
            }
            localChapterIndex = localChapterIndex.coerceIn(0, localChapters.size - 1)
            // applyLocalChapter 会在排版完成（onLayoutReady）里收起 loading，避免"暂无内容"闪一帧
            applyLocalChapter(localChapterIndex, restore = true)
        }
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
        // 空章（相邻两个标题挨着）不会触发布局，onLayoutReady 也就永不回调 —— 直接收起 loading，
        // 否则打开书时那片 loading 会一直盖在"暂无内容"上。
        if (chapterContent.isBlank()) showLoading(false)
        // 排版（relayout）完成后再定位：setContent 的折行是异步的，若在旧章 totalPages 上立即
        // setPage/续读定位，totalPages 会短暂偏小，被边界 clamp 拦截，表现为“无法翻页”。
        // 改为在 onLayoutReady（relayout 完成后、内容首次绘制前）里定位，保证 totalPages 已正确。
        // 续读→restorePosition；回翻上一章→末页；下一章/目录跳转→首页。
        readingView.onLayoutReady = when {
            restore -> ({ restorePosition(); showLoading(false) })
            startAtEnd -> ({ readingView.goToLastPage(); showLoading(false) })
            else -> ({ readingView.setPage(0); showLoading(false) })
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
        tocCurrentIndex = localChapterIndex
        tocCurrentFound = localChapterIndex in localChapters.indices
        if (localChapterIndex >= 0 && tocPageSize < localChapters.size) {
            tocCurrentPage = localChapterIndex / tocPageSize
        } else {
            tocCurrentPage = 0
        }
        renderTocPage()
    }

    companion object {
        // 「全部」模式下的懒加载批大小：一次最多创建这么多目录项，滚到底再追加
        private const val TOC_RENDER_CHUNK = 250
        // 「全部」模式下为了定位当前章，首批最多渲染到这么多项（再多就只渲第一批、落在书首）
        private const val TOC_ALL_INITIAL_MAX = 600

        const val EXTRA_LOCAL_FILE = "EXTRA_LOCAL_FILE"
        const val EXTRA_LOCAL_TITLE = "EXTRA_LOCAL_TITLE"
        const val EXTRA_LOCAL_CHAPTER = "EXTRA_LOCAL_CHAPTER"

        // onSaveInstanceState 的 key：进程被杀后重建时据这些值覆盖过期的 intent extras
        private const val STATE_IS_LOCAL_BOOK = "state_is_local_book"
        private const val STATE_CURRENT_URL = "state_current_url"
        private const val STATE_NEXT_CHAPTER = "state_next_chapter"
        private const val STATE_CURRENT_BOOK_TITLE = "state_current_book_title"
        private const val STATE_LOCAL_FILE_PATH = "state_local_file_path"
        private const val STATE_LOCAL_TITLE = "state_local_title"
        private const val STATE_LOCAL_CHAPTER_INDEX = "state_local_chapter_index"
        private const val STATE_POSITION = "state_position"
        private const val STATE_TOTAL_PAGES = "state_total_pages"
        private const val STATE_SCROLL_MODE = "state_scroll_mode"
    }
}
