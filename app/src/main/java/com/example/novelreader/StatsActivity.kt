package com.example.novelreader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import android.widget.TextView

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

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
}
