package com.example.novelreader

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    // 栏背景颜色预设（均为暗色，保证白字/浅色图标可读）
    data class BarColorPreset(val name: String, val color: Int)

    companion object {
        // 28 个预设暗色栏背景（均保证白字/浅色图标可读）；第一格"跟随主题"=-1
        val BAR_COLOR_PRESETS = listOf(
            BarColorPreset("跟随主题", -1),
            // 暗色系
            BarColorPreset("极夜黑", Color.parseColor("#0A0A1F")),
            BarColorPreset("暮蓝黑", Color.parseColor("#1A1A2E")),
            BarColorPreset("墨灰", Color.parseColor("#1C1C1C")),
            BarColorPreset("炭灰", Color.parseColor("#2A2A2A")),
            BarColorPreset("铅灰", Color.parseColor("#2D2D2D")),
            // 暖棕系
            BarColorPreset("暖棕", Color.parseColor("#3A2818")),
            BarColorPreset("复古棕", Color.parseColor("#3D2F1A")),
            BarColorPreset("咖啡棕", Color.parseColor("#3D2510")),
            BarColorPreset("砂岩棕", Color.parseColor("#2D2519")),
            BarColorPreset("牛皮棕", Color.parseColor("#3D3319")),
            BarColorPreset("焦糖棕", Color.parseColor("#4A2F1A")),
            BarColorPreset("暖巧棕", Color.parseColor("#5D3A1A")),
            // 赤褐系
            BarColorPreset("赤褐", Color.parseColor("#3D1F0F")),
            BarColorPreset("酒红", Color.parseColor("#3D1818")),
            BarColorPreset("红木", Color.parseColor("#3D1A1A")),
            // 蓝色系
            BarColorPreset("靛蓝", Color.parseColor("#1F2D40")),
            BarColorPreset("深海蓝", Color.parseColor("#1A2538")),
            BarColorPreset("钴蓝", Color.parseColor("#1B2D4A")),
            // 绿/青系
            BarColorPreset("墨绿", Color.parseColor("#1F3325")),
            BarColorPreset("森林绿", Color.parseColor("#1A3020")),
            BarColorPreset("深松绿", Color.parseColor("#1F3329")),
            BarColorPreset("暗青", Color.parseColor("#1A3035")),
            // 紫/梅系
            BarColorPreset("暗紫", Color.parseColor("#2A1F38")),
            BarColorPreset("深茄紫", Color.parseColor("#2D2035")),
            BarColorPreset("梅紫", Color.parseColor("#2A1A2E")),
            // 金色系
            BarColorPreset("橄榄棕", Color.parseColor("#3D3528")),
            BarColorPreset("暗金", Color.parseColor("#3D3020"))
        )
    }

    private lateinit var settings: ReadingSettings
    private lateinit var preview: TextView
    private lateinit var tvFontSize: TextView
    private lateinit var swatchContainer: ViewGroup

    private val themeGroups = listOf(
        ThemeCategory.WARM to "暖色调",
        ThemeCategory.COOL to "清新色",
        ThemeCategory.NIGHT to "夜间"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        val statusBarSpacer = findViewById<View>(R.id.status_bar_spacer)
        ViewCompat.setOnApplyWindowInsetsListener(statusBarSpacer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams
            lp.height = systemBars.top
            v.layoutParams = lp
            insets
        }

        settings = ReadingSettings(this)
        preview = findViewById(R.id.preview_text)
        tvFontSize = findViewById(R.id.tv_font_size_value)
        swatchContainer = findViewById(R.id.swatch_container)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        setupFontSize()
        // 等布局完成后再填色块，确保能拿到 swatchContainer 的真实宽度
        swatchContainer.post { setupWarmBackground() }
        // 栏背景颜色面板
        val barContainer = findViewById<ViewGroup>(R.id.bar_color_swatch_container)
        barContainer.post { setupBarColorPalette() }
        setupLineSpacing()
        setupScrollMode()
        setupFontFamily()
        setupGesture()
        setupCacheManagement()
        applyPreview()
    }

    private fun setupFontSize() {
        tvFontSize.text = "${settings.fontSize}sp"
        findViewById<TextView>(R.id.btn_font_minus).setOnClickListener {
            if (settings.fontSize > ReadingSettings.FONT_SIZE_MIN) {
                settings.fontSize -= ReadingSettings.FONT_SIZE_STEP
                tvFontSize.text = "${settings.fontSize}sp"
                applyPreview()
            }
        }
        findViewById<TextView>(R.id.btn_font_plus).setOnClickListener {
            if (settings.fontSize < ReadingSettings.FONT_SIZE_MAX) {
                settings.fontSize += ReadingSettings.FONT_SIZE_STEP
                tvFontSize.text = "${settings.fontSize}sp"
                applyPreview()
            }
        }
    }

    private fun setupWarmBackground() {
        val currentTheme = settings.backgroundTheme
        val inflater = layoutInflater
        val density = resources.displayMetrics.density
        val perRow = 5
        val gap = (8 * density).toInt()
        // 动态计算 cardSize：每行 N 个 + N 个间距 刚好填满容器可用宽度
        val containerWidthPx = if (swatchContainer.width > 0) swatchContainer.width
        else resources.displayMetrics.widthPixels
        val containerWidthDp = containerWidthPx / density
        val cardSizeDp = ((containerWidthDp - perRow * 8f) / perRow).coerceAtLeast(40f)
        val cardSize = (cardSizeDp * density).toInt()
        val allViews = mutableListOf<View>()
        val allIds = mutableListOf<Int>()

        for ((category, title) in themeGroups) {
            val themes = BackgroundTheme.entries.filter { it.category == category }
            if (themes.isEmpty()) continue

            val titleView = TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, if (swatchContainer.childCount > 0) gap * 2 else 0, 0, gap)
                layoutParams = lp
            }
            swatchContainer.addView(titleView)

            var row: LinearLayout? = null
            themes.forEachIndexed { index, theme ->
                if (index % perRow == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.bottomMargin = gap
                        layoutParams = lp
                    }
                    swatchContainer.addView(row)
                }
                val item = inflater.inflate(R.layout.item_color_swatch, row, false)
                val swatch = item.findViewById<View>(R.id.swatch)
                swatch.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(theme.backgroundColor)
                item.isSelected = theme.id == currentTheme
                updateSelectionUI(item)
                item.layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
                    setMargins(gap / 2, 0, gap / 2, 0)
                }
                item.setOnClickListener {
                    settings.backgroundTheme = theme.id
                    // 选择非夜间主题时，记录为“上次浅色主题”，保证从夜间切回时恢复此颜色
                    if (theme.category != ThemeCategory.NIGHT) {
                        settings.lastLightTheme = theme.id
                    }
                    allViews.forEachIndexed { i, v ->
                        v.isSelected = allIds[i] == theme.id
                        updateSelectionUI(v)
                    }
                    applyPreview()
                }
                allViews.add(item)
                allIds.add(theme.id)
                row!!.addView(item)
            }
        }
    }

    private fun setupGesture() {
        val switchGesture = findViewById<SwitchCompat>(R.id.switch_gesture)
        switchGesture.isChecked = settings.gestureEnabled
        switchGesture.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                settings.gestureEnabled = false
                switchGesture.isChecked = false
                android.widget.Toast.makeText(this, "设备没有摄像头，无法使用手势翻页", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                settings.gestureEnabled = isChecked
            }
        }
    }

    private fun setupLineSpacing() {
        val seekBar = findViewById<android.widget.SeekBar>(R.id.seekbar_line_spacing)
        val tvValue = findViewById<TextView>(R.id.tv_line_spacing_value)
        val initial = (settings.lineSpacing * 10f).roundToInt().coerceIn(10, 25)
        seekBar.progress = initial
        tvValue.text = String.format("%.1f", settings.lineSpacing)
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.1f", progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                settings.lineSpacing = (seekBar?.progress ?: initial) / 10f
                applyPreview()
            }
        })
    }

    private fun setupScrollMode() {
        val switchScroll = findViewById<SwitchCompat>(R.id.switch_scroll_mode)
        switchScroll.isChecked = settings.scrollMode
        switchScroll.setOnCheckedChangeListener { _, isChecked ->
            settings.scrollMode = isChecked
        }
    }

    private fun setupFontFamily() {
        val container = findViewById<LinearLayout>(R.id.font_family_group)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val perRow = 4
        val options = ReadingSettings.FONT_OPTIONS
        val buttons = mutableListOf<TextView>()

        fun updateSelection() {
            val current = settings.fontFamily
            buttons.forEach { btn ->
                val selected = (btn.tag as? Int) == current
                btn.setBackgroundResource(
                    if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip_neutral
                )
                btn.setTextColor(
                    if (selected) ContextCompat.getColor(this@SettingsActivity, R.color.textOnPrimary)
                    else ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary)
                )
            }
        }

        var row: LinearLayout? = null
        options.forEachIndexed { index, opt ->
            if (index % perRow == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * density).toInt() }
                }
                container.addView(row)
            }
            val btn = TextView(this).apply {
                text = opt.name
                textSize = 14f
                // 用对应字体渲染按钮文字，用户可直观预览字体效果
                typeface = opt.typeface
                gravity = Gravity.CENTER
                tag = opt.id
                layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                    marginStart = if (index % perRow == 0) 0 else (3 * density).toInt()
                    marginEnd = if ((index + 1) % perRow == 0) 0 else (6 * density).toInt()
                }
            }
            btn.setOnClickListener {
                settings.fontFamily = opt.id
                updateSelection()
                applyPreview()
            }
            buttons.add(btn)
            row!!.addView(btn)
        }

        updateSelection()
    }

    private fun setupBarColorPalette() {
        val container = findViewById<ViewGroup>(R.id.bar_color_swatch_container)
        val hint = findViewById<TextView>(R.id.tv_bar_color_hint)
        val currentColor = settings.customBarColor
        val inflater = layoutInflater
        val density = resources.displayMetrics.density
        val perRow = 5
        val gap = (8 * density).toInt()
        val containerWidthPx = if (container.width > 0) container.width
        else resources.displayMetrics.widthPixels
        val containerWidthDp = containerWidthPx / density
        val cardSizeDp = ((containerWidthDp - perRow * 8f) / perRow).coerceAtLeast(40f)
        val cardSize = (cardSizeDp * density).toInt()
        val allViews = mutableListOf<View>()
        val allColors = mutableListOf<Int>()

        fun updateHint() {
            val sel = settings.customBarColor
            val preset = BAR_COLOR_PRESETS.find { it.color == sel }
            hint.text = when {
                sel == -1 || preset == null && sel == -1 -> "当前：跟随主题默认"
                preset != null -> "当前：${preset.name}"
                else -> "当前：自定义 ${String.format("#%06X", 0xFFFFFF and sel)}"
            }
        }
        updateHint()

        var row: LinearLayout? = null
        BAR_COLOR_PRESETS.forEachIndexed { index, preset ->
            if (index % perRow == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = gap
                    layoutParams = lp
                }
                container.addView(row)
            }
            if (preset.color == -1) {
                // 跟随主题：单独用 TextView + 虚线圆背景，"A" 字暗示 auto
                val auto = TextView(this).apply {
                    text = "A"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                    background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_swatch_auto)
                    isSelected = preset.color == currentColor
                    updateSelectionUI(this)
                    layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
                        setMargins(gap / 2, 0, gap / 2, 0)
                    }
                }
                auto.setOnClickListener {
                    settings.customBarColor = preset.color
                    allViews.forEachIndexed { i, v ->
                        v.isSelected = allColors[i] == preset.color
                        updateSelectionUI(v)
                    }
                    updateHint()
                }
                allViews.add(auto)
                allColors.add(preset.color)
                row!!.addView(auto)
            } else {
                val item = inflater.inflate(R.layout.item_color_swatch, row, false)
                val swatch = item.findViewById<View>(R.id.swatch)
                swatch.backgroundTintList = ColorStateList.valueOf(preset.color)
                item.isSelected = preset.color == currentColor
                updateSelectionUI(item)
                item.layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
                    setMargins(gap / 2, 0, gap / 2, 0)
                }
                item.setOnClickListener {
                    settings.customBarColor = preset.color
                    allViews.forEachIndexed { i, v ->
                        v.isSelected = allColors[i] == preset.color
                        updateSelectionUI(v)
                    }
                    updateHint()
                }
                allViews.add(item)
                allColors.add(preset.color)
                row!!.addView(item)
            }
        }

        // ── 自定义颜色入口 ──
        addCustomColorEntry(container, allViews, allColors, hint, inflater, density, gap, perRow, cardSize)
    }

    private fun updateSelectionUI(item: View) {
        val tint = if (item.isSelected) {
            ContextCompat.getColor(this, R.color.colorPrimary)
        } else {
            android.graphics.Color.TRANSPARENT
        }
        item.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        item.alpha = if (item.isSelected) 1f else 0.6f
    }

    /**
     * 在色块列表末尾追加"自定义颜色"入口卡片，点击打开 [ColorPickerDialog]。
     */
    private fun addCustomColorEntry(
        container: ViewGroup,
        allViews: MutableList<View>,
        allColors: MutableList<Int>,
        hint: TextView,
        inflater: android.view.LayoutInflater,
        density: Float,
        gap: Int,
        perRow: Int,
        cardSize: Int
    ) {
        // 补齐当前行（若最后一行未满 5 格，先换行保证自定义卡片独占一行）
        val remainder = BAR_COLOR_PRESETS.size % perRow
        if (remainder != 0) {
            // 已有行已创建，只要在末尾追加空占位以保证布局规整
            // 前面的 forEachIndexed 已经创建了当前行，无需额外操作
            // 自定义卡片放在新起的一行
        }

        // 新建一行放自定义卡片
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = gap
            layoutParams = lp
        }

        val isCustomSelected = settings.customBarColor != -1 &&
                BAR_COLOR_PRESETS.none { it.color == settings.customBarColor }

        val card = inflater.inflate(R.layout.item_color_swatch, row, false)
        // 自定义卡片显示为虚线边框 + 中心 "＋" 图样
        val swatch = card.findViewById<View>(R.id.swatch)
        swatch.background = createCustomSwatchBackground(density, isCustomSelected)
        card.isSelected = isCustomSelected
        updateSelectionUI(card)
        card.layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
            setMargins(gap / 2, 0, gap / 2, 0)
        }
        card.setOnClickListener {
            val currentBarColor = settings.customBarColor
            val initColor = if (currentBarColor != -1) currentBarColor
            else Color.parseColor("#2D2D2D") // 默认铅灰

            ColorPickerDialog(this, initColor) { chosen ->
                settings.customBarColor = chosen
                // 取消所有预设选中
                allViews.forEachIndexed { i, v ->
                    v.isSelected = false
                    updateSelectionUI(v)
                }
                // 标记自定义卡片为选中
                card.isSelected = true
                updateSelectionUI(card)
                // 更新自定义卡片外观（显示选中颜色）
                swatch.background = createCustomSwatchBackground(density, true)
                updateHint(hint)
            }.show()
        }
        allViews.add(card)
        allColors.add(-2) // 特殊标记：自定义
        row.addView(card)

        container.addView(row)
    }

    /** 自定义色块背景：虚线边框 + 中心"＋"图标，选中时填充当前颜色。 */
    private fun createCustomSwatchBackground(density: Float, selected: Boolean): android.graphics.drawable.Drawable {
        val r = (6 * density).toInt()
        val strokeW = (2 * density).toInt()
        val gapDp = (4 * density).toInt()

        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = r.toFloat()
            if (selected) {
                val sel = settings.customBarColor
                setColor(if (sel != -1) sel else Color.parseColor("#3A3A4E"))
                setStroke(strokeW, ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary))
            } else {
                setColor(Color.parseColor("#2A2A3E"))
                setStroke(strokeW, ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                // 虚线效果用 dash
                setStroke(strokeW, ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary),
                    gapDp.toFloat(), gapDp.toFloat())
            }
        }
        return shape
    }

    private fun updateHint(hint: TextView) {
        val sel = settings.customBarColor
        val preset = BAR_COLOR_PRESETS.find { it.color == sel }
        hint.text = when {
            sel == -1 -> "当前：跟随主题默认"
            preset != null -> "当前：${preset.name}"
            else -> "当前：自定义 ${String.format("#%06X", 0xFFFFFF and sel)}"
        }
    }

    private fun applyPreview() {
        val theme = BackgroundTheme.fromId(settings.backgroundTheme)
        preview.textSize = settings.fontSize.toFloat()
        preview.setLineSpacing(0f, settings.lineSpacing)
        preview.typeface = ReadingSettings.resolveTypeface(settings.fontFamily)
        preview.setTextColor(theme.textColor)
        preview.setBackgroundColor(theme.backgroundColor)
    }

    // ===== 缓存管理 =====

    private var tvCacheInfo: TextView? = null
    private var cacheBookList: LinearLayout? = null

    private fun setupCacheManagement() {
        val tvInfo = findViewById<TextView>(R.id.tv_cache_info)
        tvCacheInfo = tvInfo
        cacheBookList = findViewById(R.id.cache_book_list)
        val btnManage = findViewById<TextView>(R.id.btn_manage_cache)
        val btnClear = findViewById<TextView>(R.id.btn_clear_cache)

        refreshCacheInfo()

        btnManage.setOnClickListener { showCacheManageDialog() }

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空全部离线缓存")
                .setMessage("确定要清空所有已缓存的章节吗？\n\n清空后，离线时将无法阅读未缓存的内容。")
                .setPositiveButton("清空") { _, _ ->
                    ChapterCache.clearAll(this)
                    refreshCacheInfo()
                    Toast.makeText(this, "已清空所有缓存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshCacheInfo() {
        val stats = ChapterCache.listCachedBooks(this)
        val totalChapters = stats.sumOf { it.chapterCount }
        val totalBytes = stats.sumOf { it.totalSize }
        val text = if (totalChapters == 0) "暂无缓存数据"
        else "共 ${stats.size} 本书 · $totalChapters 章 · ${formatSize(totalBytes)}"
        tvCacheInfo?.text = text

        // 按书展示内联概览列表
        populateCacheBookList(stats)
    }

    /** 在缓存卡片内直接展示每本书的缓存概况，无需点击进入对话框 */
    private fun populateCacheBookList(stats: List<ChapterCache.BookCacheStats>) {
        val container = cacheBookList ?: return
        container.removeAllViews()

        if (stats.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val store = HistoryStore(this)
        val density = resources.displayMetrics.density
        val totalChapters = stats.sumOf { it.chapterCount }

        for (book in stats) {
            val title = resolveBookTitle(book.bookKey, store)
            val ratio = if (totalChapters > 0) book.chapterCount.toFloat() / totalChapters.toFloat() else 0f

            // 摘要行（可点击展开章节）
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * density).toInt() }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            textCol.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(this).apply {
                text = "${book.chapterCount} 章 · ${formatSize(book.totalSize)}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                maxLines = 1
            })

            row.addView(textCol)

            // 小进度条（相对于总缓存占比）
            val barWidth = (40 * density).toInt()
            val barHeight = (3 * density).toInt()
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(barWidth, barHeight).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            bar.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary))
                layoutParams = LinearLayout.LayoutParams(0, barHeight, ratio.coerceAtLeast(0.02f))
            })
            bar.addView(View(this).apply {
                setBackgroundColor(
                    ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary).let { it and 0x00FFFFFF or 0x22000000 }
                )
                layoutParams = LinearLayout.LayoutParams(0, barHeight, (1f - ratio).coerceAtLeast(0.01f))
            })
            row.addView(bar)

            // 右侧箭头（提示可展开）
            val arrow = TextView(this).apply {
                text = "▸"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(arrow)

            container.addView(row)

            // 展开/折叠的章节列表（默认隐藏）
            val expandArea = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(expandArea)

            // 点击行 → 展开/收起章节列表
            row.setOnClickListener {
                if (expandArea.visibility == View.VISIBLE) {
                    expandArea.visibility = View.GONE
                    arrow.text = "▸"
                } else {
                    expandArea.visibility = View.VISIBLE
                    arrow.text = "▾"
                    // 首次展开才填充章节（避免重复读取）
                    if (expandArea.childCount == 0) {
                        populateChapterList(expandArea, book.bookKey, title)
                    }
                }
            }

            // 细分隔线
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).apply { topMargin = (6 * density).toInt() }
                setBackgroundColor(
                    ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary).let { it and 0x00FFFFFF or 0x18000000 }
                )
            })
        }
    }

    /** 填充某本书的缓存章节标题到展开区域 */
    private fun populateChapterList(container: LinearLayout, bookKey: String, bookTitle: String) {
        val density = resources.displayMetrics.density
        val chapters = try {
            ChapterCache(this, bookKey).listChapterInfos()
        } catch (_: Exception) { emptyList() }

        // 顶部小提示
        container.addView(TextView(this).apply {
            text = "《${bookTitle}》已缓存章节（共 ${chapters.size} 章）"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
            setPadding((8 * density).toInt(), (4 * density).toInt(), 0, (6 * density).toInt())
        })

        if (chapters.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无缓存章节"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                setPadding((8 * density).toInt(), 0, 0, (8 * density).toInt())
            })
            return
        }

        // 章节列表（最多展示前 60 章 + 提示，更多可以点进管理对话框查看全部）
        val showLimit = 60
        val visibleChapters = chapters.take(showLimit)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        visibleChapters.forEachIndexed { idx, ch ->
            listContainer.addView(TextView(this).apply {
                text = "${idx + 1}. ${ch.title}"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
                setPadding((4 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (3 * density).toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        // 如果缓存章节超过显示上限，加一条提示
        if (chapters.size > showLimit) {
            listContainer.addView(TextView(this).apply {
                text = "...还有 ${chapters.size - showLimit} 章未显示（点击「管理缓存」查看全部）"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
                setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            })
        }
        container.addView(listContainer)
    }

    /** 缓存管理对话框 —— 按书分组展示，支持单本清空 */
    private fun showCacheManageDialog() {
        val stats = ChapterCache.listCachedBooks(this)
        if (stats.isEmpty()) {
            Toast.makeText(this, "暂无缓存数据", Toast.LENGTH_SHORT).show()
            return
        }

        val store = HistoryStore(this)
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
        }

        for (book in stats) {
            val title = resolveBookTitle(book.bookKey, store)
            val subtitle = "${book.chapterCount} 章 · ${formatSize(book.totalSize)}"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            textCol.addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textSecondary))
            })

            val btnDel = TextView(this).apply {
                text = "清空"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary))
                setPadding(
                    (14 * density).toInt(), (6 * density).toInt(),
                    (14 * density).toInt(), (6 * density).toInt()
                )
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_chip_neutral)
                setOnClickListener {
                    ChapterCache(this@SettingsActivity, book.bookKey).clear()
                    refreshCacheInfo()
                    Toast.makeText(this@SettingsActivity, "已清空《${title}》的缓存", Toast.LENGTH_SHORT).show()
                    (it.parent.parent as? ViewGroup)?.removeView(it.parent as View)
                }
            }

            row.addView(textCol)
            row.addView(btnDel)
            // 点书行 → 展开该书的章节列表（避开"清空"按钮）
            row.setOnClickListener { showChapterListDialog(book, title) }
            container.addView(row)

            // 分隔线
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).apply { topMargin = (8 * density).toInt() }
                setBackgroundColor(0x1A000000)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("缓存管理")
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 展示某本书已缓存的所有章节标题列表 */
    private fun showChapterListDialog(book: ChapterCache.BookCacheStats, bookTitle: String) {
        val cache = ChapterCache(this, book.bookKey)
        val chapters = cache.listChapterInfos()
        if (chapters.isEmpty()) {
            Toast.makeText(this, "该书暂无缓存章节", Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
        }
        chapters.forEachIndexed { idx, ch ->
            list.addView(TextView(this).apply {
                text = "${idx + 1}. ${ch.title}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textPrimary))
                setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(list)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()
            )
        }
        AlertDialog.Builder(this)
            .setTitle("《${bookTitle}》缓存章节（${chapters.size}）")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 从 bookKey 解析可读书名：优先查历史/书架，退化为域名+路径 */
    private fun resolveBookTitle(bookKey: String, store: HistoryStore): String {
        fun looksLikeUrl(s: String) = s.contains("://") || s.startsWith("www.")
        // 查历史
        val hist = store.getHistory().firstOrNull { it.bookKey == bookKey }
        if (hist != null && hist.title.isNotBlank() && !looksLikeUrl(hist.title)) return hist.title
        // 查书架
        val shelf = store.getShelf().firstOrNull { it.bookKey == bookKey }
        if (shelf != null && shelf.title.isNotBlank() && !looksLikeUrl(shelf.title)) return shelf.title
        // 退化：先从缓存章节的真实 URL 提取，再尝试 Uri.parse，最后退到 bookKey 截取
        val firstUrl = try {
            ChapterCache(this, bookKey).listChapterInfos().firstOrNull()?.url
        } catch (_: Exception) { null }
        return fallbackTitle(bookKey, firstUrl)
    }

    /**
     * 当历史/书架都查不到书名时的降级显示策略：
     * 1. 优先解析章节 URL，拿到域名 + 路径前两段作为可读标识
     * 2. 用正则从 bookKey 里硬抠出 host（处理 Uri.parse 在单斜杠下解析失败的情况）
     * 3. 最后才截取 bookKey 前 30 字符兜底
     */
    private fun fallbackTitle(bookKey: String, hintUrl: String?): String {
        val raw = hintUrl ?: bookKey
        // 用正则提取 host（不依赖 Uri.parse，能兼容 "//" 与 "/" 两种情况）
        val hostMatch = Regex("""(?:https?)?:?//([^/]+)""").find(raw)
        val host = hostMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return bookKey.take(30)
        // 提取路径前两段（去掉域名段）
        val pathMatch = Regex("""^[a-z]+://[^/]+(/[^?#]*)""").find(raw)
        val path = pathMatch?.groupValues?.getOrNull(1)?.trimEnd('/').orEmpty()
        val segments = path.split('/').filter { it.isNotBlank() }.take(2)
        val shortPath = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
        return host + shortPath
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
