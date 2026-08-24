package com.example.novelreader

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.widget.*

/**
 * HSV 调色板弹窗：SAT 面板 + Hue 条 + Hex 输入。
 * 用于阅读页自定义栏背景颜色。
 */
class ColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) : Dialog(context) {

    private val hsv = FloatArray(3)
    private var currentColor: Int = 0
    private var updatingFromHex = false

    private lateinit var satValView: SatValView
    private lateinit var hueBarView: HueBarView
    private lateinit var previewCircle: View
    private lateinit var hexEdit: EditText

    init {
        Color.colorToHSV(initialColor, hsv)
        currentColor = initialColor
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        // 标题
        root.addView(TextView(context).apply {
            text = "自定义栏背景颜色"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        })

        // SAT 面板
        val satSize = dp(260)
        satValView = SatValView(context).apply {
            layoutParams = LinearLayout.LayoutParams(satSize, satSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(satValView)

        // 间距
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            )
        })

        // Hue 条
        hueBarView = HueBarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
            )
        }
        root.addView(hueBarView)

        // 颜色预览 + Hex 输入
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        previewCircle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundColor(currentColor)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
        }
        bottomRow.addView(previewCircle)

        hexEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(40)).apply {
                marginStart = dp(12)
            }
            setTextColor(Color.WHITE)
            hint = "#RRGGBB"
            setHintTextColor(Color.parseColor("#666666"))
            filters = arrayOf(InputFilter.LengthFilter(7))
            setBackgroundColor(Color.parseColor("#2A2A3E"))
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setText(String.format("#%06X", 0xFFFFFF and currentColor))

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (updatingFromHex) return
                    val hex = s?.toString()?.trim() ?: return
                    if (hex.length == 7 && hex.startsWith("#")) {
                        try {
                            val color = Color.parseColor(hex)
                            updatingFromHex = true
                            Color.colorToHSV(color, hsv)
                            currentColor = color
                            satValView.invalidate()
                            hueBarView.invalidate()
                            previewCircle.setBackgroundColor(color)
                            updatingFromHex = false
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        bottomRow.addView(hexEdit)
        root.addView(bottomRow)

        // 按钮行
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }

        btnRow.addView(TextView(context).apply {
            text = "取消"
            setTextColor(Color.parseColor("#888888"))
            textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { dismiss() }
        })

        btnRow.addView(TextView(context).apply {
            text = "确定"
            setTextColor(Color.parseColor("#7FFFD4"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), dp(10), dp(0), dp(10))
            setOnClickListener {
                onColorSelected(currentColor)
                dismiss()
            }
        })
        root.addView(btnRow)

        setContentView(root)
        window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onColorChanged() {
        currentColor = Color.HSVToColor(hsv)
        previewCircle.setBackgroundColor(currentColor)
        if (!updatingFromHex) {
            hexEdit.setText(String.format("#%06X", 0xFFFFFF and currentColor))
        }
        satValView.invalidate()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    // ── SAT 面板（饱和度 × 明度） ──
    inner class SatValView(context: Context) : View(context) {

        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // left→right: 白 → 选定色相（饱和度 0→1）
            val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            val satShader = LinearGradient(
                0f, 0f, w, 0f,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP
            )
            // top→bottom: 透明 → 黑（明度 1→0）
            val valShader = LinearGradient(
                0f, 0f, 0f, h,
                0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
            )
            val composeShader = ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY)
            paint.shader = composeShader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // 当前位置指示器（双环：白外环 + 黑内环，任意颜色上都可见）
            val cx = hsv[1] * w
            val cy = (1f - hsv[2]) * h
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = dpf(3f)
            paint.alpha = 255
            canvas.drawCircle(cx, cy, dpf(10f), paint)
            paint.color = Color.BLACK
            paint.strokeWidth = dpf(1.5f)
            canvas.drawCircle(cx, cy, dpf(8f), paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val x = event.x.coerceIn(0f, w)
                    val y = event.y.coerceIn(0f, h)
                    hsv[1] = x / w
                    hsv[2] = 1f - y / h
                    onColorChanged()
                }
            }
            return true
        }
    }

    // ── 色相条 ──
    inner class HueBarView(context: Context) : View(context) {

        private val paint = Paint()
        private val hueColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        private val huePositions = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val shader = LinearGradient(
                0f, 0f, w, 0f, hueColors, huePositions, Shader.TileMode.CLAMP
            )
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // 当前位置指示线
            val cx = hsv[0] / 360f * w
            paint.strokeWidth = dpf(3f)
            paint.color = Color.WHITE
            paint.alpha = 230
            canvas.drawLine(cx, 0f, cx, h, paint)
            paint.strokeWidth = dpf(1f)
            paint.color = Color.BLACK
            paint.alpha = 230
            canvas.drawLine(cx, 0f, cx, h, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val x = event.x.coerceIn(0f, width.toFloat())
                    hsv[0] = x / width * 360f
                    onColorChanged()
                }
            }
            return true
        }
    }

    private fun dpf(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    )
}
