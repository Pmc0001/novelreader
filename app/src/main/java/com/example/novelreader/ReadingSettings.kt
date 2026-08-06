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

    var gestureSensitivity: Int
        get() = prefs.getInt(KEY_GESTURE_SENSITIVITY, SENSITIVITY_MEDIUM)
        set(value) = prefs.edit().putInt(KEY_GESTURE_SENSITIVITY, value).apply()

    // 滚动阅读模式：true=连续滚动，false=翻页（默认翻页）
    var scrollMode: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()

    // 正文字体：默认/黑体/宋体/等宽（默认系统默认）
    var fontFamily: Int
        get() = prefs.getInt(KEY_FONT_FAMILY, FONT_FAMILY_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_FONT_FAMILY, value).apply()

    companion object {
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_BACKGROUND_THEME = "background_theme"
        const val KEY_LAST_LIGHT_THEME = "last_light_theme"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_LAST_POSITION = "last_position"
        const val KEY_GESTURE_ENABLED = "gesture_enabled"
        const val KEY_GESTURE_SENSITIVITY = "gesture_sensitivity"
        const val KEY_SCROLL_MODE = "scroll_mode"
        const val KEY_FONT_FAMILY = "font_family"

        const val SENSITIVITY_LOW = 0
        const val SENSITIVITY_MEDIUM = 1
        const val SENSITIVITY_HIGH = 2

        const val FONT_FAMILY_DEFAULT = 0
        const val FONT_FAMILY_SANS = 1
        const val FONT_FAMILY_SERIF = 2
        const val FONT_FAMILY_MONO = 3

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
            else -> Typeface.DEFAULT
        }
    }
}
