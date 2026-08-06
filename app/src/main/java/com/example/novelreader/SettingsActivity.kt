package com.example.novelreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

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
        setupLineSpacing()
        setupScrollMode()
        setupFontFamily()
        setupGesture()
        setupGestureSensitivity()
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
        val buttons = listOf(
            findViewById<TextView>(R.id.font_family_default),
            findViewById<TextView>(R.id.font_family_sans),
            findViewById<TextView>(R.id.font_family_serif),
            findViewById<TextView>(R.id.font_family_mono)
        )

        fun updateSelection() {
            val current = settings.fontFamily
            buttons.forEachIndexed { idx, btn ->
                val selected = idx == current
                btn.setBackgroundResource(
                    if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip_neutral
                )
                btn.setTextColor(
                    if (selected) ContextCompat.getColor(this@SettingsActivity, R.color.textOnPrimary)
                    else ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary)
                )
            }
        }

        updateSelection()
        buttons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                settings.fontFamily = idx
                updateSelection()
                applyPreview()
            }
        }
    }

    private fun setupGestureSensitivity() {
        val lowBtn = findViewById<TextView>(R.id.gesture_sensitivity_low)
        val mediumBtn = findViewById<TextView>(R.id.gesture_sensitivity_medium)
        val highBtn = findViewById<TextView>(R.id.gesture_sensitivity_high)

        fun updateSelection() {
            val level = settings.gestureSensitivity
            listOf(lowBtn, mediumBtn, highBtn).forEachIndexed { idx, btn ->
                val selected = idx == level
                btn.setBackgroundResource(
                    if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip_neutral
                )
                btn.setTextColor(
                    if (selected) ContextCompat.getColor(this@SettingsActivity, R.color.textOnPrimary)
                    else ContextCompat.getColor(this@SettingsActivity, R.color.colorPrimary)
                )
            }
        }

        updateSelection()
        lowBtn.setOnClickListener { settings.gestureSensitivity = ReadingSettings.SENSITIVITY_LOW; updateSelection() }
        mediumBtn.setOnClickListener { settings.gestureSensitivity = ReadingSettings.SENSITIVITY_MEDIUM; updateSelection() }
        highBtn.setOnClickListener { settings.gestureSensitivity = ReadingSettings.SENSITIVITY_HIGH; updateSelection() }
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

    private fun applyPreview() {
        val theme = BackgroundTheme.fromId(settings.backgroundTheme)
        preview.textSize = settings.fontSize.toFloat()
        preview.setLineSpacing(0f, settings.lineSpacing)
        preview.typeface = ReadingSettings.resolveTypeface(settings.fontFamily)
        preview.setTextColor(theme.textColor)
        preview.setBackgroundColor(theme.backgroundColor)
    }
}
