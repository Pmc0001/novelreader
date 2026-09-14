package com.example.novelreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ===== 排版异步化（把 breakText 折行搬到后台线程，避免大章/整本单章在主线程卡顿） =====
    private val layoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var layoutJob: Job? = null
    // 一次排版完成后回调（由上层用于续读定位 / 收起 loading / 滚动模式拼接下一章）
    var onLayoutReady: (() -> Unit)? = null
    // applyChapter 的翻章方向：true=从末尾进入（上一章），排版完成后定位到末页/章底
    var pendingStartAtEnd = false
    // 标记「有待排版的内容（新章）」尚未完成：仅在内容排版完成时回调 onLayoutReady，
    // 字号/行距/尺寸变化触发的重排版不会误触发续读定位 / 收起 loading。
    private var contentLayoutPending = false

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
            relayout()
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
            relayout()
            invalidate()
        }

    // 正文字体编号（对应 ReadingSettings.FONT_FAMILY_*）
    var fontFamily: Int = ReadingSettings.FONT_FAMILY_DEFAULT
        set(value) {
            field = value
            textPaint.typeface = ReadingSettings.resolveTypeface(value)
            relayout()
            invalidate()
        }

    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null
    var onTapCenter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onPrevChapter: (() -> Unit)? = null
    var onChapterTransitionComplete: ((isNext: Boolean) -> Unit)? = null
    // 翻章动画开始前回调：上层可用已缓存的章节内容同步填充过渡内容，避免动画期间白屏；
    // 返回 true 表示存在可翻的上一/下一章（应播放动画），false 表示已到边界（不应播放动画）
    var onPrepareTransition: ((isNext: Boolean) -> Boolean)? = null
    // 轻量无副作用查询：是否存在可翻的上一/下一章，供拖拽边界判断实时反映 prevChapters/nextUrl 状态
    var onHasChapter: ((isNext: Boolean) -> Boolean)? = null
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
            // 切换模式时清理滚动模式专属的拼接状态
            scrollNextLines = emptyList()
            currentContentHeight = 0f
            nextChapterOffset = Float.MAX_VALUE
            flingRunning = false
            relayout()
            invalidate()
        }

    // 滚动模式状态
    private var scrollY: Float = 0f            // 当前滚动偏移（向下为正）
    private var maxScroll: Float = 0f          // 最大可滚动距离
    private var scrollDragging = false
    private var scrollMoved = false            // 本次触摸是否发生过超阈值的移动（用于区分点击与拖拽）
    private var pendingScrollPercent = -1f     // >=0 时 computeLayout 后定位到该比例
    private val overScroller = OverScroller(context)

    val scrollPercent: Float
        // 基于当前章自身可滚动高度（不含拼接的下一章），避免滑入下一章区域时进度超过 100%
        get() {
            val own = (currentContentHeight - height).coerceAtLeast(0f)
            return if (own > 0f) (scrollY / own).coerceIn(0f, 1f) else 0f
        }

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
    // 翻章切换中标记：动画结束到新章排版完成前的间隙，继续显示 incoming 章首/尾屏，
    // 避免异步 relayout 期间闪现旧章内容造成的"抖动"。
    private var chapterSwapPending = false
    private var chapterSwapIsNext = true

    // ===== 滚动模式：到底无缝衔接下一章 =====
    // 预取的下一章全文行（拼到当前章末尾连续渲染，滑过当前章末尾自然进入下一章）
    private var scrollNextLines: List<LineEntry> = emptyList()
    // 当前章正文高度（仅当前章，用于进度计算与边界判定）
    private var currentContentHeight: Float = 0f
    // 下一章首行 baseline 相对当前章首行 baseline 的偏移：scrollY 达到该值即进入下一章区域
    private var nextChapterOffset: Float = Float.MAX_VALUE
    // 无缝换章后需要恢复的 scrollY（onNextChapter 同步应用新章后会重置为 0，再据此恢复）
    private var pendingSwapScrollY: Float = 0f
    // fling 运行中标记，用于在 fling 结束时检测是否已滑入下一章区域
    private var flingRunning: Boolean = false

    private val chapterGapPx: Float
        get() = 32f * density

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

    // ===== 页脚电量 =====
    // 电池外框/填充/闪电：填充与文字同色（#999），低电量（<=20%）转为警示红
    private val batteryFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val batteryBoltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val batteryBoltPath = Path()
    private val batteryRect = RectF()

    // -1 表示未知（取不到电量时不绘制电池，只显示时间）
    private var batteryPercent: Int = -1
    private var batteryCharging: Boolean = false
    // 电量变化慢，30s 轮询一次即可；避免每帧读 BatteryManager
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            refreshBattery()
            invalidate()
            postDelayed(this, 30_000L)
        }
    }
    // 电量低于此值用警示色
    private val batteryLowThreshold = 20

    fun setContent(text: String, startAtEnd: Boolean = false) {
        content = text
        currentPage = 0
        drawPageBase = 0
        drawOffset = 0f
        scrollY = 0f
        pendingScrollPercent = -1f
        settleAnimator?.cancel()
        isDragging = false
        // 翻章切换间隙：保留过渡内容，待新章排版完成（relayout）后再清空，消除换章闪动
        if (!chapterSwapPending) {
            transitionNextLines = emptyList()
            transitionPrevLines = emptyList()
        }
        // 新章应用：旧的拼接下一章已无效（即将由上层重新预取拼接）
        scrollNextLines = emptyList()
        nextChapterOffset = Float.MAX_VALUE
        flingRunning = false
        pendingStartPage = -1
        // startAtEnd=true 表示「从末尾进入」（如回翻上一章），排版完成后定位到末页/章底。
        // 必须在 relayout() 启动前设置，排版完成的 computeLayout 才会据此定位。
        pendingStartAtEnd = startAtEnd
        // 折行排版搬到后台线程；排版完成前保留上一章画面（避免白屏闪烁），
        // 完成后再赋值 allLines 并分页（见 relayout）。
        contentLayoutPending = true
        relayout()
    }

    /** 翻章加载失败时用：取消换章过渡态，避免停留在 incoming 章首/尾屏（回到当前章画面）。 */
    fun abortChapterSwap() {
        chapterSwapPending = false
        transitionNextLines = emptyList()
        transitionPrevLines = emptyList()
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
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent, textPaint)
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
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent, textPaint)
            wrapped.forEachIndexed { index, line ->
                newLines.add(LineEntry(line, index == 0))
            }
        }
        // 取上一章的真实最后一页（与 linesForPage 计算方式一致，末页可能不足一页）
        val lastPage = (newLines.size - 1) / linesPerPage
        val startLine = lastPage * linesPerPage
        transitionPrevLines = newLines.subList(startLine, newLines.size)
    }

    /**
     * 滚动模式专用：把预取的下一章全文折行后拼到当前章末尾，
     * 使 maxScroll 延伸到下一章末尾，用户滑过当前章末尾即可连续进入下一章内容。
     * 仅在滚动模式下生效；翻页模式调用此方法无效。
     */
    fun setScrollNextChapter(text: String) {
        if (!scrollMode || width <= 0 || height <= 0) return
        scrollNextLines = layoutLines(text.split("\n").filter { it.isNotBlank() }, textPaint)
        computeLayout()
        invalidate()
    }

    /**
     * 滚动模式无感知换章：当 fling/拖拽结束且视口已进入预取的下一章区域时，
     * 把下一章切换为当前章，并将 scrollY 折算到新章对应位置，使屏内像素保持不变。
     *
     * 切换时机：scrollY >= nextChapterOffset（视口顶已越过下一章首行）
     *         或 scrollY >= maxScroll（已滑到拼接内容末尾，如下一章短于一屏）。
     */
    private fun maybeSwapToNextChapter() {
        if (scrollNextLines.isEmpty()) return
        if (scrollY < nextChapterOffset && scrollY < maxScroll) return
        // 在下一章内容空间中的偏移：scrollY - nextChapterOffset
        val offsetInNext = (scrollY - nextChapterOffset).coerceAtLeast(0f)
        overScroller.forceFinished(true)
        flingRunning = false
        // 即将成为当前章，先清空拼接（setContent 也会清，这里提前清避免边界判定错乱）
        scrollNextLines = emptyList()
        nextChapterOffset = Float.MAX_VALUE
        pendingSwapScrollY = offsetInNext
        // onNextChapter 会同步应用缓存中的下一章（loadChapter cache hit → setContent，scrollY 被重置为 0）
        onNextChapter?.invoke()
        // setContent 已重置 scrollY=0；恢复到无缝折算位置（限制在新当前章自身可滚动范围内）
        val own = (currentContentHeight - height).coerceAtLeast(0f)
        scrollY = pendingSwapScrollY.coerceIn(0f, own)
        invalidate()
        reportScrollProgress()
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
        // 排版尚未就绪（allLines 为空）时只记录意图，交由 relayout 完成后的 computeLayout 落实，
        // 避免在未排版时就把页码误算为 0。
        if (width > 0 && height > 0 && allLines.isNotEmpty()) computeLayout()
    }

    // 滚动模式下按百分比定位（用于恢复阅读位置）
    fun setInitialScrollPercent(p: Float) {
        pendingScrollPercent = p.coerceIn(0f, 1f)
        // 排版尚未就绪时只记录意图，交由 relayout 完成后的 computeLayout 落实。
        if (width > 0 && height > 0 && allLines.isNotEmpty()) {
            scrollY = (maxScroll * pendingScrollPercent).coerceIn(0f, maxScroll)
            pendingScrollPercent = -1f
            invalidate()
            reportScrollProgress()
        }
    }

    /**
     * 把「拆段 + 折行排版」搬到后台线程执行，完成后切主线程赋值 [allLines] 并分页。
     * 折行用的临时 Paint 与主线程 [textPaint] 保持完全一致的字号/字体/字距，保证换行结果一致。
     * 多次连续调用（快速翻章）会取消上一次未完成的排版，只保留最新一章。
     */
    private fun relayout() {
        if (content.isEmpty() || width <= 0 || height <= 0) return
        layoutJob?.cancel()
        val raw = content
        val fs = fontSize
        val ff = fontFamily
        val densityPt = resources.displayMetrics.density
        val w = width
        // 仅当本次排版源于「内容变化（新章）」时才在完成后回调 onLayoutReady
        val fireReady = contentLayoutPending
        layoutJob = layoutScope.launch {
            val paras = raw.split("\n").filter { it.isNotBlank() }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fs * densityPt
                typeface = ReadingSettings.resolveTypeface(ff)
                letterSpacing = 0.02f
            }
            // 异常安全：折行（breakText）偶发失败也不能让 onLayoutReady 永不触发，
            // 否则上层 showLoading(false) / 续读定位永远不执行 → loading 卡死被误认成「加载失败」。
            val lines = runCatching { layoutLines(paras, paint) }.getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                // 期间已发生更新的 setContent（content 变了），本次结果作废
                if (content != raw) return@withContext
                allLines = lines
                paragraphs = paras
                computeLayout()
                invalidate()
                if (chapterSwapPending) {
                    // 新章已排版完成，过渡内容与新章首/尾页内容一致，至此无缝接管，消除抖动
                    chapterSwapPending = false
                    transitionNextLines = emptyList()
                    transitionPrevLines = emptyList()
                    invalidate()
                }
                if (fireReady) {
                    contentLayoutPending = false
                    onLayoutReady?.invoke()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(batteryPollRunnable)
        layoutScope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // 回到前台时立即刷新一次，避免离开期间电量变化后显示滞后
        if (visibility == VISIBLE) {
            refreshBattery()
            removeCallbacks(batteryPollRunnable)
            postDelayed(batteryPollRunnable, 30_000L)
        } else {
            removeCallbacks(batteryPollRunnable)
        }
    }

    private fun computeLayout() {
        if (width <= 0 || height <= 0) return

        val lineHeight = fontSize * lineSpacing * density
        // 注意：allLines 已由后台 relayout() 计算完成，这里只做「分页」（极廉价），不再折行。

        if (scrollMode) {
            // 滚动模式：计算当前章内容高度与最大滚动距离
            val paddingV = SCREEN_PADDING_V_DP * density
            val paragraphGap = fontSize * density * PARAGRAPH_SPACING
            val firstAscent = fontSize * density
            val descent = fontSize * density * 0.3f
            // 第一行 baseline = paddingTop + paddingV + fontSize
            val top = paddingTop + paddingV + firstAscent
            var y = top
            var currentLastBaseline = top
            allLines.forEachIndexed { index, entry ->
                if (entry.isParagraphStart && index > 0) y += paragraphGap
                currentLastBaseline = y
                y += lineHeight
            }
            // 当前章内容总高（仅当前章，用于进度与边界判定）
            currentContentHeight = currentLastBaseline + descent + paddingV + paddingBottom

            if (scrollNextLines.isEmpty()) {
                // 未拼接下一章：常规单章可滚动距离
                nextChapterOffset = Float.MAX_VALUE
                maxScroll = (currentContentHeight - height).coerceAtLeast(0f)
            } else {
                // 拼接下一章：y 当前 = currentLastBaseline + lineHeight；补章节间距后为下一章首行 baseline
                y += chapterGapPx
                val nextFirstBaseline = y
                // 进入下一章区域所需的 scrollY（视口顶到达下一章首行位置）
                nextChapterOffset = nextFirstBaseline - top
                var ny = nextFirstBaseline
                var nextLastBaseline = nextFirstBaseline
                scrollNextLines.forEachIndexed { index, entry ->
                    if (entry.isParagraphStart && index > 0) ny += paragraphGap
                    nextLastBaseline = ny
                    ny += lineHeight
                }
                val combinedHeight = nextLastBaseline + descent + paddingV + paddingBottom
                maxScroll = (combinedHeight - height).coerceAtLeast(0f)
            }

            scrollY = scrollY.coerceIn(0f, maxScroll)
            if (pendingScrollPercent >= 0f) {
                // 续读定位以当前章自身高度为基准（不含拼接的下一章）
                val own = (currentContentHeight - height).coerceAtLeast(0f)
                scrollY = (own * pendingScrollPercent).coerceIn(0f, maxScroll)
                pendingScrollPercent = -1f
            } else if (pendingStartAtEnd) {
                // 上一章从末尾进入：定位到章底
                scrollY = maxScroll
                pendingStartAtEnd = false
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
        } else if (pendingStartAtEnd) {
            currentPage = (totalPages - 1).coerceAtLeast(0)
            drawPageBase = currentPage
            drawOffset = 0f
            pendingStartAtEnd = false
        }

        if (currentPage >= totalPages) currentPage = totalPages - 1
        if (drawPageBase >= totalPages) drawPageBase = totalPages - 1

        onPageChanged?.invoke(currentPage, totalPages)
    }

    /**
     * 把段落列表按当前字号/可用宽度折行成 [LineEntry]。
     * computeLayout 与 setScrollNextChapter 共用，确保当前章与拼接章的折行规则一致。
     */
    /**
     * 把段落列表按当前字号/可用宽度折行成 [LineEntry]。
     * @param paint 用于测量的画笔；主线程路径传 [textPaint]，后台排版路径传同参数的临时 Paint。
     * computeLayout 与 setScrollNextChapter 共用，确保当前章与拼接章的折行规则一致。
     */
    private fun layoutLines(paragraphs: List<String>, paint: Paint): List<LineEntry> {
        val paddingH = 24f * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val availableWidth = width - paddingH * 2
        val lines = mutableListOf<LineEntry>()
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue
            val wrapped = splitParagraphToLines(paragraph, availableWidth, firstLineIndent, paint)
            wrapped.forEachIndexed { index, line ->
                lines.add(LineEntry(line, index == 0))
            }
        }
        return lines
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

    private fun splitParagraphToLines(
        paragraph: String,
        availableWidth: Float,
        firstLineIndent: Float,
        paint: Paint
    ): List<String> {
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
            val measuredWidth = paint.measureText(remaining)
            if (measuredWidth <= lineAvailableWidth) {
                lines.add(remaining)
                break
            }

            var breakPoint = paint.breakText(remaining, true, lineAvailableWidth, null)
            if (breakPoint > 0 && breakPoint < remaining.length) {
                while (breakPoint > 0 && remaining[breakPoint - 1] != ' ') {
                    breakPoint--
                }
                if (breakPoint == 0) {
                    breakPoint = paint.breakText(remaining, true, lineAvailableWidth, null)
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
        relayout()
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
            if (chapterSwapPending) {
                // 换章间隙：有过渡内容时显示 incoming 章首/尾屏；无过渡内容（未预取/预取未完成）
                // 则保持空白底色，等待新章 relayout 接管，避免回落旧章当前页造成"滑出又弹回"的闪动。
                val swapLines = if (chapterSwapIsNext) transitionNextLines else transitionPrevLines
                if (swapLines.isNotEmpty()) {
                    drawPageAt(canvas, swapLines, 0f)
                }
            } else {
                drawPageAt(canvas, linesForPage(currentPage), 0f)
            }
        }

        drawFooter(canvas)
    }

    // 滚动模式：连续绘制可见行；若已预取下一章则拼到末尾连续渲染，实现到底无缝衔接
    private fun drawScroll(canvas: Canvas) {
        val lineHeight = fontSize * lineSpacing * density
        val paragraphGap = fontSize * density * PARAGRAPH_SPACING
        val paddingH = 24f * density
        val paddingV = SCREEN_PADDING_V_DP * density
        val firstLineIndent = fontSize * density * FIRST_LINE_INDENT
        val firstAscent = fontSize * density
        val top = paddingTop + paddingV + firstAscent  // 第一行 baseline

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
        // 拼接预取的下一章全文：y 此时为当前章末行 baseline + lineHeight，补章节间距后继续
        if (scrollNextLines.isNotEmpty()) {
            y += chapterGapPx
            scrollNextLines.forEachIndexed { index, entry ->
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
        }
        canvas.restore()

        // 边界拉出提示（在主画布固定位置绘制）
        // 已拼接下一章时，底部不再提示"下滑看下一章"（直接连续滑入即可）
        if (scrollY < -4f) {
            canvas.drawText("上滑看上一章", width / 2f, 40f * density, hintPaint)
        } else if (scrollY > maxScroll + 4f && scrollNextLines.isEmpty()) {
            canvas.drawText("下滑看下一章", width / 2f, height - 24f * density, hintPaint)
        }
    }

    /**
     * 页脚右下角：时间 + 电量（电池图标 + 百分比）。
     * 时间右对齐，电池画在时间右侧，整体贴右下角 paddingH/paddingV。
     */
    private fun drawFooter(canvas: Canvas) {
        val paddingH = 16f * density
        val paddingV = 12f * density
        val baselineY = height - paddingV
        var right = width - paddingH

        val showBattery = batteryPercent >= 0
        if (showBattery) {
            val bw = 17f * density
            val bh = 9f * density
            val fm = timePaint.fontMetrics
            val textCenter = baselineY + (fm.ascent + fm.descent) / 2f
            drawBattery(canvas, right - bw, textCenter - bh / 2f, bw, bh)
            right -= bw + 5f * density
        }

        val time = timeFormat.format(Date())
        val text = if (showBattery) "$time · $batteryPercent%" else time
        canvas.drawText(text, right, baselineY, timePaint)
    }

    /** 画一个电池：外框 + 正极 + 内部按百分比填充；充电时在中间画闪电。 */
    private fun drawBattery(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val stroke = maxOf(1f, 1f * density)
        val capW = 1.8f * density
        val capH = h * 0.42f
        val bodyW = w - capW - 1f * density
        val radius = 2f * density
        val inset = 1.5f * density

        val low = batteryPercent in 0..batteryLowThreshold
        val frameColor = if (low) Color.parseColor("#E5484D") else Color.parseColor("#999999")
        batteryFramePaint.color = frameColor
        batteryFramePaint.strokeWidth = stroke
        batteryFillPaint.color = frameColor
        batteryBoltPaint.color = frameColor

        // 正极（右侧小方块）
        batteryRect.set(
            left + bodyW + 1f * density,
            top + (h - capH) / 2f,
            left + bodyW + 1f * density + capW,
            top + (h - capH) / 2f + capH
        )
        canvas.drawRoundRect(batteryRect, 0.6f * density, 0.6f * density, batteryFillPaint)

        // 外框
        batteryRect.set(left + stroke / 2, top + stroke / 2, left + bodyW - stroke / 2, top + h - stroke / 2)
        canvas.drawRoundRect(batteryRect, radius, radius, batteryFramePaint)

        // 内部填充
        val innerMax = bodyW - inset * 2 - stroke
        val innerW = innerMax * (batteryPercent.coerceIn(0, 100) / 100f)
        if (innerW > 0.5f) {
            batteryRect.set(
                left + inset,
                top + inset,
                left + inset + innerW,
                top + h - inset
            )
            canvas.drawRoundRect(batteryRect, radius * 0.5f, radius * 0.5f, batteryFillPaint)
        }

        if (batteryCharging) {
            drawBolt(canvas, left + bodyW / 2f, top + h / 2f, h * 0.72f)
        }
    }

    /** 充电闪电：用背景色画，压在填充之上，形成"抠空"效果。 */
    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        val w = h * 0.6f
        batteryBoltPaint.color = bgColor
        batteryBoltPath.reset()
        batteryBoltPath.moveTo(cx + w * 0.18f, cy - h * 0.5f)
        batteryBoltPath.lineTo(cx - w * 0.38f, cy + h * 0.06f)
        batteryBoltPath.lineTo(cx + w * 0.02f, cy + h * 0.06f)
        batteryBoltPath.lineTo(cx - w * 0.18f, cy + h * 0.5f)
        batteryBoltPath.lineTo(cx + w * 0.38f, cy - h * 0.08f)
        batteryBoltPath.lineTo(cx - w * 0.02f, cy - h * 0.08f)
        batteryBoltPath.close()
        canvas.drawPath(batteryBoltPath, batteryBoltPaint)
    }

    /**
     * 读取当前电量：BatteryManager 取容量（无需权限），sticky 广播判断是否在充电。
     * 结果缓存在 batteryPercent / batteryCharging，由 batteryPollRunnable 定期刷新。
     */
    private fun refreshBattery() {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            batteryPercent = if (capacity in 0..100) capacity else -1
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            batteryPercent = -1
            batteryCharging = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshBattery()
        removeCallbacks(batteryPollRunnable)
        postDelayed(batteryPollRunnable, 30_000L)
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

                    // 边界橡皮筋：仅在"本页已是首页且无上一章"时禁止向右拖出；
                    // "本页已是末页且无下一章"时禁止向左拖出。章内正常的上一页/下一页翻动不受影响。
                    if (drawOffset > 0f && drawPageBase <= 0 && onHasChapter?.invoke(false) != true) drawOffset = 0f
                    if (drawOffset < 0f && drawPageBase >= totalPages - 1 && onHasChapter?.invoke(true) != true) drawOffset = 0f

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
        if (!scrollMode) return
        if (overScroller.computeScrollOffset()) {
            scrollY = overScroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
            reportScrollProgress()
        } else if (flingRunning) {
            // fling 结束：若已滑入预取的下一章区域，则无感知地把下一章切换为当前章
            flingRunning = false
            maybeSwapToNextChapter()
        }
    }

    // 滚动模式的触摸处理：拖拽滚动 + fling 惯性 + 顶/底边界拉出翻章
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overScroller.forceFinished(true)
                // 若上一次 fling 被手指中断且已滑入下一章区域，先结算无感知换章
                maybeSwapToNextChapter()
                flingRunning = false
                scrollDragging = true
                scrollMoved = false
                downX = event.x
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
                // 位移超过 slop 才算"拖拽"，否则视为点击（松开时切换栏显隐）
                val slop = 12f * density
                if (!scrollMoved &&
                    (kotlin.math.abs(event.x - downX) > slop ||
                     kotlin.math.abs(event.y - downY) > slop)
                ) {
                    scrollMoved = true
                }
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

                // 纯点击（未发生超阈值移动）→ 切换顶/底栏显隐，与翻页模式中心点击一致
                if (!scrollMoved) {
                    onTapCenter?.invoke()
                    return true
                }

                val os = 80f * density
                // 顶部拉出超过阈值 → 上一章
                if (scrollY <= -os * 0.6f) {
                    scrollY = 0f
                    invalidate()
                    onPrevChapter?.invoke()
                    return true
                }
                // 底部拉出超过阈值 → 下一章
                // 已拼接预取的下一章时，底部边界即下一章末尾，不在此处触发翻章；
                // 由 fling 结束时的 maybeSwapToNextChapter 在屏内无感知切换。
                if (scrollNextLines.isEmpty() && scrollY >= maxScroll + os * 0.6f) {
                    scrollY = maxScroll
                    invalidate()
                    onNextChapter?.invoke()
                    return true
                }
                // 否则惯性滑动（maxScroll 已含拼接的下一章，可顺滑滑入）
                val startY = scrollY.coerceIn(0f, maxScroll).toInt()
                overScroller.fling(
                    0, startY, 0, (-vy).toInt(),
                    0, 0, 0, maxScroll.toInt()
                )
                flingRunning = true
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
                } else if (onPrepareTransition?.invoke(false) == true) {
                    // 与翻页统一：播放翻页滑动动画；先用缓存同步填充过渡内容，避免白屏
                    drawPageBase = currentPage
                    drawOffset = 0f
                    isDragging = false
                    animateChapterTransition(false)
                } else {
                    // 已是第一章：不播放翻章动画，交由上层提示（如"已经是第一章"）
                    drawPageBase = currentPage
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
                } else if (onPrepareTransition?.invoke(true) == true) {
                    // 与翻页统一：播放翻页滑动动画；先用缓存同步填充过渡内容，避免白屏
                    drawPageBase = currentPage
                    drawOffset = 0f
                    isDragging = false
                    animateChapterTransition(true)
                } else {
                    // 已是最后一章：不播放翻章动画，交由上层提示（如"已经是最后一章"）
                    drawPageBase = currentPage
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

        // 下一章边界：统一播放与翻页一致的滑动动画（仅当确实存在可翻的下一章）
        if (draggingNext && !canNext && (absOff > threshold * 0.5f || flingFast)) {
            if (onPrepareTransition?.invoke(true) == true) {
                animateChapterTransition(true)
            } else {
                // 已是末章：复位状态并交上层提示（如"已经是最后一章"），避免画面卡在拖出位置
                drawOffset = 0f
                isDragging = false
                invalidate()
                onNextChapter?.invoke()
            }
            return
        }
        // 上一章边界：统一播放与翻页一致的滑动动画（仅当确实存在可翻的上一章）
        if (!draggingNext && !canPrev && (absOff > threshold * 0.5f || flingFast)) {
            if (onPrepareTransition?.invoke(false) == true) {
                animateChapterTransition(false)
            } else {
                // 已是首章：复位状态并交上层提示（如"已经是第一章"），避免画面卡在拖出位置
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
                    // 不立即清空过渡内容：保留 incoming 章首/尾屏，直到新章 relayout 完成（见 relayout），
                    // 期间由 onDraw 的 chapterSwapPending 分支继续绘制，消除换章瞬间的闪动/抖动。
                    chapterSwapPending = true
                    chapterSwapIsNext = isNext
                    invalidate()
                    if (isNext) onNextChapter?.invoke() else onPrevChapter?.invoke()
                }
            })
            start()
        }
    }

}
