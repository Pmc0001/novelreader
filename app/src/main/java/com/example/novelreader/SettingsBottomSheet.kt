package com.example.novelreader

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var onSettingsChanged: ((SettingsAction) -> Unit)? = null
    private var currentFontSize: Int = 18
    private var currentTheme: Int = ReadingSettings.THEME_WHITE
    private var currentBrightness: Int = 50

    sealed class SettingsAction {
        data class FontSizeChanged(val size: Int) : SettingsAction()
        data class ThemeChanged(val themeId: Int) : SettingsAction()
        data class BrightnessChanged(val brightness: Int) : SettingsAction()
    }

    fun setOnSettingsChangedListener(listener: (SettingsAction) -> Unit) {
        onSettingsChanged = listener
    }

    fun updateSettings(fontSize: Int, themeId: Int, brightness: Int) {
        currentFontSize = fontSize
        currentTheme = themeId
        currentBrightness = brightness
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.layout_settings_panel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFontSizeControl(view)
        setupBrightnessControl(view)
        setupBackgroundSelector(view)
    }

    private fun setupFontSizeControl(view: View) {
        val tvFontSize = view.findViewById<TextView>(R.id.tv_font_size_value)
        val btnFontMinus = view.findViewById<TextView>(R.id.btn_font_minus)
        val btnFontPlus = view.findViewById<TextView>(R.id.btn_font_plus)

        tvFontSize.text = "${currentFontSize}sp"

        btnFontMinus.setOnClickListener {
            if (currentFontSize > ReadingSettings.FONT_SIZE_MIN) {
                currentFontSize -= ReadingSettings.FONT_SIZE_STEP
                tvFontSize.text = "${currentFontSize}sp"
                onSettingsChanged?.invoke(SettingsAction.FontSizeChanged(currentFontSize))
            }
        }

        btnFontPlus.setOnClickListener {
            if (currentFontSize < ReadingSettings.FONT_SIZE_MAX) {
                currentFontSize += ReadingSettings.FONT_SIZE_STEP
                tvFontSize.text = "${currentFontSize}sp"
                onSettingsChanged?.invoke(SettingsAction.FontSizeChanged(currentFontSize))
            }
        }
    }

    private fun setupBrightnessControl(view: View) {
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar_brightness)
        val tvBrightness = view.findViewById<TextView>(R.id.tv_brightness_value)

        seekBar.progress = currentBrightness
        tvBrightness.text = if (currentBrightness == 50) "自动" else "$currentBrightness%"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBrightness = progress
                tvBrightness.text = if (progress == 50) "自动" else "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onSettingsChanged?.invoke(SettingsAction.BrightnessChanged(currentBrightness))
            }
        })
    }

    private fun setupBackgroundSelector(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.ll_background_selector)
        container.removeAllViews()

        val themes = BackgroundTheme.entries
        val density = resources.displayMetrics.density

        for (theme in themes) {
            val themeView = createThemeItem(theme, density)
            container.addView(themeView)
        }
    }

    private fun createThemeItem(theme: BackgroundTheme, density: Float): View {
        val itemLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val circleSize = (36 * density).toInt()
        val circleView = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_theme_circle_refined)
            backgroundTintList = android.content.res.ColorStateList.valueOf(theme.previewColor)
            elevation = 2 * density

            if (theme.id == currentTheme) {
                alpha = 1f
            } else {
                alpha = 0.7f
            }
        }

        val borderSize = (44 * density).toInt()
        val borderView = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(borderSize, borderSize)
            background = if (theme.id == currentTheme) {
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_theme_border_selected)
            } else {
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_theme_border_normal)
            }
            setPadding(2 * density.toInt(), 2 * density.toInt(), 2 * density.toInt(), 2 * density.toInt())
        }
        borderView.addView(circleView)

        val nameText = TextView(requireContext()).apply {
            text = theme.displayName
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), R.color.textOnPrimary))
            setPadding(0, (4 * density).toInt(), 0, 0)
        }

        itemLayout.addView(borderView)
        itemLayout.addView(nameText)

        itemLayout.setOnClickListener {
            currentTheme = theme.id
            onSettingsChanged?.invoke(SettingsAction.ThemeChanged(theme.id))
            setupBackgroundSelector(requireView())
        }

        return itemLayout
    }

    companion object {
        const val TAG = "SettingsBottomSheet"

        fun newInstance(): SettingsBottomSheet {
            return SettingsBottomSheet()
        }
    }
}
