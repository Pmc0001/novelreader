package com.example.novelreader

import android.content.Intent
import com.example.novelreader.HistoryItem
import com.example.novelreader.HistoryStore
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvPageInfo: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnNightMode: ImageButton
    private lateinit var btnLoadUrl: ImageButton
    private lateinit var btnPrevChapter: TextView
    private lateinit var btnNextChapter: TextView
    private lateinit var premiumLoadingView: PremiumLoadingView
    private lateinit var tocDrawer: View
    private lateinit var tocOverlay: View
    private lateinit var tocList: ViewGroup
    private lateinit var tocLoading: ProgressBar
    private lateinit var tocEmpty: View
    private lateinit var btnToc: View

    private var isBarsVisible = false
    private var currentTitle: String = "示例小说"
    private var currentUrl: String? = null
    private var nextChapterUrl: String? = null
    private val prevChapters = ArrayDeque<String>()
    private val chapterCache = LinkedHashMap<String, CachedChapter>()
    private var chapterToc: List<UrlLoader.ChapterItem> = emptyList()

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
        currentUrl = intent.getStringExtra("EXTRA_URL")
        nextChapterUrl = intent.getStringExtra("EXTRA_NEXT_CHAPTER")

        initViews()
        setupImmersiveMode()
        loadContent()
        applySettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
    }

    private fun initViews() {
        readingView = findViewById(R.id.reading_view)
        topBarContainer = findViewById(R.id.top_bar_container)
        topBar = findViewById(R.id.top_bar)
        statusBarSpacer = findViewById(R.id.status_bar_spacer)
        bottomBar = findViewById(R.id.bottom_bar)
        topBarGradient = findViewById(R.id.top_bar_gradient)
        bottomBarGradient = findViewById(R.id.bottom_bar_gradient)
        tvTitle = findViewById(R.id.tv_title)
        tvProgress = findViewById(R.id.tv_progress)
        tvPageInfo = findViewById(R.id.tv_page_info)
        btnBack = findViewById(R.id.btn_back)
        btnSettings = findViewById(R.id.btn_settings)
        btnNightMode = findViewById(R.id.btn_night_mode)
        btnLoadUrl = findViewById(R.id.btn_load_url)
        btnPrevChapter = findViewById(R.id.btn_prev_chapter)
        btnNextChapter = findViewById(R.id.btn_next_chapter)
        premiumLoadingView = findViewById(R.id.premium_loading_view)
        tocDrawer = findViewById(R.id.toc_drawer)
        tocOverlay = findViewById(R.id.toc_overlay)
        tocList = findViewById(R.id.toc_list)
        tocLoading = findViewById(R.id.toc_loading)
        tocEmpty = findViewById(R.id.toc_empty)
        btnToc = findViewById(R.id.btn_toc)
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = ContextCompat.getColor(this, R.color.topBarOverlay)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.bottomBarOverlay)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        hideSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(topBarContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.displayCutout
            val topInset = maxOf(systemBars.top, displayCutout?.safeInsetTop ?: 0)
            statusBarSpacer.layoutParams.height = topInset
            statusBarSpacer.requestLayout()
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

        if (title != null && content != null) {
            currentTitle = title
            val cacheUrl = currentUrl ?: "initial"
            val cached = CachedChapter(title, content, nextChapterUrl)
            chapterCache[cacheUrl] = cached
            applyChapter(cached, cacheUrl)
            restorePosition()
            prefetchChapters(cacheUrl, nextChapterUrl, null)
            tvTitle.text = currentTitle
        } else if (url != null) {
            currentUrl = url
            loadChapter(url, isForward = true)
        } else {
            currentTitle = "未加载内容"
            tvTitle.text = currentTitle
            showUrlInputDialog()
        }
    }

    private fun restorePosition() {
        val startPos = intent.getIntExtra("EXTRA_POSITION", 0)
        if (startPos > 0) readingView.setInitialPage(startPos)
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

        applyBrightness(settings.brightness)
        updateStatusBarColor(theme)
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
        if (theme.id == ReadingSettings.THEME_DARK) {
            controller.isAppearanceLightStatusBars = false
        } else {
            controller.isAppearanceLightStatusBars = true
        }
    }

    private fun setupListeners() {
        readingView.onPageChanged = { current, total ->
            tvPageInfo.text = "${current + 1} / $total"
            val progress = if (total > 0) ((current + 1) * 100 / total) else 0
            tvProgress.text = "阅读进度 $progress%"

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

        btnLoadUrl.setOnClickListener {
            showUrlInputDialog()
        }

        btnPrevChapter.setOnClickListener {
            loadPrevChapter()
        }

        btnNextChapter.setOnClickListener {
            loadNextChapter()
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
    }

    private fun openToc() {
        tocOverlay.visibility = View.VISIBLE
        tocOverlay.alpha = 0f
        tocOverlay.animate().alpha(1f).setDuration(200).start()
        tocDrawer.animate().translationX(0f).setDuration(250).start()
        loadToc()
    }

    private fun closeToc() {
        tocDrawer.animate().translationX(-tocDrawer.width.toFloat()).setDuration(250).start()
        tocOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            tocOverlay.visibility = View.GONE
        }.start()
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
        tocList.removeAllViews()
        val density = resources.displayMetrics.density
        for (item in chapterToc) {
            val tv = TextView(this).apply {
                text = item.title
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ReadingActivity, R.color.textPrimary))
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                if (item.url == currentUrl) {
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
                closeToc()
                if (item.url != currentUrl) {
                    loadChapter(item.url, isForward = true)
                }
            }
            tocList.addView(tv)
        }
    }

    private fun toggleBars() {
        isBarsVisible = !isBarsVisible
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
        currentTitle = cached.title
        currentUrl = url
        nextChapterUrl = cached.nextChapterUrl
        readingView.setContent(cached.content)
        if (isForward) readingView.setPage(0) else readingView.goToLastPage()
        tvTitle.text = currentTitle
        updateNextFromToc(url)
        updatePrevFromToc(url)
        updateNavButtons()
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
        val idx = chapterToc.indexOfFirst { it.url == url }
        if (idx >= 0 && idx < chapterToc.size - 1) {
            nextChapterUrl = chapterToc[idx + 1].url
        }
    }

    private fun updatePrevFromToc(url: String) {
        if (chapterToc.isEmpty()) return
        val idx = chapterToc.indexOfFirst { it.url == url }
        if (idx > 0) {
            val prevUrl = chapterToc[idx - 1].url
            if (!prevChapters.contains(prevUrl)) {
                prevChapters.addLast(prevUrl)
                updateNavButtons()
            }
        }
    }

    // 从历史记录进入时 prevChapters 为空，加载目录以补全上一章入口
    private fun ensureTocLoaded(url: String) {
        if (chapterToc.isNotEmpty()) {
            updatePrevFromToc(url)
            return
        }
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadChapterList(url)) {
                is UrlLoader.ChapterListResult.Success -> {
                    chapterToc = result.items
                    updateNextFromToc(url)
                    updatePrevFromToc(url)
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
        val newTheme = if (settings.backgroundTheme == ReadingSettings.THEME_DARK) {
            ReadingSettings.THEME_WHITE
        } else {
            ReadingSettings.THEME_DARK
        }
        settings.backgroundTheme = newTheme
        applySettings()
    }

    override fun onPause() {
        super.onPause()
        readingView.commitCurrentPage()
        settings.lastReadPosition = readingView.currentPage
        saveCurrentHistory()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun saveCurrentHistory() {
        val url = currentUrl ?: return
        historyStore.addOrUpdate(
            HistoryItem(
                title = currentTitle,
                url = url,
                bookKey = HistoryItem.keyOf(url),
                nextChapterUrl = nextChapterUrl,
                position = readingView.currentPage,
                totalPages = readingView.pageCount
            )
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }
}
