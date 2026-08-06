package com.example.novelreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var content: String = ""
    private var paragraphs: List<String> = emptyList()
    private data class LineEntry(val text: String, val isParagraphStart: Boolean)

    private var allLines: List<LineEntry> = emptyList()
    private var linesPerPage: Int = 0
    var currentPage: Int = 0
        private set
    private var totalPages: Int = 0

    val pageCount: Int
        get() = totalPages

    companion object {
        // 段间距（字号倍数）— 0.35 个字号 = 段间 17sp 左右，段/行比 ≈1.6；
        // 参考微信读书/掌阅/Kindle/iBooks 的 1.2~1.4 略宽一档，给中文阅读留呼吸感。
        // 关键约束：段间空隙必须 < 屏边距 (SCREEN_PADDING_V_DP)，否则"段比页边还抢眼"会切碎阅读节奏。
        private const val PARAGRAPH_SPACING = 0.35f
        // 段首缩进（字号倍数）— 2 字符缩进
        private const val FIRST_LINE_INDENT = 2f
        // 屏顶/屏底留白（dp）— 20dp 比段间距 17sp 略宽，给眼睛留呼吸缓冲；
        // 太小贴近边沿发紧，太大翻页效率下降
        private const val SCREEN_PADDING_V_DP = 20f
    }

    private val density: Float
        get() = resources.displayMetrics.density

    var fontSize: Float = 18f
        set(value) {
            field = value
            textPaint.textSize = value * density
            computeLayout()
            invalidate()
        }

    var textColor: Int = Color.parseColor("#454545")
        set(value) {
            field = value
            textPaint.color = value
            invalidate()
        }

    var bgColor: Int = Color.WHITE
        set(value) {
            field = value
            setBackgroundColor(value)
        }

    var lineSpacing: Float = 1.6f
        set(value) {
            field = value
            computeLayout()
            invalidate()
        }

    // 正文字体编号（对应 ReadingSettings.FONT_FAMILY_*）
    var fontFamily: Int = ReadingSettings.FONT_FAMILY_DEFAULT
        set(value) {
            field = value
            textPaint.typeface = ReadingSettings.resolveTypeface(value)
            computeLayout()
            invalidate()
        }

    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null
    var onTapCenter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onPrevChapter: (() -> Unit)? = null
    var onChapterTransitionComplete: ((isNext: Boolean) -> Unit)? = null
    // 滚动模式下上报阅读进度百分比（0~100）
    var onScrollProgress: ((percent: Int) -> Unit)? = null

    // 滚动阅读模式：true=连续滚动，false=翻页
    var scrollMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            settleAnimator?.cancel()
            isDragging = false
            drawOffset = 0f
            scrollY = 0f
            pendingScrollPercent = -1f
            computeLayout()
            invalidate()
        }

    // 滚动模式状态
    private var scrollY: Float = 0f            // 当前滚动偏移（向下为正）
    private var maxScroll: Float = 0f          // 最大可滚动距离
    private var scrollDragging = false
    private var pendingScrollPercent = -1f     // >=0 时 computeLayout 后定位到该比例
    private val overScroller = OverScroller(context)

    val scrollPercent: Float
        get() = if (maxScroll > 0f) (scrollY / maxScroll).coerceIn(0f, 1f) else 1f

    // 拖拽 / 翻页动画状态
    private var isDragging = false
    private var drawPageBase = 0   // 拖拽锚点页（drawOffset 为 0 时显示的页）
    private var drawOffset = 0f    // 像素；负=向左拖(翻下一页)，正=向右拖(翻上一页)
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null
    private var settleFinalPage = 0   // 正在播放的翻页动画结束时落到的页

    // 下一章过渡内容（预取后用于翻章动画）
    private var transitionNextLines: List<LineEntry> = emptyList()
    // 上一章过渡内容（预取后用于翻章动画，取上一章的最后一页）
    private var transitionPrevLines: List<LineEntry> = emptyList()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = fontSize * density
        isAntiAlias = true
        letterSpacing = 0.02f
        setShadowLayer(0.8f * density, 0f, 0.3f * density, 0x10000000)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 14f * resources.displayMetrics.density
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        textSize = 11f * resources.displayMetrics.density
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setContent(text: String) {
        content = text
        paragraphs = text.split("\n").filter { it.isNotBlank() }
        currentPage = 0
        drawPageBase = 0
        drawOffset = 0f
        scrollY = 0f
        pendingScrollPercent = -1f
        settleAnimator?.cancel()
        isDragging = false
        transitionNextLines = emptyList()
        transitionPrevLines = emptyList()
        computeLayout()
        invalidate()
    }

    fun setPage(page: Int) {
        if (scrollMode) {
            // 滚动模式下跳到顶部（正向进入新章）
            scrollY = 0f
            invalidate()
            return
        }
        if (page in 0 until totalPages) {
            currentPage = page
            drawPageBase = page
            drawOffset = 0f
            computeLayout()
            invalidate()
        }
    }

    fun goToLastPage() {
        if (scrollMode) {
            scrollY = maxScroll
            invalidate()
            return
        }
        if (totalPages > 0) {
            setPage(totalPages - 1)
        }
    }

    fun setTransitionContent(text: String) {
        if (width <= 0 || height <= 0) return
        val paras = text.split("\n").filter { it.isNotBlank() }
        val paddingH = 24f * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val availableWidth = width - paddingH * 2
        val newLines = mutableListOf<LineEntry>()
        for (paragraph in paras) {
            if (paragraph.isBlank()) continue
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent)
            wrapped.forEachIndexed { index, line ->
                newLines.add(LineEntry(line, index == 0))
            }
        }
        transitionNextLines = if (newLines.size > linesPerPage) {
            newLines.subList(0, linesPerPage)
        } else {
            newLines
        }
    }

    fun setTransitionPrevContent(text: String) {
        if (width <= 0 || height <= 0 || linesPerPage <= 0) return
        val paras = text.split("\n").filter { it.isNotBlank() }
        val paddingH = 24f * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val availableWidth = width - paddingH * 2
        val newLines = mutableListOf<LineEntry>()
        for (paragraph in paras) {
            if (paragraph.isBlank()) continue
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent)
            wrapped.forEachIndexed { index, line ->
                newLines.add(LineEntry(line, index == 0))
            }
        }
        // 取上一章的真实最后一页（与 linesForPage 计算方式一致，末页可能不足一页）
        val lastPage = (newLines.size - 1) / linesPerPage
        val startLine = lastPage * linesPerPage
        transitionPrevLines = newLines.subList(startLine, newLines.size)
    }

    fun commitCurrentPage() {
        if (settleAnimator?.isRunning == true) {
            settleAnimator?.cancel()
            settleAnimator = null
            currentPage = settleFinalPage
            drawPageBase = settleFinalPage
            drawOffset = 0f
            isDragging = false
            onPageChanged?.invoke(currentPage, totalPages)
            invalidate()
        }
    }

    // 待视图完成测量后再跳转的目标页（用于恢复阅读位置）
    private var pendingStartPage = -1

    fun setInitialPage(page: Int) {
        pendingStartPage = page
        if (width > 0 && height > 0) computeLayout()
    }

    // 滚动模式下按百分比定位（用于恢复阅读位置）
    fun setInitialScrollPercent(p: Float) {
        pendingScrollPercent = p.coerceIn(0f, 1f)
        if (width > 0 && height > 0) {
            scrollY = (maxScroll * pendingScrollPercent).coerceIn(0f, maxScroll)
            pendingScrollPercent = -1f
            invalidate()
            reportScrollProgress()
        }
    }

    private fun computeLayout() {
        if (width <= 0 || height <= 0) return

        val lineHeight = fontSize * lineSpacing * density
        val paddingH = 24f * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val availableWidth = width - paddingH * 2

        val newLines = mutableListOf<LineEntry>()
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent)
            wrapped.forEachIndexed { index, line ->
                newLines.add(LineEntry(line, index == 0))
            }
        }

        allLines = newLines

        if (scrollMode) {
            // 滚动模式：计算内容总高度与最大滚动距离
            val paddingV = SCREEN_PADDING_V_DP * density
            val paragraphGap = fontSize * density * PARAGRAPH_SPACING
            // 第一行 baseline = paddingTop + paddingV + fontSize
            var y = paddingTop + paddingV + fontSize * density
            allLines.forEachIndexed { index, entry ->
                if (entry.isParagraphStart && index > 0) y += paragraphGap
                y += lineHeight
            }
            // 内容总高 = 最后一行 baseline + descent - 第一行 top + paddingV 底
            val contentHeight = y - lineHeight + fontSize * density * 0.3f + paddingV + paddingBottom
            maxScroll = maxOf(0f, contentHeight - height)
            scrollY = scrollY.coerceIn(0f, maxScroll)
            if (pendingScrollPercent >= 0f) {
                scrollY = (maxScroll * pendingScrollPercent).coerceIn(0f, maxScroll)
                pendingScrollPercent = -1f
            }
            totalPages = 1
            reportScrollProgress()
            return
        }

        val paddingV = SCREEN_PADDING_V_DP * density
        val paragraphGap = fontSize * density * PARAGRAPH_SPACING
        // 可用高度 = 总高 - 屏顶留白 - 屏底留白 - 最后一行 descent 缓冲
        // 不然最后一行 baseline+descent 会延伸到 paddingBottom 区内，被栏/屏底切字
        val usableHeight = height - paddingTop - paddingBottom - paddingV * 2f - lineHeight * 0.3f
        linesPerPage = maxOf(1, (usableHeight / lineHeight).toInt())
        totalPages = maxOf(1, (allLines.size + linesPerPage - 1) / linesPerPage)

        if (pendingStartPage >= 0) {
            currentPage = pendingStartPage.coerceIn(0, totalPages - 1)
            drawPageBase = currentPage
            drawOffset = 0f
            pendingStartPage = -1
        }

        if (currentPage >= totalPages) currentPage = totalPages - 1
        if (drawPageBase >= totalPages) drawPageBase = totalPages - 1

        onPageChanged?.invoke(currentPage, totalPages)
    }

    private fun reportScrollProgress() {
        onScrollProgress?.invoke((scrollPercent * 100).toInt())
    }

    private fun linesForPage(page: Int): List<LineEntry> {
        if (page < 0 || page >= totalPages) return emptyList()
        val startLine = page * linesPerPage
        val endLine = minOf(startLine + linesPerPage, allLines.size)
        return if (startLine < allLines.size) allLines.subList(startLine, endLine) else emptyList()
    }

    private fun splitParagraphToLines(paragraph: String, availableWidth: Float, firstLineIndent: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = paragraph
        var isFirstLine = true

        while (remaining.isNotEmpty()) {
            val indent = if (isFirstLine) firstLineIndent else 0f
            val lineAvailableWidth = availableWidth - indent
            if (lineAvailableWidth <= 0) {
                lines.add(remaining)
                break
            }
            val measuredWidth = textPaint.measureText(remaining)
            if (measuredWidth <= lineAvailableWidth) {
                lines.add(remaining)
                break
            }

            var breakPoint = textPaint.breakText(remaining, true, lineAvailableWidth, null)
            if (breakPoint > 0 && breakPoint < remaining.length) {
                while (breakPoint > 0 && remaining[breakPoint - 1] != ' ') {
                    breakPoint--
                }
                if (breakPoint == 0) {
                    breakPoint = textPaint.breakText(remaining, true, lineAvailableWidth, null)
                }
            }

            lines.add(remaining.substring(0, breakPoint))
            remaining = remaining.substring(breakPoint).trimStart()
            isFirstLine = false
        }

        return if (lines.isEmpty()) listOf(paragraph) else lines
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (allLines.isEmpty()) {
            canvas.drawText("暂无内容", width / 2f, height / 2f, hintPaint)
            return
        }

        val w = width.toFloat()

        if (scrollMode) {
            drawScroll(canvas)
        } else if (isDragging || settleAnimator?.isRunning == true) {
            val baseLines = linesForPage(drawPageBase)
            if (drawOffset <= 0f) {
                drawPageAt(canvas, baseLines, drawOffset)
                // 下一页或下一章第一页
                if (drawPageBase >= totalPages - 1 && transitionNextLines.isNotEmpty()) {
                    drawPageAt(canvas, transitionNextLines, drawOffset + w)
                } else {
                    drawPageAt(canvas, linesForPage(drawPageBase + 1), drawOffset + w)
                }
            } else {
                drawPageAt(canvas, baseLines, drawOffset)
                // 上一页或上一章最后一页
                if (drawPageBase <= 0 && transitionPrevLines.isNotEmpty()) {
                    drawPageAt(canvas, transitionPrevLines, drawOffset - w)
                } else {
                    drawPageAt(canvas, linesForPage(drawPageBase - 1), drawOffset - w)
                }
            }
        } else {
            drawPageAt(canvas, linesForPage(currentPage), 0f)
        }

        drawTime(canvas)
    }

    // 滚动模式：连续绘制可见行，并支持顶/底边界"拉出上一章/下一章"提示
    private fun drawScroll(canvas: Canvas) {
        val lineHeight = fontSize * lineSpacing * density
        val paragraphGap = fontSize * density * PARAGRAPH_SPACING
        val paddingH = 24f * density
        val paddingV = SCREEN_PADDING_V_DP * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val firstAscent = fontSize * density
        val top = paddingTop + paddingV + firstAscent  // 第一行 baseline
        val bottomLimit = height - paddingBottom

        canvas.save()
        canvas.translate(0f, -scrollY)
        var y = top
        allLines.forEachIndexed { index, entry ->
            if (entry.isParagraphStart && index > 0) y += paragraphGap
            val baseline = y
            val lineTop = baseline - firstAscent
            val lineBottom = lineTop + lineHeight
            if (lineBottom > scrollY && lineTop < scrollY + height) {
                val indent = if (entry.isParagraphStart) firstLineIndent else 0f
                if (entry.text.isNotEmpty()) {
                    canvas.drawText(entry.text, paddingH + indent, baseline, textPaint)
                }
            }
            y += lineHeight
        }
        canvas.restore()

        // 边界拉出提示（在主画布固定位置绘制）
        if (scrollY < -4f) {
            canvas.drawText("上滑看上一章", width / 2f, 40f * density, hintPaint)
        } else if (scrollY > maxScroll + 4f) {
            canvas.drawText("下滑看下一章", width / 2f, height - 24f * density, hintPaint)
        }
    }

    private fun drawTime(canvas: Canvas) {
        val time = timeFormat.format(Date())
        val paddingH = 16f * density
        val paddingV = 12f * density
        canvas.drawText(time, width - paddingH, height - paddingV, timePaint)
    }

    private fun drawPageAt(canvas: Canvas, lines: List<LineEntry>, offsetX: Float) {
        if (lines.isEmpty()) return

        val paddingH = 24f * density
        val paddingV = SCREEN_PADDING_V_DP * density
        val lineHeight = fontSize * lineSpacing * density
        val paragraphGap = fontSize * density * PARAGRAPH_SPACING
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT

        // 第一行 baseline = paddingTop + paddingV + fontSize（顶边距 16dp，眼睛舒服）
        var y = paddingTop + paddingV + fontSize * density

        canvas.save()
        canvas.translate(offsetX, 0f)
        lines.forEachIndexed { index, entry ->
            if (entry.isParagraphStart && index > 0) {
                y += paragraphGap
            }
            if (entry.text.isEmpty()) {
                y += lineHeight * 0.5f
                return@forEachIndexed
            }
            val indent = if (entry.isParagraphStart) firstLineIndent else 0f
            canvas.drawText(entry.text, paddingH + indent, y, textPaint)
            y += lineHeight
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scrollMode) return handleScrollTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel()
                isDragging = false
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                drawPageBase = currentPage
                drawOffset = 0f
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                else velocityTracker?.clear()
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - lastTouchX
                lastTouchX = event.x

                if (!isDragging) {
                    val slop = 12f * density
                    if (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop
                    ) {
                        isDragging = true
                    }
                }

                if (isDragging) {
                    val w = width.toFloat()
                    drawOffset += dx

                    // 超过一整页则连续翻页，支持一次滑动划过多页
                    while (drawOffset <= -w && drawPageBase < totalPages - 1) {
                        drawOffset += w
                        drawPageBase++
                        currentPage = drawPageBase
                        onPageChanged?.invoke(currentPage, totalPages)
                    }
                    while (drawOffset >= w && drawPageBase > 0) {
                        drawOffset -= w
                        drawPageBase--
                        currentPage = drawPageBase
                        onPageChanged?.invoke(currentPage, totalPages)
                    }

                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                if (isDragging) {
                    settle(vx)
                } else {
                    handleTap(downX, downY)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scrollMode && overScroller.computeScrollOffset()) {
            scrollY = overScroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
            reportScrollProgress()
        }
    }

    // 滚动模式的触摸处理：拖拽滚动 + fling 惯性 + 顶/底边界拉出翻章
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overScroller.forceFinished(true)
                scrollDragging = true
                downY = event.y
                lastTouchX = event.y
                velocityTracker?.clear()
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!scrollDragging) return true
                val dy = event.y - lastTouchX
                lastTouchX = event.y
                val os = 80f * density
                scrollY = (scrollY - dy).coerceIn(-os, maxScroll + os)
                invalidate()
                reportScrollProgress()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                scrollDragging = false

                val os = 80f * density
                // 顶部拉出超过阈值 → 上一章
                if (scrollY <= -os * 0.6f) {
                    scrollY = 0f
                    invalidate()
                    onPrevChapter?.invoke()
                    return true
                }
                // 底部拉出超过阈值 → 下一章
                if (scrollY >= maxScroll + os * 0.6f) {
                    scrollY = maxScroll
                    invalidate()
                    onNextChapter?.invoke()
                    return true
                }
                // 否则惯性滑动
                val startY = scrollY.coerceIn(0f, maxScroll).toInt()
                overScroller.fling(
                    0, startY, 0, (-vy).toInt(),
                    0, 0, 0, maxScroll.toInt()
                )
                invalidate()
                return true
            }
        }
        return true
    }

    private fun changeBasePage(delta: Int) {
        if (delta > 0) {
            if (drawPageBase >= totalPages - 1) {
                drawOffset = 0f
                isDragging = false
                invalidate()
                onNextChapter?.invoke()
                return
            }
            drawPageBase++
        } else {
            if (drawPageBase <= 0) {
                drawOffset = 0f
                isDragging = false
                invalidate()
                onPrevChapter?.invoke()
                return
            }
            drawPageBase--
        }
        currentPage = drawPageBase
        onPageChanged?.invoke(currentPage, totalPages)
    }

    private fun handleTap(x: Float, y: Float) {
        val w = width.toFloat()
        when {
            x < w * 0.33f -> {
                if (currentPage > 0) {
                    isDragging = true
                    drawPageBase = currentPage
                    drawOffset = 0f
                    animateTo(w, currentPage - 1)
                } else if (transitionPrevLines.isNotEmpty()) {
                    animateChapterTransition(false)
                } else {
                    drawOffset = 0f
                    isDragging = false
                    invalidate()
                    onPrevChapter?.invoke()
                }
            }
            x > w * 0.67f -> {
                if (currentPage < totalPages - 1) {
                    isDragging = true
                    drawPageBase = currentPage
                    drawOffset = 0f
                    animateTo(-w, currentPage + 1)
                } else if (transitionNextLines.isNotEmpty()) {
                    animateChapterTransition(true)
                } else {
                    drawOffset = 0f
                    isDragging = false
                    invalidate()
                    onNextChapter?.invoke()
                }
            }
            else -> onTapCenter?.invoke()
        }
    }

    private fun settle(vx: Float) {
        val w = width.toFloat()
        val threshold = w * 0.35f
        val draggingNext = drawOffset < 0f
        val absOff = kotlin.math.abs(drawOffset)
        val canNext = drawPageBase < totalPages - 1
        val canPrev = drawPageBase > 0
        val flingFast = kotlin.math.abs(vx) > 1200f && ((vx < 0) == draggingNext)

        // 下一章边界：如果有过渡内容，播放翻章动画；否则直接触发加载
        if (draggingNext && !canNext && (absOff > threshold * 0.5f || flingFast)) {
            if (transitionNextLines.isNotEmpty()) {
                animateChapterTransition(true)
            } else {
                drawOffset = 0f
                isDragging = false
                invalidate()
                onNextChapter?.invoke()
            }
            return
        }
        // 上一章边界：如果有过渡内容，播放翻章动画；否则直接触发加载
        if (!draggingNext && !canPrev && (absOff > threshold * 0.5f || flingFast)) {
            if (transitionPrevLines.isNotEmpty()) {
                animateChapterTransition(false)
            } else {
                drawOffset = 0f
                isDragging = false
                invalidate()
                onPrevChapter?.invoke()
            }
            return
        }

        val commit =
            (absOff > threshold || flingFast) && ((draggingNext && canNext) || (!draggingNext && canPrev))

        val target: Float
        val finalBase: Int
        if (commit) {
            if (draggingNext) {
                target = -w
                finalBase = drawPageBase + 1
            } else {
                target = w
                finalBase = drawPageBase - 1
            }
        } else {
            target = 0f
            finalBase = drawPageBase
        }
        animateTo(target, finalBase)
    }

    private fun animateTo(target: Float, finalBase: Int) {
        settleFinalPage = finalBase
        settleAnimator?.cancel()
        val from = drawOffset
        settleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawPageBase = finalBase
                    currentPage = finalBase
                    drawOffset = 0f
                    isDragging = false
                    onPageChanged?.invoke(currentPage, totalPages)
                    invalidate()
                }
            })
            start()
        }
    }

    private fun animateChapterTransition(isNext: Boolean) {
        settleAnimator?.cancel()
        val from = drawOffset
        val target = if (isNext) -width.toFloat() else width.toFloat()
        isDragging = false
        settleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawOffset = 0f
                    isDragging = false
                    transitionNextLines = emptyList()
                    transitionPrevLines = emptyList()
                    invalidate()
                    if (isNext) onNextChapter?.invoke() else onPrevChapter?.invoke()
                }
            })
            start()
        }
    }

}
