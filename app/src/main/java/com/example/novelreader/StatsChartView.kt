package com.example.novelreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 自绘的 7 天阅读时长柱状图（不依赖第三方图表库）。
 * 数据通过 [setData] 注入，单位为「分钟」。
 */
class StatsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var minutes: List<Float> = emptyList()
    private var labels: List<String> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.colorPrimary)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textSecondary)
        textSize = 11f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textPrimary)
        textSize = 10f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    fun setData(data: List<Pair<String, DayStat>>) {
        this.minutes = data.map { (it.second.ms / 60000f) }
        this.labels = data.map { it.first.substring(5) } // MM-dd
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (minutes.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padBottom = 22f * resources.displayMetrics.density
        val padTop = 18f * resources.displayMetrics.density
        val padSide = 8f * resources.displayMetrics.density
        val chartH = h - padBottom - padTop
        val n = minutes.size
        val slot = (w - padSide * 2) / n
        val barW = slot * 0.55f
        val maxMin = (minutes.maxOrNull() ?: 0f).coerceAtLeast(1f)

        for (i in minutes.indices) {
            val cx = padSide + slot * i + slot / 2
            val m = minutes[i]
            val barH = (m / maxMin) * chartH
            val top = h - padBottom - barH
            // 柱体（至少 2dp 高，便于看到 0 分钟外的痕迹）
            val drawH = barH.coerceAtLeast(2f * resources.displayMetrics.density)
            canvas.drawRect(cx - barW / 2, h - padBottom - drawH, cx + barW / 2, h - padBottom, barPaint)

            // 数值（仅当 >0 时显示，避免刷屏）
            if (m > 0f) {
                val txt = if (m >= 60) String.format("%.1fh", m / 60f) else "${m.toInt()}m"
                canvas.drawText(txt, cx, top - 4f * resources.displayMetrics.density, valuePaint)
            }
            // 日期标签
            canvas.drawText(labels[i], cx, h - 6f * resources.displayMetrics.density, labelPaint)
        }
    }
}
