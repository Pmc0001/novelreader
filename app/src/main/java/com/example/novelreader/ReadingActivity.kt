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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    // 目录抽屉顶栏：跟随阅读页顶栏的动态栏色（applyBarTheme 统一上色）
    private lateinit var tocDrawerHeader: View
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

    // ===== 章节加载：内存 LRU + 磁盘二级缓存 + 相邻章预取 =====
    // 内存缓存按「最近访问」淘汰（accessOrder=true）：来回翻章时最久没看的先走，
    // 80 章足够覆盖一段连续阅读，前后翻动不会再触网。
    private val chapterCache = object : java.util.LinkedHashMap<String, CachedChapter>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChapter>?): Boolean =
            size > MAX_MEM_CHAPTERS
    }
    // 磁盘二级缓存（ChapterCache）：进程/页面重建后依然能秒开，读盘 ~2ms vs 网络 1~3s
    @Volatile
    private var diskCache: ChapterCache? = null

    @Volatile
    private var diskCacheKey: String? = null
    // 已落盘的章节 URL，避免同一章反复序列化写盘
    private val persistedUrls = HashSet<String>()
    private val diskWriteCount = java.util.concurrent.atomic.AtomicInteger(0)
    // 已发起过预取的 URL（时间戳）：失败后 PREFETCH_RETRY_MS 内不重复排队
    private val prefetchRequested = object : java.util.LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 400
    }
    // 目录 URL → 下标：翻章路径上要反复定位当前章，避免每次都 O(n) 扫几千章
    private var tocIndex: Map<String, Int> = emptyMap()
    private var tocIndexLoose: Map<String, Int> = emptyMap()
    // 串行预取链（目录未就绪时沿 nextChapterUrl 逐章展开）
    private var prefetchChainJob: Job? = null
    // 同一时刻只允许一个 WebView 预取在跑（WebView 很重，并发会拖慢前台）
    private var webViewPrefetchBusy = false
    // 正在用 WebView 预取的章 key：前台翻到它时不再等待
    private var webViewPrefetchKey: String? = null
    // 空闲 WebView 预取的延迟任务（翻章时立即取消）
    private var webViewPrefetchRunnable: Runnable? = null
    // WebView 预取失败时间：试满次数后进入长冷却
    private val webViewPrefetchFailedAt = HashMap<String, Long>()
    // WebView 预取失败次数（按章）：失败后自动重试，最多 3 次
    private val webViewPrefetchRetry = HashMap<String, Int>()

    private var chapterToc: List<UrlLoader.ChapterItem> = emptyList()

    // 目录加载 single-flight：多个入口（openBook / loadToc / prefetchToc / ensureTocLoaded）
    // 共享同一个加载任务，避免并发重复爬取整条目录。
    private var tocDeferred: Deferred<UrlLoader.ChapterListResult>? = null
    // 目录的「最佳起点 URL」：通常是书页/目录页。章节目录页往往要先抓一次章节页才能找到「目录」入口，
    // 从书页起爬能少一个 RTT；打开书时记下来，后续 loadToc/prefetchToc 直接复用。
    private var tocSourceUrl: String? = null
    // 目录磁盘缓存（TocStore）的读取任务：多个入口（打开目录 / 预取 / 首章补目录）都要
    // 先等它跑完再决定要不要联网 —— 缓存命中就完全不爬，省掉一次白跑的目录抓取。
    private var tocCacheJob: Job? = null
    // 磁盘里的目录还在有效期内：不需要后台刷新
    private var tocCacheFresh = false
    // 目录还没抓完时的断点：下一个要抓的分页地址（下次打开从这里接着补）
    private var tocResumePage: String? = null
    // 目录是否已经抓全（false 表示这本大书还有分页没补完，打开目录时顺手继续补）
    private var tocComplete = false
    // 上一次目录抓取失败的时间：站点没有目录/被反爬时，不能每翻一章都重爬一遍（每次都是一个 WebView）
    private var tocLastErrorAt = 0L
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

    // 章节内容统一用顶层 [CachedChapter]：它同时是磁盘缓存（ChapterCache）的序列化单元，
    // 内存与磁盘共用同一结构，省掉一层来回转换。

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading)

        settings = ReadingSettings(this)
        historyStore = HistoryStore(this)
        ReadingStats.init(this)
        // 主线程预热 WebView 的 CookieManager：后续 OkHttp 通道要从它拿 cookie 过反爬
        UrlLoader.prewarmCookies()
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
        // 长按标题栏 → 阅读性能诊断（加载来源/耗时/预取状态），排查「为什么没秒开」用
        tvTitle.setOnLongClickListener {
            showPerfDialog()
            true
        }
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
        tocDrawerHeader = findViewById(R.id.toc_drawer_header)
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
            storeCachedChapter(cacheUrl, cached)
            applyChapter(cached, cacheUrl)
            restorePosition()
            schedulePrefetch(cacheUrl, nextChapterUrl)
            prefetchToc()
            tvTitle.text = currentChapterTitle
        } else if (url != null) {
            // 历史/书架续读：查 HistoryItem 补齐书名（之前 addOrUpdate 写的就是 currentBookTitle）
            val itemTitle = historyStore.findByUrl(url)?.title
            if (!itemTitle.isNullOrBlank() && currentBookTitle.isBlank()) currentBookTitle = itemTitle
            currentUrl = url
            // 目录落盘缓存先读出来：打开目录就不用等网络（上一次读这本书时抓过的目录）
            restoreTocFromCache(url)
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
        // 首屏是空白页，遮罩要立刻出现（不能等 180ms 才给出「有反应」的反馈）
        showLoading(true, immediate = true)
        // 这一版目录是从「书页」爬出来的，记下来：后面打开目录不用再从章节页绕一圈
        tocSourceUrl = bookUrl
        val loader = UrlLoader(this)
        // 首章只进一次：快通道和目录兜底可能都想进，用原子标记去重，避免抓两遍、界面闪两下
        val firstRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        // 目录首页一到手就把第 2 章也发出去：它和第一章**并行**下载，
        // 用户读完第一章（几十秒起步）时它早就在缓存里了 —— 这是在「打开书就立刻翻章」场景下
        // 唯一能把等待消掉的时机。旧实现要等第一章上屏后才开始预取，白丢一个 RTT。
        val secondRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val prefetchSecondIfNeeded: (List<UrlLoader.ChapterItem>) -> Unit = { items ->
            if (secondRequested.compareAndSet(false, true)) {
                items.getOrNull(1)?.url?.let { second ->
                    lifecycleScope.launch {
                        val cached = chapterJob(loader, second).await() ?: return@launch
                        if (!isFinishing && !isDestroyed) storeCachedChapter(second, cached)
                    }
                }
            }
        }
        val enterFirstIfNeeded: (String) -> Unit = { url ->
            if (firstRequested.compareAndSet(false, true)) {
                lifecycleScope.launch { enterFirstChapter(loader, url) }
            }
        }
        lifecycleScope.launch {
            // 0) 目录缓存命中：第一章的 URL 直接从缓存里取，连目录都不用爬。
            //    「搜索/换源打开同一本书」的第二次起走这条路径 —— 目录与首章并行，实测首屏快一个量级。
            val cachedEntry = withContext(Dispatchers.IO) {
                runCatching { TocStore.load(this@ReadingActivity, bookUrl) }.getOrNull()
            }
            if (!isFinishing && !isDestroyed && cachedEntry != null && cachedEntry.items.isNotEmpty()) {
                if (chapterToc.isEmpty()) {
                    setChapterToc(cachedEntry.items)
                    cachedEntry.catalogUrl?.takeIf { it.isNotBlank() }?.let { tocSourceUrl = it }
                    tocResumePage = cachedEntry.nextPageUrl
                    tocCacheFresh = cachedEntry.isFresh()
                    tocComplete = cachedEntry.complete
                    onTocDataRefreshed()
                }
                enterFirstIfNeeded(cachedEntry.items.first().url)
                prefetchSecondIfNeeded(cachedEntry.items)
                // 缓存只是「先给你看」，过期了仍要在后台静默补全（目录会增量刷新）
                if (!cachedEntry.isFresh()) startTocCrawl(bookUrl, force = true)
                return@launch
            }
            val toc = getToc(bookUrl) { items ->
                items.firstOrNull()?.url?.let { enterFirstIfNeeded(it) }
                prefetchSecondIfNeeded(items)
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
                    prefetchSecondIfNeeded(toc.items)
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
                val cached = CachedChapter(chap.title, chap.content, chap.nextChapterUrl, chap.prevChapterUrl)
                storeCachedChapter(firstUrl, cached)
                applyChapter(cached, firstUrl)
                schedulePrefetch(firstUrl, chap.nextChapterUrl)
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

    private var loadingToken = 0
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 加载遮罩。
     *
     * 关键体验点：默认**延迟 [LOADING_DELAY_MS] 再显示**。命中内存/磁盘缓存的翻章通常几十毫秒
     * 就上屏了，如果 mask 立刻弹出，用户会看到一层 LOADING 闪一下 —— 明明很快，观感却是「卡了一下」。
     * 只有真的要等网络（超过 180ms）才让遮罩出现；首屏空白场景用 [immediate] 立刻显示。
     */
    private fun showLoading(show: Boolean, immediate: Boolean = false) {
        val token = ++loadingToken
        if (!show) {
            premiumLoadingView.visibility = View.GONE
            return
        }
        if (immediate) {
            premiumLoadingView.visibility = View.VISIBLE
            return
        }
        uiHandler.postDelayed({
            if (token == loadingToken && !isFinishing && !isDestroyed) {
                premiumLoadingView.visibility = View.VISIBLE
            }
        }, LOADING_DELAY_MS)
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

        // 目录抽屉顶栏跟随阅读页顶栏颜色：阅读页顶栏是「栏色 50% 半透明」盖在页面上的，
        // 抽屉自身是不透明 bgPage 背景，直接叠半透明色会二次混色导致色偏，
        // 所以这里预合成「栏色 50% + 页面背景 50%」的不透明色，与顶栏盖在页面空白处的观感一致。
        // customBarColor 已包含在 rawBarColor 里，自定义栏色同样生效。
        val bg = theme.backgroundColor
        val drawerHeaderColor = android.graphics.Color.argb(
            0xFF,
            (android.graphics.Color.red(rawBarColor) + android.graphics.Color.red(bg)) shr 1,
            (android.graphics.Color.green(rawBarColor) + android.graphics.Color.green(bg)) shr 1,
            (android.graphics.Color.blue(rawBarColor) + android.graphics.Color.blue(bg)) shr 1
        )
        tocDrawerHeader.setBackgroundColor(drawerHeaderColor)

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
            // 大书目录（移动站常见每页 20 章、上百页）可能还没补完：用户既然点开了目录，
            // 就在后台接着往下补，列表会边看边长
            if (!tocComplete) {
                val base = tocSourceUrl ?: currentUrl
                if (base != null) startTocCrawl(base, force = true)
            }
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
        force: Boolean = false,
        resumeFrom: String? = null,
        onFirstPage: ((List<UrlLoader.ChapterItem>) -> Unit)? = null
    ): UrlLoader.ChapterListResult {
        // force：目录已由磁盘缓存填好，但已过期，仍需去网络校验一次（正常调用不会走到这里）
        if (!force && chapterToc.isNotEmpty()) {
            return UrlLoader.ChapterListResult.Success(chapterToc)
        }
        tocDeferred?.let { if (it.isActive) return it.await() }
        val loader = UrlLoader(this)
        // 包一层：目录抓取若抛异常，把它收敛成 Error 结果 —— 现在有多个入口会 await 同一个任务，
        // 裸异常会让任意一个调用方的协程直接崩掉（表现为打开书就闪退）。
        val deferred = lifecycleScope.async {
            runCatching {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val r = loader.loadChapterList(
                    url = baseUrl,
                    onFirstPage = { firstPage ->
                        // 目录「首页」解析出来就先交给 UI...
                        publishTocFirstPage(firstPage)
                        onFirstPage?.invoke(firstPage)
                    },
                    resumeFrom = resumeFrom,
                    // 续抓要以已有目录为底，否则只拿回后半段、把前面的章节冲掉
                    baseItems = chapterToc,
                    // 每抓完一批分页就增量刷新：大书目录有上百页，用户翻着目录就能看到
                    // 章节一条条补进来，而不是盯着 20 章等半分钟
                    onProgress = { merged, catalog, nextPage -> publishTocProgress(merged, catalog, nextPage) }
                )
                // 全量到齐：直接在这里落袋（而不是等 await 回来）—— 首个调用方可能已被取消，
                // 但目录本身仍应该补全，否则目录会永远停在首页那几十章。
                if (r is UrlLoader.ChapterListResult.Success) {
                    addPerf(
                        "toc-net ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                            "${r.items.size}章${if (r.complete) "" else "（未抓完）"}"
                    )
                    // 记下真正解析出目录的那一页：下次刷新直接从它起爬，省掉「章节页→找目录入口」
                    r.catalogUrl?.takeIf { it.isNotBlank() }?.let { tocSourceUrl = it }
                    tocResumePage = r.nextPageUrl
                    // 落盘：下次打开这本书，目录直接读缓存（~5ms）出内容，不再等网络
                    val persistUrl = r.items.firstOrNull()?.url
                    if (persistUrl != null && !isLocalBook) {
                        val catalog = r.catalogUrl ?: tocSourceUrl
                        val complete = r.complete
                        val nextPage = r.nextPageUrl
                        appIoScope.launch {
                            runCatching {
                                TocStore.save(
                                    applicationContext, persistUrl, r.items, catalog, complete, nextPage
                                )
                            }
                        }
                    }
                    // 是否抓全要无条件更新：即使这次一条新章节都没加（数量没变），
                    // 也可能已经把最后几页补完，不更新的话每次打开目录都会再爬一遍。
                    tocCacheFresh = r.complete
                    tocComplete = r.complete
                    if (r.items.size > chapterToc.size && !isFinishing && !isDestroyed) {
                        setChapterToc(r.items)
                        onTocDataRefreshed()
                    }
                    tocLastErrorAt = 0L
                } else if (r is UrlLoader.ChapterListResult.Error) {
                    // 记下失败时间：后面每次翻章的"补目录"在冷却期内直接跳过，
                    // 否则每章都要白跑一次目录爬取（反爬站点是一次十几秒的 WebView）。
                    tocLastErrorAt = android.os.SystemClock.elapsedRealtime()
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
     * 打开书时先把落盘的目录读出来（内存 → 磁盘）。
     *
     * 这是「目录秒开」的主路径：只要这本书被抓过一次，目录抽屉在本次打开时就是
     * 同步渲染好的，用户点开即见，网络只在后台做一次过期校验。
     */
    private fun restoreTocFromCache(url: String?) {
        if (isLocalBook || chapterToc.isNotEmpty()) return
        val keyUrl = url ?: return
        if (tocCacheJob?.isActive == true) return
        tocCacheJob = lifecycleScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val entry = withContext(Dispatchers.IO) {
                runCatching { TocStore.load(this@ReadingActivity, keyUrl) }.getOrNull()
            }
            addPerf(
                "toc-cache ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                    "${entry?.items?.size ?: 0}章"
            )
            if (isFinishing || isDestroyed) return@launch
            // 读盘期间可能已经"换书"了（粘贴了新链接）：把上一本的目录盖到新书上就串书了
            val curUrl = currentUrl
            if (curUrl != null && TocStore.tocKeyOf(curUrl) != TocStore.tocKeyOf(keyUrl)) {
                return@launch
            }
            if (entry != null && chapterToc.isEmpty()) {
                setChapterToc(entry.items)
                entry.catalogUrl?.takeIf { it.isNotBlank() }?.let { tocSourceUrl = it }
                tocResumePage = entry.nextPageUrl
                tocCacheFresh = entry.isFresh()
                tocComplete = entry.complete
                // 目录一到手就能修正上一/下一章按钮，并立刻并行预取后面几章
                updateNavFromToc()
                // 抽屉可能已经打开并在转圈：立刻把缓存里的目录画出来（只画一次，避免重复建视图）
                if (tocOverlay.visibility == View.VISIBLE && tocList.childCount == 0) {
                    tocLoading.visibility = View.GONE
                    renderToc()
                }
            } else {
                tocCacheFresh = false
                tocComplete = false
            }
            // 缓存够新（6 小时内完整抓过）就完全不联网；否则后台静默补全，不弹 loading、不打断目录
            if (!tocCacheFresh) {
                startTocCrawl(tocSourceUrl ?: currentUrl ?: keyUrl, force = true)
            }
        }
    }

    /**
     * 目录首页到手（[UrlLoader.loadChapterList] 的 onFirstPage，回调在 IO 线程）。
     * 先落袋并让已打开的目录抽屉立刻出内容 —— 这是「打开目录要等 3 秒」的主修：
     * 痛点不是目录慢，而是旧实现非要等整条目录（含全部分页）爬完才肯渲染。
     */
    private fun publishTocFirstPage(items: List<UrlLoader.ChapterItem>) {
        if (items.size < 3) return
        // 首页目录先落一份盘：即使用户看完就退出、整条目录还没补全，下次打开也能秒出目录。
        // （TocStore 里"不完整"的条目不会覆盖已有的"完整"条目，所以这一步是安全的。）
        val persistUrl = items.firstOrNull()?.url
        if (persistUrl != null) {
            appIoScope.launch {
                runCatching { TocStore.save(applicationContext, persistUrl, items, null, complete = false) }
            }
        }
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@launch
            if (chapterToc.size >= items.size) return@launch
            setChapterToc(items)
            // 抽屉正开着且还是空的（正在转圈）：立刻把首页那段画出来，loading 收掉
            if (tocOverlay.visibility == View.VISIBLE && tocList.childCount == 0) {
                tocLoading.visibility = View.GONE
                renderToc()
            }
        }
    }

    /**
     * 分页补全的增量回调（[UrlLoader.loadChapterList] 的 onProgress，回调在 IO 线程）。
     *
     * 大书目录有上百页、每页 20 章，整条抓完要几十秒 —— 与其让用户盯着 20 章，
     * 不如每抓完一批就把列表刷长：用户滚着滚着就发现章节越来越全。
     */
    private fun publishTocProgress(
        items: List<UrlLoader.ChapterItem>,
        catalogUrl: String?,
        nextPage: String?
    ) {
        if (items.size < 3) return
        // 断点 + 进度落盘：用户中途退出，下次打开就从这里接着补，不用从第 1 页重来
        tocResumePage = nextPage
        val persistUrl = items.firstOrNull()?.url
        if (persistUrl != null && !isLocalBook) {
            appIoScope.launch {
                runCatching {
                    TocStore.save(
                        applicationContext, persistUrl, items, catalogUrl,
                        complete = false, nextPageUrl = nextPage
                    )
                }
            }
        }
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@launch
            if (items.size <= chapterToc.size) return@launch
            setChapterToc(items)
            if (tocOverlay.visibility == View.VISIBLE) {
                // 抽屉开着：按当前页/滚动位置增量刷新，别把用户正在看的位置跳走
                onTocDataRefreshed()
            } else {
                // 抽屉关着：只更新导航与预取，不碰视图
                updateNavFromToc()
            }
        }
    }

    /** 目录就绪/更新后，用目录修正上一/下一章入口并立刻预取后续几章（与抽屉是否打开无关）。 */
    private fun updateNavFromToc() {
        if (isLocalBook) return
        currentUrl?.let { url ->
            updateNextFromToc(url)
            updatePrevFromToc(url)
            updateNavButtons()
            // 关键补漏：目录补齐前 nextChapterUrl 可能为空（章节页没解析出「下一章」），
            // 旧实现补全后不预取 —— 用户点「下一章」才开始现抓，一抓就是好几秒。
            schedulePrefetch(url, nextChapterUrl)
        }
    }

    /** 目录数据变多（首页 → 全量）后的轻量刷新：不重建整张列表，避免把用户正在看的位置跳走。 */    private fun onTocDataRefreshed() {
        updateNavFromToc()
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
        // 注意比较的是「本页应显示的条数」而不是绝对结束下标：tocPageEnd() 返回的是
        // start+pageSize，直接和 childCount 比会在任何非首页时都成立，导致每次目录
        // 补全都重画一遍（用户滚动中被反复重建）。
        val pageStart = if (isTocPageable()) tocCurrentPage * tocPageSize else 0
        val expectedOnPage = if (isTocPageable()) tocPageEnd() - pageStart else tocItemCount()
        if (expectedOnPage > tocList.childCount) {
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
        if (chapterToc.isNotEmpty()) {
            renderToc()
            return
        }
        tocLoading.visibility = View.VISIBLE
        tocEmpty.visibility = View.GONE
        tocList.removeAllViews()
        val loading = tocCacheJob
        lifecycleScope.launch {
            // 磁盘目录缓存正在读：等它跑完（几毫秒）再决定要不要联网 ——
            // 缓存命中就直接出目录，用户看到的是「点开即见」而不是转圈。
            if (loading != null && loading.isActive) loading.join()
            if (isFinishing || isDestroyed) return@launch
            if (chapterToc.isNotEmpty()) {
                tocLoading.visibility = View.GONE
                // 缓存那条路径可能已经画过了，别重复建一页视图
                if (tocList.childCount == 0) renderToc()
                return@launch
            }
            // 优先从「书页/目录页」起爬：从章节页起爬要先抓一次章节页找「目录」入口，白白多一个 RTT
            val baseUrl = tocSourceUrl ?: currentUrl ?: run {
                Toast.makeText(this@ReadingActivity, "请先加载章节", Toast.LENGTH_SHORT).show()
                closeToc()
                return@launch
            }
            // 上次没抓完就接着抓（缓存里已有几十上百章，从断点续而不是从头再来）
            val result = getToc(baseUrl, resumeFrom = tocResumePage)
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
        // 优先命中 URL 下标表（O(1)）；失败再用章节标题兜底（目录项标题与内容页标题通常一致），
        // 避免"下一章"链接与目录链接格式不一致时定位不到当前章。
        var currentIdx = tocIndexOf(targetUrl)
        if (currentIdx < 0 && targetTitle.isNotBlank()) {
            currentIdx = chapterToc.indexOfFirst { it.title == targetTitle }
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

        // 一页 100~250 个 item，逐个 addView 会触发同等次数的 requestLayout；
        // 先挂起布局再批量添加，只做一次测量/布局 —— 目录抽屉滑入更跟手。
        prepareTocRenderBatch()
        tocList.suppressLayout(true)
        // 记录当前章对应的列表项，渲染完成后将其滚动到可见（居中）位置
        var currentItemView: TextView? = null
        try {
            for (idx in start until end) {
                val (tv, selected) = buildTocItemView(idx)
                tocList.addView(tv)
                if (selected) currentItemView = tv
            }
        } finally {
            tocList.suppressLayout(false)
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
        // 分页模式下每页条数必须严格等于「每页 N」：这里再兜一道，
        // 避免滚动监听残留把下一页的内容追加到本页后面（看起来就是"选 50 却显示 70"）。
        if (isTocPageable()) return
        val total = tocItemCount()
        val from = tocList.childCount
        if (from >= total) return
        val to = minOf(from + TOC_RENDER_CHUNK, total)
        prepareTocRenderBatch()
        tocList.suppressLayout(true)
        try {
            for (idx in from until to) tocList.addView(buildTocItemView(idx).first)
        } finally {
            tocList.suppressLayout(false)
        }
    }

    // ===== 目录渲染的复用对象 =====
    // 每页要建 100~250 个 TextView，若每项都现取密度/颜色/drawable，等于把同一份资源
    // 反序列化上百遍（目录抽屉打开时的卡顿主要来自这里）。批量渲染前算一次、整批复用。
    private var tocDensity = 0f
    private var tocTextPrimary = 0
    private var tocTextAccent = 0
    private var tocItemBg: android.graphics.drawable.Drawable? = null
    private var tocItemBgSelected: android.graphics.drawable.Drawable? = null
    // 当前章归一化后的 key：旧实现在「每个 item」里都算一次 chapterKey，几千次字符串归一化
    private var tocHighlightKey = ""

    private fun prepareTocRenderBatch() {
        if (tocDensity == 0f) tocDensity = resources.displayMetrics.density
        if (tocTextPrimary == 0) {
            tocTextPrimary = ContextCompat.getColor(this, R.color.textPrimary)
            tocTextAccent = ContextCompat.getColor(this, R.color.colorPrimary)
        }
        if (tocItemBg == null) {
            tocItemBg = ContextCompat.getDrawable(this, R.drawable.bg_toc_item)
            tocItemBgSelected = ContextCompat.getDrawable(this, R.drawable.bg_toc_item_selected)
        }
        tocHighlightKey = if (isLocalBook) "" else chapterKey(tocTargetUrl ?: currentUrl ?: "")
    }

    /** 创建一个目录项；返回值第二项表示它是否是「当前章」（用于自动滚动居中）。 */
    private fun buildTocItemView(idx: Int): Pair<TextView, Boolean> {
        val density = if (tocDensity > 0f) tocDensity else resources.displayMetrics.density
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
            title = item.title
            // 同 renderToc：URL 匹配失败时用当前章节标题兜底，保证当前章被高亮。
            selected = chapterKey(item.url) == tocHighlightKey ||
                (currentChapterTitle.isNotBlank() && item.title == currentChapterTitle)
            onClick = {
                tocTargetUrl = item.url
                closeToc()
                if (chapterKey(item.url) != chapterKey(currentUrl ?: "")) {
                    loadChapter(item.url, isForward = true)
                }
            }
        }
        val tv = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(if (tocTextPrimary != 0) tocTextPrimary else ContextCompat.getColor(this@ReadingActivity, R.color.textPrimary))
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            if (selected) {
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (tocTextAccent != 0) tocTextAccent else ContextCompat.getColor(this@ReadingActivity, R.color.colorPrimary))
                background = tocItemBgSelected
                    ?: ContextCompat.getDrawable(this@ReadingActivity, R.drawable.bg_toc_item_selected)
            } else {
                background = tocItemBg
                    ?: ContextCompat.getDrawable(this@ReadingActivity, R.drawable.bg_toc_item)
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
        // 下一章优先用章节页解析出的链接；拿不到就回退到目录 ——
        // 目录里有全部章节，是比「章节页角落的下一章链接」可靠得多的权威来源。
        // 有些站（如分 3 页的章节）只有最后一页才放「下一章」，中间页被反爬拦一下就会解析不到。
        val url = nextChapterUrl ?: tocNextUrl() ?: run {
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
        // 回上一章：优先用回翻栈；栈空（例如直接从目录/历史跳到本章）就用目录里的前一章
        val fromStack = prevChapters.removeLastOrNull()
        val url = fromStack ?: tocPrevUrl() ?: run {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(url, isForward = false)
    }

    private fun loadChapter(url: String, isForward: Boolean, restoreSavedPosition: Boolean = false) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // 用户开始翻章：立刻取消空闲 WebView 预取。它一旦跑起来就会和前台抢主线程，
        // 翻章动画会明显发涩 —— 宁可放弃这次后台预渲染。
        webViewPrefetchRunnable?.let { uiHandler.removeCallbacks(it) }
        webViewPrefetchRunnable = null
        val memHit = chapterCache[chapterKey(url)]
        if (memHit != null && !dropIfBrokenCache(url, memHit)) {
            // 内存命中：完全无 IO、无网络，直接上屏（这就是「秒开」的主路径）
            applyChapter(memHit, url, isForward)
            if (restoreSavedPosition) restorePosition()
            logChapterLoad("mem", url, startedAt)
            schedulePrefetch(url, memHit.nextChapterUrl)
            ensureTocLoaded(url)
            return
        }
        // 只有真要等 IO/网络时才走 loading，且延迟 180ms 才显示，命中磁盘时不闪遮罩
        showLoading(true)
        val loader = UrlLoader(this)
        // 该章的预取可能正在飞（用户在本章末尾快速翻页时几乎必然如此）：直接等它，
        // 不要再发一次一模一样的请求 —— 否则预取白做，翻页还得从零等一个完整 RTT。
        val inflight = chapterJobs[chapterKey(url)]
        lifecycleScope.launch {
            // 1) 先读磁盘（~5ms）。必须放在等预取之前 —— 磁盘已经命中了还去陪预取等 2.5s，
            //    用户看到的就正是「有缓存却还要等一两秒」。
            val fromDisk = withContext(Dispatchers.IO) {
                try {
                    diskCacheFor(url)?.get(chapterKey(url))
                } catch (_: Exception) {
                    null
                }
            }
            if (fromDisk != null) {
                val diskChapter = CachedChapter(fromDisk.title, fromDisk.content, fromDisk.nextChapterUrl, fromDisk.prevChapterUrl)
                if (!dropIfBrokenCache(url, diskChapter)) {
                    logChapterLoad("disk", url, startedAt)
                    applyFetchedChapter(url, diskChapter, isForward, restoreSavedPosition)
                    return@launch
                }
                // 坏缓存已清掉，继续走网络重新抓
            }
            // 2) 磁盘没有再等在飞预取，但只给很短的时间：等不到就自己发请求。
            //    WebView 预取一律不等（它要好几个 RTT，等它只会让用户更久）。
            val key = chapterKey(url)
            val waitMs = if (webViewPrefetchKey == key) WEBVIEW_PREFETCH_WAIT_MS else PREFETCH_WAIT_MS
            val prefetched = try {
                inflight?.let { job -> withTimeoutOrNull(waitMs) { job.await() } }
            } catch (e: TimeoutCancellationException) {
                // 预取自己的预算用尽 —— 只代表这次预取失败，不能连累翻章协程
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (prefetched != null) {
                logChapterLoad("prefetch", url, startedAt)
                applyFetchedChapter(url, prefetched, isForward, restoreSavedPosition)
                return@launch
            }
            // 预取还没回来（多半是正在跑 WebView）：取消它，把 WebView 和主线程让给前台，
            // 否则前台再起一个 WebView 就是两个全屏 WebView 同时加载，又慢又费内存。
            if (inflight != null && inflight.isActive) {
                runCatching { inflight.cancel() }
            }
            // 站点是否需要跳过轻量通道由 UrlLoader 自己按 host 记忆（见 shouldSkipLight）
            when (val result = loader.loadUrl(
                url,
                skipLight = UrlLoader.shouldSkipLight(url),
                lightBudgetMs = FOREGROUND_LIGHT_BUDGET_MS
            )) {
                is UrlLoader.LoadResult.Success -> {
                    refreshBookTitle(result.bookTitle)
                    logChapterLoad("network", url, startedAt)
                    applyFetchedChapter(
                        url,
                        CachedChapter(result.title, result.content, result.nextChapterUrl, result.prevChapterUrl),
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
        storeCachedChapter(url, cached)
        showLoading(false)
        applyChapter(cached, url, isForward)
        if (restoreSavedPosition) restorePosition()
        schedulePrefetch(url, cached.nextChapterUrl)
        ensureTocLoaded(url)
    }

    private fun applyChapter(cached: CachedChapter, url: String, isForward: Boolean = true) {
        currentChapterTitle = cached.title
        currentUrl = url
        nextChapterUrl = cached.nextChapterUrl
        // 章节页自身带「上一章」链接时先补进回翻栈：从目录/历史直接跳到本章时栈是空的，
        // 只靠目录兜底的话，目录一旦没加载出来就永远回不到上一章。
        cached.prevChapterUrl?.takeIf { it.isNotBlank() }?.let { prev ->
            if (prevChapters.none { chapterKey(it) == chapterKey(prev) }) {
                prevChapters.addLast(prev)
            }
        }
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
        // 统计：字数与章节数合并成一次读写（翻章路径上少一次全量 JSON 序列化）
        ReadingStats.addChapterRead(cached.content.length)
    }

    private fun updateNavButtons() {
        // 按钮可用性同样以目录兜底：章节页解析不到「下一章」不代表真的没有下一章
        // （分页章节的最后一页才放链接，中途被反爬拦一下就会解析为空）
        val hasPrev = prevChapters.isNotEmpty() || tocPrevUrl() != null
        val hasNext = !nextChapterUrl.isNullOrBlank() || tocNextUrl() != null
        btnPrevChapter.isEnabled = hasPrev
        btnNextChapter.isEnabled = hasNext
        val dim = 0.4f
        btnPrevChapter.alpha = if (hasPrev) 1f else dim
        btnNextChapter.alpha = if (hasNext) 1f else dim
    }

    /** 目录中当前章的下一章（目录可用时是权威来源） */
    private fun tocNextUrl(): String? {
        val idx = tocIndexOf(currentUrl)
        return if (idx >= 0 && idx < chapterToc.size - 1) chapterToc[idx + 1].url else null
    }

    /** 目录中当前章的上一章 */
    private fun tocPrevUrl(): String? {
        val idx = tocIndexOf(currentUrl)
        return if (idx > 0) chapterToc[idx - 1].url else null
    }

    private fun updateNextFromToc(url: String) {
        val idx = tocIndexOf(url)
        if (idx in 0 until chapterToc.size - 1) {
            // 目录不全时**不能**用它覆盖章节页自己解析出的「下一章」：
            // 目录缺章（站点分页没抓全、标题被过滤）时，目录里的"下一项"可能直接跳到
            // 后面好几章 —— 用户点下一章就跳章（如 314 直接到 317）。
            // 章节页上的「下一章」链接永远是相邻的一章，优先级更高。
            if (tocComplete || nextChapterUrl.isNullOrBlank()) {
                nextChapterUrl = chapterToc[idx + 1].url
            }
        }
    }

    private fun updatePrevFromToc(url: String) {
        val idx = tocIndexOf(url)
        if (idx > 0) {
            val prevUrl = chapterToc[idx - 1].url
            if (prevChapters.none { chapterKey(it) == chapterKey(prevUrl) }) {
                prevChapters.addLast(prevUrl)
                updateNavButtons()
            }
        }
    }

    /**
     * 目录 URL → 下标（O(1)）。目录为空或未命中返回 -1。
     *
     * 两级匹配：先按 [chapterKey] 精确匹配（保留 query，不会把不同章节混成一条），
     * 再退化成「去掉 query」的宽松匹配 —— 有些站点的目录项带 `?from=list` 之类的追踪参数，
     * 只做精确匹配会定位不到当前章。
     */
    private fun tocIndexOf(url: String?): Int {
        if (url.isNullOrBlank()) return -1
        tocIndex[chapterKey(url)]?.let { return it }
        return tocIndexLoose[normUrlLoose(url)] ?: -1
    }

    /**
     * 统一替换目录数据：同步重建 URL 下标表。
     * 翻章路径上有 3~4 处需要「当前章在目录里的位置」，每次都 O(n) 扫几千章会直接卡主线程。
     */
    private fun setChapterToc(items: List<UrlLoader.ChapterItem>) {
        chapterToc = items
        if (items.isEmpty()) {
            tocIndex = emptyMap()
            tocIndexLoose = emptyMap()
            return
        }
        val exact = HashMap<String, Int>(items.size * 2)
        val loose = HashMap<String, Int>(items.size * 2)
        for (i in items.indices) {
            exact.putIfAbsent(chapterKey(items[i].url), i)
            loose.putIfAbsent(normUrlLoose(items[i].url), i)
        }
        tocIndex = exact
        tocIndexLoose = loose
    }

    // 宽松归一化：在 [normUrl] 基础上再去掉 query。只用于目录定位的兜底匹配，
    // 不能用于缓存 key —— 站点把章节号放在 query 里时，去了 query 就全章归一了。
    private fun normUrlLoose(u: String): String = normUrl(u).substringBefore('?')

    // 归一化 URL：去掉协议、www、query、fragment、尾斜杠并转小写，
    // 用于目录与章节 URL 的宽松匹配（不少站点目录链接与内容页"下一章"链接在 www/大小写/尾部上有差异，
    // 不归一化会导致匹配失败 → 打开目录时定位不到当前章）。
    private fun normUrl(u: String): String = u
        .substringBefore('#')
        // 注意：这里刻意**不去掉 query**。很多站点的章节号就在 query 里（?chapter=123），
        // 一旦抹掉，整本书所有章节都会归一到同一个 key，目录定位会全部指向第一章。
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .lowercase()
        .trimEnd('/')

    /**
     * 章节缓存的统一 key。
     *
     * 同一章在「目录」和「章节页角落的下一章链接」里经常是两种写法：
     * `http://www.xx.com/book/1/2.html`、`https://xx.com/book/1/2.html/`…
     * 如果直接拿原始 URL 当 key，预取写进去的和翻章读出来的对不上 ——
     * 表现就是「明明预取了，翻章还在转圈重新加载」。
     *
     * 只归一化「协议 + 主机 + 锚点」这三处确定等价的部分：
     * path 可能大小写敏感、query 可能承载章节号，都不能动。
     */
    private fun chapterKey(url: String): String {
        var u = url.trim()
        val hash = u.indexOf('#')
        if (hash >= 0) u = u.substring(0, hash)
        val schemeEnd = u.indexOf("://")
        if (schemeEnd > 0) {
            val rest = u.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            val host = (if (slash >= 0) rest.substring(0, slash) else rest)
                .lowercase()
                .removePrefix("www.")
            val tail = if (slash >= 0) rest.substring(slash) else ""
            u = host + tail
        }
        return u.trimEnd('/')
    }

    // 从历史记录进入时 prevChapters 为空，加载目录以补全上一章入口
    private fun ensureTocLoaded(url: String) {
        if (chapterToc.isNotEmpty()) {
            updatePrevFromToc(url)
            // 目录已在：立刻按目录并行预取后续几章（从历史续读时最容易漏掉这一步）
            schedulePrefetch(url, nextChapterUrl)
            return
        }
        prefetchToc()
    }

    private fun prefetchToc() {
        if (chapterToc.isNotEmpty()) return
        // 刚失败过就先别再来一遍：反爬站点的目录爬取是一次十几秒的 WebView，
        // 每翻一章都重试的话，用户会觉得"翻页越来越卡"。
        if (tocLastErrorAt > 0 &&
            android.os.SystemClock.elapsedRealtime() - tocLastErrorAt < TOC_RETRY_MS
        ) return
        val baseUrl = tocSourceUrl ?: currentUrl ?: return
        // 磁盘目录还在读：等它读完（通常几毫秒）。命中且新鲜就彻底不联网了，
        // 否则会白白爬一整条目录，还会把缓存里的目录当成"过期的"再盖一遍。
        val loading = tocCacheJob
        if (loading != null && loading.isActive) {
            lifecycleScope.launch {
                loading.join()
                if (isFinishing || isDestroyed || chapterToc.isNotEmpty()) return@launch
                startTocCrawl(baseUrl)
            }
            return
        }
        startTocCrawl(baseUrl)
    }

    private fun startTocCrawl(baseUrl: String, force: Boolean = false, resumeFrom: String? = tocResumePage) {
        lifecycleScope.launch {
            val result = getToc(baseUrl, force = force, resumeFrom = resumeFrom)
            if (result is UrlLoader.ChapterListResult.Success) {
                // 目录加载完成后回填上一章/下一章（加载时目录可能还没就绪）
                currentUrl?.let { url ->
                    updateNextFromToc(url)
                    updatePrevFromToc(url)
                    updateNavButtons()
                    // 目录一就绪就能按顺序并行预取后面几章了 —— 这是「点下一章才发现要现抓」的主修
                    schedulePrefetch(url, nextChapterUrl)
                }
            }
        }
    }

    // 章节抓取 single-flight（按 URL）：预取、翻章动画过渡内容、真正翻页三者共用同一个请求。
    // 全部调用点都在主线程，Map 不需要加锁。
    private val chapterJobs = HashMap<String, Deferred<CachedChapter?>>()

    /**
     * 取（或发起）某章的轻量抓取任务。同一 URL 在飞期间只发一次请求；
     * 任务结束即从表中移除（成功结果由调用方写入 [chapterCache]）。
     */
    private fun chapterJob(
        loader: UrlLoader,
        url: String,
        allowWebView: Boolean = false
    ): Deferred<CachedChapter?> {
        val key = chapterKey(url)
        chapterJobs[key]?.let { if (it.isActive) return it }
        val job = lifecycleScope.async(start = CoroutineStart.LAZY) {
            try {
                fetchChapterForCache(loader, url, allowWebView)
            } finally {
                // 协程体跑在主线程，这里是「同一 URL 只有一个在飞任务」的收尾点
                chapterJobs.remove(key)
            }
        }
        chapterJobs[key] = job
        job.start()
        return job
    }

    /**
     * 预取一章。
     *
     * 轻量通道（OkHttp）失败时，如果这是「下一章」（[allowWebView]），就用隐藏 WebView 预渲染 ——
     * 这是反爬站点唯一能做到「翻章秒开」的办法：WebView 在后台把页面加载完、正文提取好，
     * 用户翻过去时直接命中内存缓存，一次网络等待都不用付。
     *
     * 只对**下一章**开启，后面的章节仍走轻量：WebView 很重，不能连着开几个。
     */
    private suspend fun fetchChapterForCache(
        loader: UrlLoader,
        target: String,
        allowWebView: Boolean = false
    ): CachedChapter? {
        // 预取是"锦上添花"，任何异常都不该冒泡到协程作用域（那会直接闪退），一律当作"没取到"
        // 站点已被判定只能走 WebView 时直接跳过轻量：否则每次预取都要先白等 12s 超时才轮到 WebView，
        // 预取等于晚启动十几秒，用户翻章时自然还没就位。
        val light = if (UrlLoader.isWebViewOnly(target)) null else try {
            when (val r = loader.fetchChapterLight(target, PREFETCH_LIGHT_BUDGET_MS)) {
                is UrlLoader.LoadResult.Success -> CachedChapter(r.title, r.content, r.nextChapterUrl, r.prevChapterUrl)
                else -> null
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (light != null) return light
        if (!allowWebView) return null
        if (isFinishing || isDestroyed) return null
        // 同一时刻只允许一个 WebView 预取：两个全屏 WebView 一起加载会明显拖慢前台绘制
        if (webViewPrefetchBusy) return null
        webViewPrefetchBusy = true
        webViewPrefetchKey = chapterKey(target)
        // 轻量过不去 → 后台 WebView 预渲染（页面是对用户不可见的，不会闪白块）
        return try {
            when (val r = loader.loadUrl(target, skipLight = true)) {
                is UrlLoader.LoadResult.Success -> CachedChapter(r.title, r.content, r.nextChapterUrl, r.prevChapterUrl)
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            webViewPrefetchBusy = false
            webViewPrefetchKey = null
        }
    }

    /**
     * 缓存里存的其实是浏览器错误页（「网页无法打开」等）时，把它连同磁盘文件一起清掉。
     *
     * 这是「明明有缓存却一直打不开」的根因：错误页曾经被当成正文写入缓存，
     * 之后每次翻到这一章都命中它，于是永远不再发请求。清掉才能重新抓。
     *
     * @return true 表示这份缓存是坏的、已经清理，调用方应继续走网络
     */
    private fun dropIfBrokenCache(url: String, cached: CachedChapter): Boolean {
        if (!UrlLoader.looksLikeErrorPage(cached.content, cached.title)) return false
        val key = chapterKey(url)
        chapterCache.remove(key)
        persistedUrls.remove(key)
        diskCacheFor(url)?.let { cache ->
            appIoScope.launch { runCatching { cache.remove(key) } }
        }
        addPerf("cache-broken  $key")
        android.util.Log.d("ReadingPerf", "dropped broken cache key=$key")
        return true
    }

    /** 写入内存 LRU + 落盘。落盘去重，同一章只序列化一次。 */
    private fun storeCachedChapter(url: String, cached: CachedChapter) {
        val key = chapterKey(url)
        // 错误页绝不入库：一旦写进去，这一章就再也不会重新请求了
        if (UrlLoader.looksLikeErrorPage(cached.content, cached.title)) {
            addPerf("reject-broken  $key")
            return
        }
        chapterCache[key] = cached
        trimMemCacheByChars()
        if (!persistedUrls.add(key)) return
        android.util.Log.d("ReadingPerf", "cache put key=$key len=${cached.content.length}")
        val cache = diskCacheFor(url) ?: return
        // 落盘走进程级 IO 作用域：Activity 关掉也要写完，下次打开这本书才能秒读
        appIoScope.launch {
            runCatching { cache.put(key, cached) }
            if (diskWriteCount.incrementAndGet() % 25 == 0) {
                runCatching { cache.trimTo(MAX_DISK_CHAPTERS) }
                runCatching { ChapterCache.enforceGlobalLimit(applicationContext, MAX_TOTAL_DISK_BYTES) }
            }
        }
    }

    /**
     * 内存缓存的第二道闸：按总字符数裁剪。
     *
     * 只按章数（80 章）限制是不够的 —— 有的站点一章就两三万字，80 章能到十几 MB；
     * 极端情况（整本合并成一页）单章就能吃掉几十 MB。这里按字符数兜底，
     * 超长章节会直接从内存里被淘汰（磁盘里仍有，下次读盘 ~5ms）。
     *
     * accessOrder=true 的链表头就是最久未访问的那章，从头删即可。
     */
    private fun trimMemCacheByChars() {
        var total = 0L
        for (v in chapterCache.values) total += v.content.length
        if (total <= MAX_MEM_CHARS) return
        val it = chapterCache.entries.iterator()
        while (total > MAX_MEM_CHARS && it.hasNext()) {
            val e = it.next()
            total -= e.value.content.length
            it.remove()
        }
    }

    /** 该章所属书的磁盘缓存（按 bookKey 分区，与设置页缓存管理一致）。 */
    private fun diskCacheFor(url: String): ChapterCache? {
        if (isLocalBook || url.isBlank() || url == "initial") return null
        val key = HistoryItem.keyOf(url)
        if (key.isBlank()) return null
        diskCache?.let { if (diskCacheKey == key) return it }
        return try {
            ChapterCache(this, key).also {
                diskCache = it
                diskCacheKey = key
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 章节加载来源与耗时埋点：logcat 过滤 ReadingPerf 即可确认是否真的「秒开」。 */
    private fun logChapterLoad(source: String, url: String, startedAt: Long) {
        val cost = android.os.SystemClock.elapsedRealtime() - startedAt
        addPerf("$source ${cost}ms  ${chapterKey(url)}")
        android.util.Log.d("ReadingPerf", "chapter=$source cost=${cost}ms key=${chapterKey(url)}")
    }

    /** 最近若干次加载记录，长按标题栏可查看（见 [showPerfDialog]） */
    private val perfLog = ArrayDeque<String>()

    private fun addPerf(line: String) {
        perfLog.addFirst(line)
        while (perfLog.size > 40) perfLog.removeLast()
    }

    /**
     * 诊断面板：长按标题栏打开。
     *
     * 这里回答的是「为什么这章没秒开」的三个关键问题：
     * 1. 翻章走的是哪条路径（mem/disk/prefetch/network）
     * 2. 下一章到底有没有被预取、是否还在飞
     * 3. 这个站点有没有被误判成「只能走 WebView」
     */
    private fun showPerfDialog() {
        val url = currentUrl.orEmpty()
        val host = try {
            android.net.Uri.parse(url).host.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val next = nextChapterUrl
        val nextKey = next?.let { chapterKey(it) }
        val sb = StringBuilder()
        sb.append("站点：").append(host.ifBlank { "—" }).append('\n')
        sb.append("轻量通道：")
            .append(if (UrlLoader.isWebViewOnly(url)) "已禁用（判定反爬）" else "可用")
            .append('\n')
        sb.append("内存缓存：").append(chapterCache.size).append(" 章\n")
        sb.append("本章字数：").append(chapterCache[chapterKey(url)]?.content?.length ?: 0).append('\n')
        sb.append("下一章已缓存：").append(nextKey != null && chapterCache.containsKey(nextKey)).append('\n')
        sb.append("下一章预取中：").append(nextKey != null && chapterJobs.containsKey(nextKey)).append('\n')
        sb.append("WebView 预取中：").append(webViewPrefetchKey == nextKey && nextKey != null).append('\n')
        sb.append("下章预取重试：").append(nextKey?.let { webViewPrefetchRetry[it] } ?: 0)
            .append("/").append(WEBVIEW_PREFETCH_MAX_ATTEMPTS).append(" 次\n")
        sb.append("在飞任务：").append(chapterJobs.size).append('\n')
        sb.append("\n最近加载（新→旧）：\n")
        if (perfLog.isEmpty()) sb.append("（暂无）") else perfLog.forEach { sb.append(it).append('\n') }
        AlertDialog.Builder(this)
            .setTitle("阅读性能诊断")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .setNeutralButton("仅重载本章") { _, _ -> reloadCurrentChapterFromNetwork() }
            .show()
    }

    /**
     * 清掉**本章**的缓存（内存 + 磁盘单文件）并重新抓一次。
     *
     * 范围严格限定在当前章：只删 [chapterKey] 对应的那一个内存条目和那一个磁盘文件，
     * 不碰其他章节、不碰目录、不碰缓存统计、不重置站点判定。
     * 用于「这一章内容被截断 / 是错误页」时手动救一次。
     */
    private fun reloadCurrentChapterFromNetwork() {
        val url = currentUrl ?: return
        val key = chapterKey(url)
        chapterCache.remove(key)
        persistedUrls.remove(key)
        // 取消可能正在跑的这一章预取：它拿到的很可能就是那份残缺内容，
        // 等它不但白等，还会把坏数据重新写回缓存
        chapterJobs.remove(key)?.let { runCatching { it.cancel() } }
        // 磁盘删除必须同步做完再重载：异步删的话，loadChapter 很可能赶在删除之前读到旧内容，
        // 结果看起来就是「点了重载没反应」。单文件删除是毫秒级操作，主线程直接做没有压力。
        diskCacheFor(url)?.let { cache ->
            runCatching { cache.remove(key) }
        }
        addPerf("reload-chapter  $key")
        showLoading(true, immediate = true)
        loadChapter(url, isForward = true)
    }

    /**
     * 预取调度：让「下一章 + 再后面两章」尽早就位，翻章时直接命中缓存（0 网络）。
     *
     * 两条通道：
     * - 目录已知：按目录下标**并行**预取后 [PREFETCH_AHEAD] 章。目录里有全部 URL，
     *   不必等前一章解析出「下一章」链接，这是最快的一路。
     * - 目录未知：沿 nextChapterUrl 串行链式展开（每章到手后用它的 nextChapterUrl 续跳）。
     *
     * 上一篇也会预取，回翻同样秒开。
     */
    private fun schedulePrefetch(fromUrl: String, nextUrl: String? = null, includePrev: Boolean = true) {
        if (isLocalBook) return
        val idx = tocIndexOf(fromUrl)
        val targets = LinkedHashSet<String>()
        if (idx >= 0) {
            val last = minOf(idx + PREFETCH_AHEAD, chapterToc.size - 1)
            for (i in idx + 1..last) targets.add(chapterToc[i].url)
            // 目录里的「下一章」比章节页角落的链接更可靠，顺手对齐 ——
            // 但目录还没抓全时不能对齐（缺章会导致跳章），只做预取
            if (idx < chapterToc.size - 1 && tocComplete) nextChapterUrl = chapterToc[idx + 1].url
        } else if (!nextUrl.isNullOrBlank()) {
            targets.add(nextUrl)
        }
        if (includePrev) prevChapters.lastOrNull()?.let { targets.add(it) }

        // 预取一律只走轻量通道。WebView 预渲染单独排到「用户空闲」时再做 ——
        // 在翻章瞬间起后台 WebView 会和前台抢主线程和带宽，实测反而更卡。
        prefetchUrls(fromUrl, targets)
        if (idx < 0) {
            // 目录拿不到时，用串行链补「再下一章」；换章了就以新章为起点重排
            prefetchChainJob?.cancel()
            startPrefetchChain(nextUrl)
        } else {
            prefetchChainJob?.cancel()
        }
        scheduleIdleWebViewPrefetch()
    }

    /**
     * 反爬站点兜底：等用户在当前章安静读上 [WEBVIEW_PREFETCH_IDLE_MS] 之后，
     * 才用隐藏 WebView 去预渲染下一章。
     *
     * 时机是这里的关键 —— 翻章瞬间启动后台 WebView 会抢主线程和带宽，用户会觉得更慢；
     * 而正常阅读时用户在一章上会停留几十秒到几分钟，等 6 秒再启动完全来得及。
     */
    private fun scheduleIdleWebViewPrefetch(delayMs: Long = WEBVIEW_PREFETCH_IDLE_MS) {
        webViewPrefetchRunnable?.let { uiHandler.removeCallbacks(it) }
        webViewPrefetchRunnable = null
        if (isLocalBook || isFinishing || isDestroyed) return
        val chapter = currentUrl ?: return
        val runnable = Runnable {
            webViewPrefetchRunnable = null
            if (currentUrl != chapter || isFinishing || isDestroyed) return@Runnable
            val next = nextChapterUrl ?: return@Runnable
            val key = chapterKey(next)
            if (chapterCache.containsKey(key)) return@Runnable
            if (!UrlLoader.isWebViewOnly(next)) return@Runnable
            if (webViewPrefetchBusy) return@Runnable
            // 反复失败后的长冷却：WebView 预渲染一次可能十几二十秒（Cloudflare 验证），
            // 试满次数还不成功就先放一放，避免一直烧电
            val failedAt = webViewPrefetchFailedAt[key]
            if (failedAt != null && System.currentTimeMillis() - failedAt < WEBVIEW_PREFETCH_RETRY_MS) {
                return@Runnable
            }
            val loader = UrlLoader(this)
            lifecycleScope.launch {
                val cached = chapterJob(loader, next, allowWebView = true).await()
                if (cached == null) {
                    val attempts = (webViewPrefetchRetry[key] ?: 0) + 1
                    webViewPrefetchRetry[key] = attempts
                    addPerf("webview-fail#$attempts  $key")
                    if (attempts < WEBVIEW_PREFETCH_MAX_ATTEMPTS) {
                        // 指数退避再试：2s、4s。反爬站点单次失败多半是验证超时，重试经常能过
                        uiHandler.postDelayed({
                            if (!isFinishing && !isDestroyed && currentUrl == chapter) {
                                scheduleIdleWebViewPrefetch(delayMs = 0L)
                            }
                        }, WEBVIEW_PREFETCH_RETRY_BASE_MS * attempts)
                    } else {
                        webViewPrefetchFailedAt[key] = System.currentTimeMillis()
                        addPerf("webview-giveup#$attempts  $key")
                    }
                    return@launch
                }
                if (isFinishing || isDestroyed) return@launch
                webViewPrefetchFailedAt.remove(key)
                webViewPrefetchRetry.remove(key)
                storeCachedChapter(next, cached)
                addPerf("webview-ok  $key")
            }
        }
        webViewPrefetchRunnable = runnable
        uiHandler.postDelayed(runnable, delayMs)
    }

    private fun prefetchUrls(current: String, targets: Collection<String>) {
        if (targets.isEmpty()) return
        val loader = UrlLoader(this)
        val now = System.currentTimeMillis()
        val currentKey = chapterKey(current)
        for (target in targets) {
            val key = chapterKey(target)
            if (key.isBlank() || key == currentKey) continue
            if (chapterCache.containsKey(key)) continue
            val last = prefetchRequested[key]
            if (last != null && now - last < PREFETCH_RETRY_MS) continue
            // 站点已被判定「轻量过不去」：不再浪费请求，交给空闲时段的 WebView 预渲染
            if (UrlLoader.isWebViewOnly(target)) continue
            prefetchRequested[key] = now
            lifecycleScope.launch {
                val cached = chapterJob(loader, target).await()
                if (cached == null) {
                    addPerf("prefetch-fail  $key")
                    return@launch
                }
                if (isFinishing || isDestroyed) return@launch
                storeCachedChapter(target, cached)
            }
        }
    }

    /** 目录不可用时的串行预取链：next → next.next → …，最多 [PREFETCH_AHEAD] 跳。 */
    private fun startPrefetchChain(firstNext: String?) {
        if (firstNext.isNullOrBlank()) return
        val loader = UrlLoader(this)
        prefetchChainJob = lifecycleScope.launch {
            var cursor: String? = firstNext
            var hops = 0
            while (hops < PREFETCH_AHEAD) {
                val target = cursor ?: break
                if (isFinishing || isDestroyed) break
                val cached = chapterCache[chapterKey(target)]
                    ?: chapterJob(loader, target).await()
                if (cached == null) break
                storeCachedChapter(target, cached)
                cursor = cached.nextChapterUrl
                hops++
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
        val cached = chapterCache[chapterKey(url)]
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
                            setChapterToc(emptyList())
                            tocDeferred = null
                            tocSourceUrl = null
                            tocCurrentFound = false
                            tocTargetUrl = null
                            tocCacheJob = null
                            tocCacheFresh = false
                            tocComplete = false
                            tocResumePage = null
                            tocLastErrorAt = 0L
                            // 换书了：磁盘缓存分区、预取记录都要跟着换
                            diskCache = null
                            diskCacheKey = null
                            persistedUrls.clear()
                            prefetchRequested.clear()
                            prefetchChainJob?.cancel()
                            val cached = CachedChapter(result.title, result.content, result.nextChapterUrl, result.prevChapterUrl)
                            storeCachedChapter(url, cached)
                            applyChapter(cached, url)
                            schedulePrefetch(url, result.nextChapterUrl)
                            restoreTocFromCache(url)
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
        // 清掉延迟显示 loading 的回调，避免销毁后还持有 Activity 引用
        uiHandler.removeCallbacksAndMessages(null)
        prefetchChainJob?.cancel()
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

    /**
     * 用本次抓到的书名纠正 [currentBookTitle]。
     *
     * 历史/书架里存的书名可能是早期版本抓到的章节标题（「垂钓之神-第13章 狠人棍法-苦读书」），
     * 续读时又会把它回填成当前书名，导致列表永远显示第一次进入时的章节名。
     * 只在「当前为空」或「当前明显是章节标题」时替换，避免覆盖搜索得到的干净书名。
     */
    private fun refreshBookTitle(fresh: String?) {
        val t = fresh?.trim().orEmpty()
        if (t.isEmpty() || CHAPTER_MARKER_RE.containsMatchIn(t)) return
        if (currentBookTitle.isBlank() || CHAPTER_MARKER_RE.containsMatchIn(currentBookTitle)) {
            currentBookTitle = t
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
        showLoading(true, immediate = true)
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
        ReadingStats.addChapterRead(chapterContent.length)
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

        // ===== 章节加载性能参数 =====
        // 内存里保留的章节数（按最近访问淘汰）
        private const val MAX_MEM_CHAPTERS = 80
        // 内存缓存的总字符数上限（约 400 万字）：章数只能防「碎」，字符数才防「超长章节撑爆堆」
        private const val MAX_MEM_CHARS = 4_000_000L
        // 每本书落盘的章节上限，超出按最久未写删除（磁盘缓存只做加速，不做收藏）
        private const val MAX_DISK_CHAPTERS = 400
        // 所有书合计的磁盘上限：80MB。超出按最后修改时间淘汰最旧的章节
        private const val MAX_TOTAL_DISK_BYTES = 80L * 1024 * 1024
        // 提前预取的章数：当前章之后 3 章，读完一章（几十秒）足够后台抓完
        private const val PREFETCH_AHEAD = 3
        // 预取失败后的静默期：期间不重复排队，避免反复打无效请求。
        // 不能设太长 —— 用户翻章往往就在几十秒内，静默期一过就要能重新补。
        private const val PREFETCH_RETRY_MS = 6_000L
        // 前台翻章的轻量通道预算：必须给足。砍太短会让「6 秒能成功」的站点直接超时，
        // 然后再走一遍更慢的 WebView —— 那是净亏（一次 6s 变 5s+3s）。
        private const val FOREGROUND_LIGHT_BUDGET_MS = 15_000L
        // 后台预取的轻量通道预算：慢一点无妨，成功一次就省掉后面所有等待
        private const val PREFETCH_LIGHT_BUDGET_MS = 12_000L
        // 翻章时最多陪在飞预取等这么久。不能长 —— 等不到就自己发，让用户先看到内容。
        private const val PREFETCH_WAIT_MS = 1_000L
        // WebView 预取：正在跑时前台不再等它（直接接管），避免被后台任务拖住
        private const val WEBVIEW_PREFETCH_WAIT_MS = 0L
        // 反爬站点用 WebView 预渲染下一章的「空闲延时」：用户停在同一章这么久才启动。
        // 连续的翻章操作期间会被不断取消，不会和前台翻章抢主线程/带宽；
        // 而 3s 足够让「翻完一章停下来看一眼」的用户在读完前把下一章预渲染好。
        private const val WEBVIEW_PREFETCH_IDLE_MS = 3_000L
        // WebView 预渲染失败后的冷却时间（试满 3 次仍失败才进入这个冷却）
        private const val WEBVIEW_PREFETCH_RETRY_MS = 10 * 60_000L
        // 反爬站点 WebView 预渲染的自动重试次数与退避基数（2s、4s）
        private const val WEBVIEW_PREFETCH_MAX_ATTEMPTS = 3
        private const val WEBVIEW_PREFETCH_RETRY_BASE_MS = 2_000L
        // 加载遮罩延迟显示阈值：短于这个时间的加载完全不闪遮罩
        private const val LOADING_DELAY_MS = 180L
        // 目录抓取失败后的冷却：期间不再自动补目录（用户主动点开目录仍会重试）
        private const val TOC_RETRY_MS = 90_000L

        // 进程级 IO 作用域：章节落盘不能因为 Activity 销毁而被取消
        private val appIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 「第12章 / 第 3 回」这类章节标记：用来识别书名里其实混进了章节名
        private val CHAPTER_MARKER_RE = Regex("第\\s*[0-9〇零一二三四五六七八九十百千万两]+\\s*[章回节篇集部话]")

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
