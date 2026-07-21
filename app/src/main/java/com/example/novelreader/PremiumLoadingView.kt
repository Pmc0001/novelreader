package com.example.novelreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.sin

class PremiumLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density

    private val outerArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * d
    }

    private val midArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.2f * d
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(5f * d, BlurMaskFilter.Blur.SOLID)
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 白色半透明卡片
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        alpha = 220
    }

    // 卡片柔光阴影
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x20000000
        maskFilter = BlurMaskFilter(12f * d, BlurMaskFilter.Blur.NORMAL)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E5F9E.toInt()
        textSize = 10f * d
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.25f
    }

    private val outerRect = RectF()
    private val midRect = RectF()

    private var outerRotation = 0f
    private var midRotation = 0f
    private var outerSweep = 90f
    private var midSweep = 100f
    private var pulse = 0f
    private var textAlpha = 0f

    private val animators = mutableListOf<ValueAnimator>()

    private data class Orbit(
        val radiusRatio: Float,
        val speed: Float,
        val phase: Float,
        val size: Float,
        val color: Int
    )

    private val orbits = listOf(
        Orbit(0.92f, 0.5f, 0f, 1.2f, 0xFF4A90D9.toInt()),
        Orbit(0.75f, -0.8f, 1.8f, 0.9f, 0xFF2E5F9E.toInt()),
        Orbit(0.58f, 1.1f, 3.5f, 0.8f, 0xFF6BAAF0.toInt())
    )

    fun startAnimation() {
        stopAnimation()

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                outerRotation = it.animatedValue as Float * 360f
                invalidate()
            }
            start()
        }

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                midRotation = it.animatedValue as Float * 360f
                invalidate()
            }
            start()
        }

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                outerSweep = 60f + (1f - cos(t * Math.PI * 2).toFloat()) * 0.5f * 220f
                midSweep = 80f + (1f - cos((t + 0.4f) * Math.PI * 2).toFloat()) * 0.5f * 160f
                invalidate()
            }
            start()
        }

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = OvershootInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                textAlpha = 0.4f + (it.animatedValue as Float) * 0.6f
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animators.forEach { it.cancel() }
        animators.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height * 0.44f
        val baseR = 22f * d

        // 白色磨砂卡片
        val pad = 10f * d
        val cardRect = RectF(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(cardRect, 18f * d, 18f * d, shadowPaint)
        canvas.drawRoundRect(cardRect, 18f * d, 18f * d, cardPaint)

        // 外圈渐变弧
        val outerGradient = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFF4A90D9.toInt(),
                0xFF2E5F9E.toInt(),
                0xFF6BAAF0.toInt(),
                0x004A90D9
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f)
        )
        val m = Matrix().apply { setRotate(outerRotation, cx, cy) }
        outerGradient.setLocalMatrix(m)
        outerArcPaint.shader = outerGradient
        outerRect.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR)
        canvas.drawArc(outerRect, outerRotation, outerSweep, false, outerArcPaint)

        // 中圈反向细弧
        val midGradient = SweepGradient(
            cx, cy,
            intArrayOf(0xFF6BAAF0.toInt(), 0xFF2E5F9E.toInt(), 0x006BAAF0),
            floatArrayOf(0f, 0.5f, 1f)
        )
        val m2 = Matrix().apply { setRotate(midRotation, cx, cy) }
        midGradient.setLocalMatrix(m2)
        midArcPaint.shader = midGradient
        val midR = baseR * 0.72f
        midRect.set(cx - midR, cy - midR, cx + midR, cy + midR)
        canvas.drawArc(midRect, -midRotation, midSweep, false, midArcPaint)

        // 内圈点状环
        val innerR = baseR * 0.48f
        val dotCount = 20
        for (i in 0 until dotCount) {
            val a = Math.toRadians((i * (360.0 / dotCount) + midRotation * 0.5).toDouble())
            val px = cx + cos(a) * innerR
            val py = cy + sin(a) * innerR
            val fade = 0.3f + 0.7f * (0.5f + 0.5f * sin(a * 2 + pulse * Math.PI * 2).toFloat())
            dotPaint.color = 0xFF2E5F9E.toInt()
            dotPaint.alpha = (fade * 255).toInt()
            canvas.drawCircle(px.toFloat(), py.toFloat(), 0.9f * d, dotPaint)
        }

        // 轨道粒子
        orbits.forEach { orb ->
            val r = baseR * orb.radiusRatio
            val a = Math.toRadians((outerRotation * orb.speed + orb.phase * 57.3).toDouble())
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            particlePaint.color = orb.color
            particlePaint.alpha = 200
            canvas.drawCircle(px.toFloat(), py.toFloat(), orb.size * d, particlePaint)
        }

        // 发光核心
        val coreScale = 0.75f + sin(pulse * Math.PI * 2).toFloat() * 0.25f
        val coreR = baseR * 0.2f * coreScale
        glowPaint.color = 0xFF4A90D9.toInt()
        canvas.drawCircle(cx, cy, coreR, glowPaint)
        corePaint.color = 0xFFE8F0FE.toInt()
        canvas.drawCircle(cx, cy, coreR * 0.4f, corePaint)

        // 加载文字
        textPaint.alpha = (textAlpha * 255).toInt()
        canvas.drawText("LOADING", cx, height * 0.78f, textPaint)
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
