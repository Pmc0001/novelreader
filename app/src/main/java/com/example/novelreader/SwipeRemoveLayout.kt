package com.example.novelreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 左滑移除容器：底层右侧是红色"移除"按钮，上层是内容卡片。
 *
 * 交互约定：
 *  - 手指按住内容卡片向左滑 → 卡片跟手平移，露出右侧移除按钮（与外层 ScrollView 无冲突，
 *    仅当水平位移超过 touchSlop 且以水平为主时才接管手势）
 *  - 松手时按位置（过半）/ 速度（快速左甩）吸附到"展开"或"收起"位
 *  - 展开状态下点击内容卡片 → 收起；点击红色按钮 → 整行滑出并触发 [onRemove]
 *  - 有行处于展开态时，点击其他行只收起展开行、不误触被点行的点击
 *  - 同一时刻最多一行展开（companion 单例跟踪）
 */
class SwipeRemoveLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 用户确认移除该行时回调（MainActivity 据此删数据 + 刷新列表） */
    var onRemove: (() -> Unit)? = null

    private var content: View? = null
    private var revealWidth = 0f

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastT = 0L
    private var startTx = 0f          // 本次手势开始时内容卡片的 translationX
    private var dragging = false      // 已进入水平拖拽
    private var vx = 0f               // 平滑后的水平速度（px/s）
    // 为"收起其他展开行"而拦截的点击：吞掉本次点击，避免用户只想收起却误打开了书
    private var consumeTap = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingThreshold = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3f

    companion object {
        // 当前展开的行：保证同一时刻只有一行处于展开态
        private var openRow: SwipeRemoveLayout? = null

        /** 收起当前展开的行（若有） */
        fun closeOpenRow() {
            openRow?.let { row ->
                openRow = null
                row.animateTo(0f)
            }
        }
    }

    private val isOpen: Boolean
        get() = (content?.translationX ?: 0f) < -revealWidth / 2f

    init {
        val density = resources.displayMetrics.density
        revealWidth = 92 * density

        // 底层：右侧红色"移除"按钮（圆角 18dp 与书卡一致），展开后露出
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_swipe_remove)
            layoutParams = LayoutParams(revealWidth.toInt(), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
            isClickable = true
            contentDescription = "移除"
            setOnClickListener { performRemove() }

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_trash)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(), (20 * density).toInt()
                )
            }
            val label = TextView(context).apply {
                text = "移除"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (5 * density).toInt() }
            }
            addView(icon)
            addView(label)
        }
        addView(btn)
    }

    /** 把要支持左滑的内容视图（书行卡片）放进容器，覆盖在移除按钮之上 */
    fun setContentView(view: View) {
        content = view
        addView(view)
    }

    // ===== 事件分发：父级（ScrollView）放行 + 水平手势接管 =====

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastT = ev.eventTime
                startTx = content?.translationX ?: 0f
                dragging = false; vx = 0f; consumeTap = false
                val overButton = ev.x > width - revealWidth
                val hadOtherOpen = openRow != null && openRow !== this
                if (hadOtherOpen) closeOpenRow()
                return when {
                    // 有其他行展开：本次按下只为收起，整条手势接管并吞掉点击
                    hadOtherOpen -> { consumeTap = true; true }
                    // 自身已展开：点内容区 → 接管（收起/拖动）；点按钮区 → 放行给按钮
                    isOpen && !overButton -> true
                    else -> false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 水平位移超过 slop 且以水平为主 → 进入左滑手势，同时禁止外层 ScrollView 抢事件
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 由 onInterceptTouchEvent(DOWN)=true 进入（自身展开态点击 / 吞 tap）
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastT = ev.eventTime
                startTx = content?.translationX ?: 0f
                dragging = false; vx = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    consumeTap = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    trackVelocity(ev)
                    content?.translationX = clamp(startTx + dx)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> settle()
                    consumeTap -> Unit               // 只为收起其他行，吞掉本次点击
                    else -> {                         // 自身展开态的干净点击 → 收起
                        if (openRow === this) openRow = null
                        animateTo(0f)
                    }
                }
                dragging = false
                consumeTap = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 被外层 ScrollView 抢走（用户实际想竖向滚动）→ 原位回弹
                if (dragging) settle()
                dragging = false
                consumeTap = false
                return true
            }
        }
        return true
    }

    // ===== 手势辅助 =====

    private fun trackVelocity(ev: MotionEvent) {
        val dt = (ev.eventTime - lastT).coerceAtLeast(1L)
        val inst = (ev.x - lastX) / dt * 1000f
        vx = vx * 0.65f + inst * 0.35f
        lastX = ev.x
        lastT = ev.eventTime
    }

    /** 平移范围限制：左右越界给 1/3 橡皮筋阻尼 */
    private fun clamp(tx: Float): Float = when {
        tx > 0f -> tx / 3f
        tx < -revealWidth -> -revealWidth + (tx + revealWidth) / 3f
        else -> tx
    }

    /** 松手后按位置（过半）或速度（快速左甩/右甩）决定吸附到展开位还是收起位 */
    private fun settle() {
        val tx = content?.translationX ?: 0f
        val shouldOpen = if (abs(vx) > flingThreshold) vx < 0f else tx < -revealWidth / 2f
        if (shouldOpen) {
            openRow = this
            animateTo(-revealWidth)
        } else {
            if (openRow === this) openRow = null
            animateTo(0f)
        }
        vx = 0f
    }

    private fun animateTo(target: Float) {
        val c = content ?: return
        c.animate().cancel()
        c.animate().translationX(target).setDuration(200L).start()
    }

    /** 点击"移除"：整行滑出 + 淡出，动画结束后回调 [onRemove]（由外部刷新列表） */
    private fun performRemove() {
        if (openRow === this) openRow = null
        val c = content
        if (c == null) {
            onRemove?.invoke()
            return
        }
        val animator = c.animate()
        animator.cancel()
        animator
            .translationX(-width * 0.9f)
            .alpha(0f)
            .setDuration(220L)
            .setListener(object : AnimatorListenerAdapter() {
                private var fired = false
                override fun onAnimationEnd(animation: Animator) {
                    animator.setListener(null)
                    if (!fired) {
                        fired = true
                        onRemove?.invoke()
                    }
                }
            })
            .start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (openRow === this) openRow = null
        content?.animate()?.cancel()
    }
}
