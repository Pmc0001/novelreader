package com.example.novelreader

import android.content.Context
import android.content.SharedPreferences

class ReadingSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading_settings", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 18)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    var backgroundTheme: Int
        get() = prefs.getInt(KEY_BACKGROUND_THEME, THEME_WHITE)
        set(value) = prefs.edit().putInt(KEY_BACKGROUND_THEME, value).apply()

    var lineSpacing: Float
        get() = prefs.getFloat(KEY_LINE_SPACING, 1.6f)
        set(value) = prefs.edit().putFloat(KEY_LINE_SPACING, value).apply()

    var brightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, -1)
        set(value) = prefs.edit().putInt(KEY_BRIGHTNESS, value).apply()

    var lastReadPosition: Int
        get() = prefs.getInt(KEY_LAST_POSITION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_POSITION, value).apply()

    companion object {
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_BACKGROUND_THEME = "background_theme"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_LAST_POSITION = "last_position"

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
    }
}
