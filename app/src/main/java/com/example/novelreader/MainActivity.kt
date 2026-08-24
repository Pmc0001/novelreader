package com.example.novelreader

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.text.TextUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.novelreader.HistoryStore

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var historyContainer: LinearLayout
    private lateinit var loadingView: PremiumLoadingView
    private lateinit var shelfTab: TextView
    private lateinit var historyTab: TextView
    private lateinit var hero: LinearLayout
    private var listTab: Int = 0   // 0 = 书架, 1 = 阅读历史

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getPreferences(MODE_PRIVATE)

        setupUI()
        handleIncomingOpenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingOpenIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForNovelUrl()
        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出 app 时默认关闭手势翻页：下次启动需手动开启，避免摄像头被意外启动
        if (isFinishing) {
            ReadingSettings(this).gestureEnabled = false
        }
    }

    private fun setupUI() {
        loadingView = findViewById(R.id.main_loading_view)
        val rootLayout = findViewById<LinearLayout>(R.id.main)
        val density = resources.displayMetrics.density

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
        }

        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_hero_gradient_v2)
            setPadding(
                (24 * density).toInt(),
                (60 * density).toInt(),
                (24 * density).toInt(),
                (44 * density).toInt()
            )
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 品牌徽章：圆形光晕底 + 翻页书插画（更"印章感"）
        val logoGlow = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (56 * density).toInt(),
                (56 * density).toInt()
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_logo_glow)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(),
                (40 * density).toInt(),
                Gravity.CENTER
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 暖棕滤镜，让 logo 与暖色光晕融合为统一徽章
            setColorFilter(0xFF7A5230.toInt())
        }
        logoGlow.addView(logo)

        val brandTitle = TextView(this).apply {
            text = "小说阅读器"
            textSize = 28f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding((14 * density).toInt(), 0, 0, 0)
            includeFontPadding = false
            // 柔和暖棕阴影，在浅色纸张背景上保留轻微浮空感
            setShadowLayer(10f, 0f, 0f, 0x227A5230)
        }
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroRow.addView(logoGlow)
        heroRow.addView(brandTitle)

        val brandSubtitle = TextView(this).apply {
            text = "粘贴链接或搜索书名，开启你的阅读之旅"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            setPadding(0, (14 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }

        // 标题下方金色装饰细线
        val decorLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                (1 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_decor_line)
        }
        val decorWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        decorWrap.addView(decorLine)

        hero.addView(heroRow)
        hero.addView(brandSubtitle)
        hero.addView(decorWrap)

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                topMargin = (-24 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_v2)
            setPadding(
                (18 * density).toInt(),
                (22 * density).toInt(),
                (18 * density).toInt(),
                (22 * density).toInt()
            )
        }

        // 主操作：搜索书名（满宽·大·暖棕渐变）
        val searchButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_search_btn_v2)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SearchActivity::class.java))
            }
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(
                (22 * density).toInt(),
                (22 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 深空紫图标，在亮色霓虹渐变按钮上保持高对比
            setColorFilter(0xFF3D2E22.toInt())
        }
        val searchLabel = TextView(this).apply {
            text = "搜索书名开始阅读"
            textSize = 16f
            setTextColor(0xFF3D2E22.toInt())
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding((10 * density).toInt(), 0, 0, 0)
        }
        searchButton.addView(searchIcon)
        searchButton.addView(searchLabel)
        cardLayout.addView(searchButton)

        // 次操作组：粘贴链接 + 阅读统计（横向 50/50）
        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }

        val loadUrlCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = (6 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding(
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt()
            )
            setOnClickListener { showUrlInputDialog() }
        }
        val loadUrlIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_url)
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
        }
        val loadUrlLabel = TextView(this).apply {
            text = "粘贴链接"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        loadUrlCard.addView(loadUrlIcon)
        loadUrlCard.addView(loadUrlLabel)

        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = (6 * density).toInt(); marginEnd = (6 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding(
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt()
            )
            setOnClickListener {
                ReadingStats.init(this@MainActivity)
                startActivity(Intent(this@MainActivity, StatsActivity::class.java))
            }
        }
        val statsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_stats)
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
        }
        val statsLabel = TextView(this).apply {
            text = "阅读统计"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        statsCard.addView(statsIcon)
        statsCard.addView(statsLabel)

        val stepsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = (6 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding(
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt()
            )
            setOnClickListener {
                startActivity(Intent(this@MainActivity, StepsActivity::class.java))
            }
        }
        val stepsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_steps)
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
        }
        val stepsLabel = TextView(this).apply {
            text = "今日步数"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        stepsCard.addView(stepsIcon)
        stepsCard.addView(stepsLabel)

        secondaryRow.addView(loadUrlCard)
        secondaryRow.addView(statsCard)
        secondaryRow.addView(stepsCard)
        cardLayout.addView(secondaryRow)

        // 设置按钮
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding(
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt()
            )
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val settingsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
        }
        val settingsLabel = TextView(this).apply {
            text = "阅读设置"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), 0, 0, 0)
        }
        settingsCard.addView(settingsIcon)
        settingsCard.addView(settingsLabel)
        cardLayout.addView(settingsCard)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 书架 / 阅读历史 切换标签（V2 胶囊滑动指示器）
        shelfTab = TextView(this).apply {
            text = "书架"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            setOnClickListener { listTab = 0; updateTabs(); refreshList() }
        }
        historyTab = TextView(this).apply {
            text = "阅读历史"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density).toInt()
            }
            setOnClickListener { listTab = 1; updateTabs(); refreshList() }
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (28 * density).toInt()
                marginEnd = (28 * density).toInt()
                topMargin = (20 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tab_track)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            addView(shelfTab)
            addView(historyTab)
        }
        updateTabs()

        val spacer2 = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1.5f }
        }

        val footerText = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
            letterSpacing = 0.05f
        }

        mainLayout.addView(hero)
        mainLayout.addView(cardLayout)
        mainLayout.addView(tabBar)
        mainLayout.addView(historyContainer)
        mainLayout.addView(spacer2)
        mainLayout.addView(footerText)

        scrollView.addView(mainLayout)
        rootLayout.addView(scrollView)

        // 沉浸式状态栏：内容延伸至状态栏后方，仅底部留出导航栏空间
        // hero 顶部 padding 叠加状态栏高度，保证标题不被状态栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, systemBars.bottom)
            hero.setPadding(
                (24 * density).toInt(),
                (systemBars.top + (48 * density).toInt()),
                (24 * density).toInt(),
                (44 * density).toInt()
            )
            insets
        }
    }

    private fun refreshList() {
        historyContainer.removeAllViews()
        if (listTab == 0) renderShelf() else renderHistory()
    }

    private fun updateTabs() {
        val indicator = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tab_indicator)
        // 激活文字用深空紫，在亮色霓虹渐变指示器上保持高对比
        val activeText = 0xFF3D2E22.toInt()
        val inactiveText = ContextCompat.getColor(this@MainActivity, R.color.textSecondary)
        shelfTab.background = if (listTab == 0) indicator else null
        historyTab.background = if (listTab == 1) indicator else null
        shelfTab.setTextColor(if (listTab == 0) activeText else inactiveText)
        historyTab.setTextColor(if (listTab == 1) activeText else inactiveText)
    }

    private fun renderHistory() {
        val history = HistoryStore(this@MainActivity).getHistory()
        val density = resources.displayMetrics.density

        historyContainer.addView(buildSectionHeader("阅读历史", "近期阅读的书"))
        if (history.isEmpty()) {
            historyContainer.addView(buildEmptyState(
                "还没有阅读记录",
                "去搜索或粘贴链接，开启你的第一本书"
            ))
            return
        }

        for (item in history) {
            val arrow = TextView(this@MainActivity).apply {
                text = "继续 ›"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
                typeface = Typeface.DEFAULT_BOLD
                setPadding((16 * density).toInt(), 0, 0, 0)
            }
            historyContainer.addView(makeBookRow(item, rowSubtitle(item), arrow) { openBook(item) })
        }

        val clearBtn = TextView(this@MainActivity).apply {
            text = "清除历史记录"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            (layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = (16 * density).toInt() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt(); bottomMargin = (8 * density).toInt() }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                HistoryStore(this@MainActivity).clear()
                refreshList()
            }
        }
        val clearWrap = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(clearBtn)
        }
        historyContainer.addView(clearWrap)
    }

    private fun renderShelf() {
        val shelf = HistoryStore(this@MainActivity).getShelf()
        val density = resources.displayMetrics.density

        historyContainer.addView(buildSectionHeader("书架", "你收藏的书"))

        if (shelf.isEmpty()) {
            historyContainer.addView(buildEmptyState(
                "书架空空如也",
                "在阅读页点击右上角 ★ 把书加入书架"
            ))
            return
        }

        for (item in shelf) {
            val remove = TextView(this@MainActivity).apply {
                text = "移出"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
                setPadding((16 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    HistoryStore(this@MainActivity).removeFromShelf(item.bookKey)
                    refreshList()
                }
            }
            historyContainer.addView(makeBookRow(item, rowSubtitle(item), remove) { openBook(item) })
        }

        val clearBtn = TextView(this@MainActivity).apply {
            text = "清空书架"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_btn_v2)
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                HistoryStore(this@MainActivity).clearShelf()
                refreshList()
            }
        }
        val clearWrap = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(clearBtn)
        }
        historyContainer.addView(clearWrap)
    }

    /**
     * 区块标题：左侧暖棕竖条 + 主标题 + 副标题，杂志化层次
     */
    private fun buildSectionHeader(title: String, subtitle: String): LinearLayout {
        val density = resources.displayMetrics.density
        val wrap = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (28 * density).toInt()
                marginEnd = (28 * density).toInt()
                topMargin = (24 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        }
        // 左侧暖棕竖条（4dp × 18dp，圆角）
        val bar = View(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (4 * density).toInt(),
                (18 * density).toInt()
            ).apply { marginEnd = (10 * density).toInt() }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_section_bar)
        }
        val titleCol = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this@MainActivity).apply {
            text = title
            textSize = 17f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            includeFontPadding = false
        }
        val tvSub = TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            letterSpacing = 0.05f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)
        wrap.addView(bar)
        wrap.addView(titleCol)
        return wrap
    }

    /**
     * 历史/书架项的"书名"展示层的最后一道理防线：
     * 当 HistoryItem.title 被早期 bug 误存成 url.host（"www.sudugu.org"）或章节名时，
     * 不要把这种坏值原样吐给用户，否则列表看起来像"我读了一个网址"。
     * 渲染时把它替换成"未知书目"，并把 chapterTitle 顶上来作为主视觉锚点（如果有）。
     */
    private fun friendlyTitleFor(item: HistoryItem): String {
        if (looksLikeBadBookTitle(item)) {
            val chap = item.chapterTitle.trim()
            return if (chap.isNotEmpty()) "$chap（书名待修正）" else "未知书目"
        }
        return item.title.trim()
    }

    /**
     * 判定一条历史项的书名是否"不像正常书名"。
     * 命中以下任一即视为坏：
     *   - 空串 / 含 ://
     *   - 跟 url.host 完全一致
     *   - 像 URL 主机名（含 . 全 ASCII 无空格）
     *   - 像章节标题（以"第"开头且含"章/回/节"）——早期 bug 把章节名误存进了 title 字段
     */
    private fun looksLikeBadBookTitle(item: HistoryItem): Boolean {
        val t = item.title.trim()
        if (t.isEmpty()) return true
        if (t.contains("://")) return true
        val host = try { java.net.URI(item.url).host.orEmpty() } catch (_: Exception) { "" }
        if (host.isNotBlank() && t == host) return true
        if (t.contains('.') && !t.contains(' ') && t.all { it.isLetterOrDigit() || it in ".-" }) return true
        // 章节标题模式：以"第"开头 + 章节量词，避免一些异常或者翻译得不干净的书名误触（合法书名"第三帝国的兴亡"等极少见，这里判"第+数字+章/回/节"更稳）
        val chapterPattern = Regex("""^第[\s\u00A0]*[0-9\u4e00-\u9fff]{1,8}[章回节]""")
        if (chapterPattern.containsMatchIn(t)) return true
        return false
    }

    private fun rowSubtitle(item: HistoryItem): String {
        val base = when {
            item.totalPages > 0 -> "上次读到第 ${item.position + 1} / ${item.totalPages} 页"
            item.position > 0 -> "已读 ${item.position}%"
            else -> "点击继续阅读"
        }
        // 老坏数据：标题是 url.host 或章节标题拼接的临时退化。提示用户点"继续"重读一章即可自愈。
        return if (looksLikeBadBookTitle(item)) "$base · 书名待修正" else base
    }

    private fun makeBookRow(item: HistoryItem, subtitle: String, trailing: View, onRowClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_v2)
            elevation = 2 * density
            setPadding((14 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        // 左侧 hash 颜色封面（56x80dp，模拟真实书籍比例 7:10）
        val coverWidth = (56 * density).toInt()
        val coverHeight = (78 * density).toInt()
        val cover = FrameLayout(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(coverWidth, coverHeight).apply {
                marginEnd = (14 * density).toInt()
            }
        }
        // 底层：hash 色封面
        val coverBase = View(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.ColorDrawable(coverColorFor(item.bookKey))
        }
        // 中层：书脊侧条 + 顶部高光
        val coverSpine = View(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_cover_spine)
        }
        // 书名首字（衬线粗体 + 字母间距 + 柔光阴影，强化"书脊字"浮空感）
        // 用 friendlyTitleFor 兜底后的标题，避免坏数据("www.sudugu.org")把封面首字也变成'w'。
        val coverInitial = TextView(this@MainActivity).apply {
            text = friendlyTitleFor(item).firstOrNull()?.toString() ?: "书"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            alpha = 0.95f
            letterSpacing = 0.05f
            setPadding((6 * density).toInt(), 0, 0, 0)  // 平衡书脊侧条的视觉偏左
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            // 柔光阴影提升首字在彩色封面上的可读性与质感
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
        }
        cover.addView(coverBase)
        cover.addView(coverSpine)
        cover.addView(coverInitial)

        // 中间：标题 + 副标题 + 进度条
        val textLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this@MainActivity).apply {
            text = friendlyTitleFor(item)
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 当前所在章节（换章后随阅读进度更新，避免列表"看起来没更新"）
        // 当 friendlyTitleFor 已经把 chapterTitle 拼进 title 时，避免重复展示。
        val tvChapter = TextView(this@MainActivity).apply {
            text = item.chapterTitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3 * density).toInt(), 0, 0)
            visibility = if (looksLikeBadBookTitle(item) || item.chapterTitle.isBlank()) View.GONE else View.VISIBLE
        }
        val tvSub = TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        // 进度条
        val progressBar = FrameLayout(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
        }
        val track = View(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_progress_track_v2)
        }
        val progressFill = View(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_progress_fill_v2)
        }
        progressBar.addView(track)
        progressBar.addView(progressFill)
        // 渲染后异步设置 fill 宽度（依赖 track 的实际宽度）
        val fraction = progressFractionFor(item)
        progressBar.post {
            val target = (progressBar.width * fraction).toInt().coerceAtLeast(0)
            progressFill.layoutParams = FrameLayout.LayoutParams(
                target,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        textLayout.addView(tvTitle)
        textLayout.addView(tvChapter)
        textLayout.addView(tvSub)
        textLayout.addView(progressBar)

        row.addView(cover)
        row.addView(textLayout)
        row.addView(trailing)
        row.setOnClickListener { onRowClick() }
        return row
    }

    /**
     * 根据 bookKey 生成稳定的"封面"颜色（高饱和、中等亮度，在深空极光背景下更突出）
     */
    private fun coverColorFor(key: String): Int {
        val h = ((key.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        // 饱和度 0.55、亮度 0.5——鲜艳但不刺眼，白字首字保持高可读对比度
        return Color.HSVToColor(floatArrayOf(h, 0.55f, 0.5f))
    }

    /**
     * 阅读进度 0..1
     */
    private fun progressFractionFor(item: HistoryItem): Float {
        return when {
            item.totalPages > 0 -> (item.position.toFloat() / item.totalPages).coerceIn(0f, 1f)
            item.position > 0 -> (item.position / 100f).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /**
     * 空状态：圆角插画卡 + 主标题 + 副标题引导
     */
    private fun buildEmptyState(title: String, subtitle: String): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                topMargin = (12 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_empty_state)
            setPadding(
                (24 * density).toInt(),
                (32 * density).toInt(),
                (24 * density).toInt(),
                (32 * density).toInt()
            )

            val illustration = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_empty_openbook)
                layoutParams = LinearLayout.LayoutParams(
                    (160 * density).toInt(),
                    (160 * density).toInt()
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 霓虹青滤镜 + 柔光，让插画在深空玻璃卡上浮现
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
                imageTintList = null
            }
            val tvTitle = TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                setPadding(0, (18 * density).toInt(), 0, 0)
                gravity = Gravity.CENTER
            }
            val tvSub = TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
                setPadding(0, (6 * density).toInt(), 0, 0)
                gravity = Gravity.CENTER
                letterSpacing = 0.03f
            }
            addView(illustration)
            addView(tvTitle)
            addView(tvSub)
        }
    }

    private fun openBook(item: HistoryItem) {
        // 本地书（url 指向本应用 cacheDir 下的 localbook_*.txt）：直接以本地方式打开，
        // 若缓存文件已被系统清理则优雅降级（从书架移除并提示）。
        if (item.url.startsWith(cacheDir.absolutePath)) {
            val file = File(item.url)
            if (!file.exists()) {
                HistoryStore(this@MainActivity).removeFromShelf(item.bookKey)
                Toast.makeText(this@MainActivity, "本地文件已不在，已从书架移除", Toast.LENGTH_SHORT).show()
                refreshList()
                return
            }
            val intent = Intent(this@MainActivity, ReadingActivity::class.java).apply {
                putExtra(ReadingActivity.EXTRA_LOCAL_FILE, item.url)
                putExtra(ReadingActivity.EXTRA_LOCAL_TITLE, item.title)
                putExtra(ReadingActivity.EXTRA_LOCAL_CHAPTER, item.localChapter)
                // 续读定位所需的页码/百分比/阅读模式
                putExtra("EXTRA_POSITION", item.position)
                putExtra("EXTRA_TOTAL_PAGES", item.totalPages)
                putExtra("EXTRA_SCROLL_MODE", item.scrollMode)
            }
            startActivity(intent)
            return
        }
        val intent = Intent(this@MainActivity, ReadingActivity::class.java).apply {
            putExtra("EXTRA_URL", item.url)
            putExtra("EXTRA_NEXT_CHAPTER", item.nextChapterUrl ?: "")
            putExtra("EXTRA_POSITION", item.position)
            // 携带保存进度时的阅读模式与总页数，便于续读时正确解释 position
            putExtra("EXTRA_TOTAL_PAGES", item.totalPages)
            putExtra("EXTRA_SCROLL_MODE", item.scrollMode)
            // 关键：把 HistoryItem.title(书名) 作为 EXTRA_TITLE 一并传给 ReadingActivity，
            // 否则 ReadingActivity 拿到 url 后只能用章节名顶替书名，换源搜索会搜错。
            putExtra("EXTRA_TITLE", item.title)
            putExtra("EXTRA_BOOK_TITLE_FALLBACK", item.title)
        }
        startActivity(intent)
    }

    private fun showUrlInputDialog(presetUrl: String = "http://www.bookszw.com/156/156468/52087819.html") {
        val dialog = UrlInputDialog.newInstance(presetUrl)
        dialog.setOnLoadListener(
            onSubmit = { url ->
                loadUrlOnCurrentPage(url)
            }
        )
        dialog.show(supportFragmentManager, UrlInputDialog.TAG)
    }

    private fun loadUrlOnCurrentPage(url: String) {
        loadingView.visibility = View.VISIBLE
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadUrl(url)) {
                is UrlLoader.LoadResult.Success -> {
                    loadingView.visibility = View.GONE
                    val intent = Intent(this@MainActivity, ReadingActivity::class.java).apply {
                        putExtra("EXTRA_TITLE", result.title)
                        putExtra("EXTRA_CONTENT", result.content)
                        putExtra("EXTRA_URL", url)
                        putExtra("EXTRA_NEXT_CHAPTER", result.nextChapterUrl ?: "")
                        // 关键：从 HTML <title> 中提取的书名作为 fallback，避免历史里把章节名当书名
                        result.bookTitle?.takeIf { it.isNotBlank() }?.let {
                            putExtra("EXTRA_BOOK_TITLE_FALLBACK", it)
                        }
                    }
                    startActivity(intent)
                }
                is UrlLoader.LoadResult.Error -> {
                    loadingView.visibility = View.GONE
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkClipboardForNovelUrl() {
        val url = ClipboardHelper.detectNovelUrl(this) ?: return

        val lastShown = prefs.getString(KEY_LAST_CLIP_PROMPT, "")
        if (url == lastShown) return

        prefs.edit().putString(KEY_LAST_CLIP_PROMPT, url).apply()

        AlertDialog.Builder(this)
            .setTitle("检测到小说链接")
            .setMessage("剪贴板中检测到链接：\n$url\n\n是否作为小说打开？")
            .setPositiveButton("打开") { _, _ ->
                loadUrlOnCurrentPage(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 处理「打开方式 / 分享」传入的本地小说文件：解析后直接跳转到阅读页。
     */
    private fun handleIncomingOpenIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data
            else -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (uri == null) return

        // 防止配置变更（旋转屏幕）重建 Activity 时重复处理
        intent.action = null
        intent.data = null

        val name = resolveDisplayName(uri)
        loadingView.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val res = LocalBookLoader.load(this@MainActivity, uri, name)) {
                is LocalBookLoader.Result.Ok -> {
                    val store = HistoryStore(this@MainActivity)
                    // 读取已存的续读进度（章节 + 页码/百分比），避免每次从文件管理器打开
                    // 都重置回第 0 章第 0 页。本地书只存在于书架，按文件路径命中。
                    val existing = store.getShelf().firstOrNull { it.bookKey == res.filePath }
                    val resumeChapter = existing?.localChapter?.takeIf { it >= 0 } ?: 0
                    val resumePosition = existing?.position ?: 0
                    val resumeTotal = existing?.totalPages ?: 0
                    val resumeScroll = existing?.scrollMode ?: false
                    // 自动存入书架（按文件路径稳定标识，重复打开同一本小说去重置顶，不会重复）；
                    // 若已存在则沿用既有进度置顶，而非清零。
                    store.addToShelf(
                        HistoryItem(
                            title = res.title,
                            url = res.filePath,
                            bookKey = res.filePath,
                            nextChapterUrl = null,
                            position = resumePosition,
                            totalPages = resumeTotal,
                            chapterTitle = res.title,
                            localChapter = resumeChapter,
                            scrollMode = resumeScroll
                        )
                    )
                    val target = Intent(this@MainActivity, ReadingActivity::class.java).apply {
                        putExtra(ReadingActivity.EXTRA_LOCAL_FILE, res.filePath)
                        putExtra(ReadingActivity.EXTRA_LOCAL_TITLE, res.title)
                        // 续读：带上已保存的章节与页内位置，ReadingActivity 据此恢复阅读进度。
                        putExtra(ReadingActivity.EXTRA_LOCAL_CHAPTER, resumeChapter)
                        putExtra("EXTRA_POSITION", resumePosition)
                        putExtra("EXTRA_TOTAL_PAGES", resumeTotal)
                        putExtra("EXTRA_SCROLL_MODE", resumeScroll)
                    }
                    startActivity(target)
                    loadingView.visibility = View.GONE
                }
                is LocalBookLoader.Result.Err -> {
                    loadingView.visibility = View.GONE
                    Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        if (name.isBlank()) name = "本地小说"
        if (uri.scheme == "content") {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                }
            } catch (_: Exception) {
                // 部分 content provider 不提供显示名，退回使用 lastPathSegment
            }
        }
        return name
    }

    companion object {
        private const val KEY_LAST_CLIP_PROMPT = "last_clip_prompt"
    }
}
