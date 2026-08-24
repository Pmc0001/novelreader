package com.example.novelreader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface

class ReadingSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading_settings", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 18)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    var backgroundTheme: Int
        get() = prefs.getInt(KEY_BACKGROUND_THEME, THEME_WHITE)
        set(value) = prefs.edit().putInt(KEY_BACKGROUND_THEME, value).apply()

    // 进入夜间模式前记录用户当前的浅色主题，切回浅色时恢复到此主题（而非固定白色）
    var lastLightTheme: Int
        get() = prefs.getInt(KEY_LAST_LIGHT_THEME, THEME_WHITE)
        set(value) = prefs.edit().putInt(KEY_LAST_LIGHT_THEME, value).apply()

    var lineSpacing: Float
        get() = prefs.getFloat(KEY_LINE_SPACING, 1.6f)
        set(value) = prefs.edit().putFloat(KEY_LINE_SPACING, value).apply()

    var brightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, -1)
        set(value) = prefs.edit().putInt(KEY_BRIGHTNESS, value).apply()

    var lastReadPosition: Int
        get() = prefs.getInt(KEY_LAST_POSITION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_POSITION, value).apply()

    var gestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_ENABLED, value).apply()

    // 滚动阅读模式：true=连续滚动，false=翻页（默认翻页）
    var scrollMode: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()

    // 正文字体：默认/黑体/宋体/等宽（默认系统默认）
    var fontFamily: Int
        get() = prefs.getInt(KEY_FONT_FAMILY, FONT_FAMILY_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_FONT_FAMILY, value).apply()

    // 自定义栏背景颜色：非 -1 时覆盖主题默认 barBackgroundColor；-1 表示跟随主题
    var customBarColor: Int
        get() = prefs.getInt(KEY_CUSTOM_BAR_COLOR, -1)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_BAR_COLOR, value).apply()

    companion object {
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_BACKGROUND_THEME = "background_theme"
        const val KEY_LAST_LIGHT_THEME = "last_light_theme"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_LAST_POSITION = "last_position"
        const val KEY_GESTURE_ENABLED = "gesture_enabled"
        const val KEY_SCROLL_MODE = "scroll_mode"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_CUSTOM_BAR_COLOR = "custom_bar_color"

        const val FONT_FAMILY_DEFAULT = 0
        const val FONT_FAMILY_SANS = 1
        const val FONT_FAMILY_SERIF = 2
        const val FONT_FAMILY_MONO = 3
        const val FONT_FAMILY_SANS_LIGHT = 4
        const val FONT_FAMILY_SANS_MEDIUM = 5
        const val FONT_FAMILY_SANS_CONDENSED = 6
        const val FONT_FAMILY_SERIF_MONO = 7
        const val FONT_FAMILY_CURSIVE = 8
        const val FONT_FAMILY_CASUAL = 9

        // 字体选项：id + 显示名 + Typeface，供设置页动态生成按钮与阅读页复用
        data class FontOption(val id: Int, val name: String, val typeface: Typeface)

        val FONT_OPTIONS = listOf(
            FontOption(FONT_FAMILY_DEFAULT, "默认", Typeface.DEFAULT),
            FontOption(FONT_FAMILY_SANS, "黑体", Typeface.SANS_SERIF),
            FontOption(FONT_FAMILY_SERIF, "宋体", Typeface.SERIF),
            FontOption(FONT_FAMILY_MONO, "等宽", Typeface.MONOSPACE),
            FontOption(FONT_FAMILY_SANS_LIGHT, "细体", Typeface.create("sans-serif-light", Typeface.NORMAL)),
            FontOption(FONT_FAMILY_SANS_MEDIUM, "中粗", Typeface.create("sans-serif-medium", Typeface.NORMAL)),
            FontOption(FONT_FAMILY_SANS_CONDENSED, "窄体", Typeface.create("sans-serif-condensed", Typeface.NORMAL)),
            FontOption(FONT_FAMILY_SERIF_MONO, "衬等宽", Typeface.create("serif-monospace", Typeface.NORMAL)),
            FontOption(FONT_FAMILY_CURSIVE, "手写", Typeface.create("cursive", Typeface.NORMAL)),
            FontOption(FONT_FAMILY_CASUAL, "休闲", Typeface.create("casual", Typeface.NORMAL))
        )

        const val THEME_WHITE = 0
        const val THEME_WARM_YELLOW = 1
        const val THEME_CREAM = 2
        const val THEME_GREEN = 3
        const val THEME_BLUE = 4
        const val THEME_DARK = 5
        const val THEME_SANDSTONE = 16

        const val FONT_SIZE_MIN = 14
        const val FONT_SIZE_MAX = 44
        const val FONT_SIZE_STEP = 1

        // 字体编号 → Typeface（供 ReadingView / 设置预览复用）
        fun resolveTypeface(family: Int): Typeface = when (family) {
            FONT_FAMILY_SANS -> Typeface.SANS_SERIF
            FONT_FAMILY_SERIF -> Typeface.SERIF
            FONT_FAMILY_MONO -> Typeface.MONOSPACE
            FONT_FAMILY_SANS_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            FONT_FAMILY_SANS_MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            FONT_FAMILY_SANS_CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            FONT_FAMILY_SERIF_MONO -> Typeface.create("serif-monospace", Typeface.NORMAL)
            FONT_FAMILY_CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
            FONT_FAMILY_CASUAL -> Typeface.create("casual", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }
}
