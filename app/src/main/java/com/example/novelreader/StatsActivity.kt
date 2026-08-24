package com.example.novelreader

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.widget.ImageButton
import android.widget.TextView

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        enableEdgeToEdge()
        // 顶栏为深色背景，状态栏图标使用浅色以保证可见
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        applyCutoutInsets()

        ReadingStats.init(this)

        findViewById<ImageButton>(R.id.stats_back).setOnClickListener { finish() }

        val total = ReadingStats.getTotal()
        val today = ReadingStats.getToday()

        findViewById<TextView>(R.id.tv_total_time).text = ReadingStats.formatDuration(total.ms)
        findViewById<TextView>(R.id.tv_total_chars).text = ReadingStats.formatChars(total.chars)
        findViewById<TextView>(R.id.tv_total_chapters).text = total.chapters.toString()

        findViewById<TextView>(R.id.tv_today_time).text = ReadingStats.formatDuration(today.ms)
        findViewById<TextView>(R.id.tv_today_chars).text = ReadingStats.formatChars(today.chars)
        findViewById<TextView>(R.id.tv_today_chapters).text = today.chapters.toString()

        val chart = findViewById<StatsChartView>(R.id.stats_chart)
        chart.setData(ReadingStats.getLast7Days())
    }

    /**
     * 全面屏/挖孔适配：
     * - 顶栏加 statusBar 顶部留白，避免被状态栏/挖孔遮挡；
     * - ScrollView 加 navigationBars 底部留白，避免被导航栏手势条遮挡。
     */
    private fun applyCutoutInsets() {
        val topBar = findViewById<View>(R.id.top_bar)
        val scrollView = findViewById<View>(R.id.scroll_view)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = sb.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nb.bottom)
            insets
        }
    }
}
