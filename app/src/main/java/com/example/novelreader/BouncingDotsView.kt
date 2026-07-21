package com.example.novelreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class BouncingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.colorPrimary)
    }

    private val dotCount = 3
    private val dotRadius = 5f * resources.displayMetrics.density
    private val dotSpacing = 18f * resources.displayMetrics.density
    private val bounceHeight = 12f * resources.displayMetrics.density

    private var phases = FloatArray(dotCount)
    private var animator: ValueAnimator? = null

    fun startAnimation() {
        stopAnimation()
        var time = 0f
        animator = ValueAnimator.ofFloat(0f, 100f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                time += 0.032f
                for (i in 0 until dotCount) {
                    phases[i] = bounceHeight * kotlin.math.sin(time * 5.0 - i * 2.09).toFloat()
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        phases = FloatArray(dotCount)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f
        val totalWidth = (dotCount - 1) * dotSpacing
        val startX = (width - totalWidth) / 2f

        for (i in 0 until dotCount) {
            val x = startX + i * dotSpacing
            val y = centerY - phases[i]
            canvas.drawCircle(x, y, dotRadius, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
