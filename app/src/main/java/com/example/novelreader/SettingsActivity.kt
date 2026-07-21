package com.example.novelreader

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: ReadingSettings
    private lateinit var preview: TextView
    private lateinit var tvFontSize: TextView
    private lateinit var swatchContainer: ViewGroup

    private val warmThemes = listOf(
        BackgroundTheme.WARM_WHITE,
        BackgroundTheme.WARM_YELLOW,
        BackgroundTheme.CREAM,
        BackgroundTheme.APRICOT,
        BackgroundTheme.PEACH,
        BackgroundTheme.ROSE,
        BackgroundTheme.SANDSTONE
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
        setupWarmBackground()
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
        for (theme in warmThemes) {
            val item = inflater.inflate(R.layout.item_color_swatch, swatchContainer, false)
            val swatch = item.findViewById<View>(R.id.swatch)
            swatch.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.backgroundColor)
            item.isSelected = theme.id == currentTheme
            updateSelectionUI(item)
            item.setOnClickListener {
                settings.backgroundTheme = theme.id
                for (i in 0 until swatchContainer.childCount) {
                    swatchContainer.getChildAt(i).isSelected = (i == swatchContainer.indexOfChild(item))
                    updateSelectionUI(swatchContainer.getChildAt(i))
                }
                applyPreview()
            }
            swatchContainer.addView(item)
        }
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
        preview.setTextColor(theme.textColor)
        preview.setBackgroundColor(theme.backgroundColor)
    }
}
