package com.example.novelreader

import android.graphics.Color

enum class BackgroundTheme(
    val id: Int,
    val displayName: String,
    val backgroundColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val previewColor: Int
) {
    WHITE(
        id = ReadingSettings.THEME_WHITE,
        displayName = "白色",
        backgroundColor = Color.WHITE,
        textColor = Color.parseColor("#454545"),
        secondaryTextColor = Color.parseColor("#999999"),
        previewColor = Color.WHITE
    ),
    WARM_YELLOW(
        id = ReadingSettings.THEME_WARM_YELLOW,
        displayName = "暖黄",
        backgroundColor = Color.parseColor("#F5E6C8"),
        textColor = Color.parseColor("#4A4035"),
        secondaryTextColor = Color.parseColor("#8B7355"),
        previewColor = Color.parseColor("#F5E6C8")
    ),
    CREAM(
        id = ReadingSettings.THEME_CREAM,
        displayName = "羊皮纸",
        backgroundColor = Color.parseColor("#F0E4D0"),
        textColor = Color.parseColor("#4A4540"),
        secondaryTextColor = Color.parseColor("#8B7E6A"),
        previewColor = Color.parseColor("#F0E4D0")
    ),
    GREEN(
        id = ReadingSettings.THEME_GREEN,
        displayName = "护眼绿",
        backgroundColor = Color.parseColor("#CCE8CF"),
        textColor = Color.parseColor("#3A4A3A"),
        secondaryTextColor = Color.parseColor("#6A8A6A"),
        previewColor = Color.parseColor("#CCE8CF")
    ),
    BLUE(
        id = ReadingSettings.THEME_BLUE,
        displayName = "淡蓝",
        backgroundColor = Color.parseColor("#D6E4F0"),
        textColor = Color.parseColor("#3A4050"),
        secondaryTextColor = Color.parseColor("#6A7A90"),
        previewColor = Color.parseColor("#D6E4F0")
    ),
    DARK(
        id = ReadingSettings.THEME_DARK,
        displayName = "夜间",
        backgroundColor = Color.parseColor("#1A1A2E"),
        textColor = Color.parseColor("#AAAAAA"),
        secondaryTextColor = Color.parseColor("#666666"),
        previewColor = Color.parseColor("#1A1A2E")
    ),
    WARM_WHITE(
        id = 6,
        displayName = "暖白",
        backgroundColor = Color.parseColor("#FFF6E9"),
        textColor = Color.parseColor("#4A4538"),
        secondaryTextColor = Color.parseColor("#9C8E7A"),
        previewColor = Color.parseColor("#FFF6E9")
    ),
    APRICOT(
        id = 7,
        displayName = "杏色",
        backgroundColor = Color.parseColor("#FBE3C9"),
        textColor = Color.parseColor("#4A4035"),
        secondaryTextColor = Color.parseColor("#9C8466"),
        previewColor = Color.parseColor("#FBE3C9")
    ),
    PEACH(
        id = 8,
        displayName = "桃色",
        backgroundColor = Color.parseColor("#FAD9C0"),
        textColor = Color.parseColor("#4A3A30"),
        secondaryTextColor = Color.parseColor("#A07A66"),
        previewColor = Color.parseColor("#FAD9C0")
    ),
    ROSE(
        id = 9,
        displayName = "玫瑰",
        backgroundColor = Color.parseColor("#F7D9D9"),
        textColor = Color.parseColor("#4A3535"),
        secondaryTextColor = Color.parseColor("#A07070"),
        previewColor = Color.parseColor("#F7D9D9")
    ),
    SEPIA(
        id = 10,
        displayName = "复古",
        backgroundColor = Color.parseColor("#F4E8D1"),
        textColor = Color.parseColor("#4A4030"),
        secondaryTextColor = Color.parseColor("#8B7355"),
        previewColor = Color.parseColor("#F4E8D1")
    ),
    LAVENDER(
        id = 11,
        displayName = "淡紫",
        backgroundColor = Color.parseColor("#E8E0F0"),
        textColor = Color.parseColor("#3D3850"),
        secondaryTextColor = Color.parseColor("#7A6F98"),
        previewColor = Color.parseColor("#E8E0F0")
    ),
    MINT(
        id = 12,
        displayName = "薄荷",
        backgroundColor = Color.parseColor("#D5EDDF"),
        textColor = Color.parseColor("#304A3A"),
        secondaryTextColor = Color.parseColor("#5A8A7A"),
        previewColor = Color.parseColor("#D5EDDF")
    ),
    PARCHMENT(
        id = 13,
        displayName = "牛皮纸",
        backgroundColor = Color.parseColor("#EDE0C8"),
        textColor = Color.parseColor("#4A4030"),
        secondaryTextColor = Color.parseColor("#8B7E62"),
        previewColor = Color.parseColor("#EDE0C8")
    ),
    GRAY(
        id = 14,
        displayName = "浅灰",
        backgroundColor = Color.parseColor("#F0F0F0"),
        textColor = Color.parseColor("#454545"),
        secondaryTextColor = Color.parseColor("#888888"),
        previewColor = Color.parseColor("#F0F0F0")
    ),
    WARM_PINK(
        id = 15,
        displayName = "淡粉",
        backgroundColor = Color.parseColor("#FDE8E8"),
        textColor = Color.parseColor("#4A3535"),
        secondaryTextColor = Color.parseColor("#907070"),
        previewColor = Color.parseColor("#FDE8E8")
    ),
    SANDSTONE(
        id = ReadingSettings.THEME_SANDSTONE,
        displayName = "砂岩",
        backgroundColor = Color.parseColor("#DDD0BC"),
        textColor = Color.parseColor("#4A453A"),
        secondaryTextColor = Color.parseColor("#8A7E6A"),
        previewColor = Color.parseColor("#DDD0BC")
    );

    companion object {
        fun fromId(id: Int): BackgroundTheme {
            return entries.find { it.id == id } ?: WHITE
        }
    }
}
