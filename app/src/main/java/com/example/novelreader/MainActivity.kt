package com.example.novelreader

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.text.TextUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.novelreader.HistoryStore

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var historyContainer: LinearLayout
    private lateinit var loadingView: PremiumLoadingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getPreferences(MODE_PRIVATE)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForNovelUrl()
        refreshHistory()
    }

    private fun setupUI() {
        loadingView = findViewById(R.id.main_loading_view)
        val rootLayout = findViewById<LinearLayout>(R.id.main)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val spacer1 = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        }

        val iconSize = (120 * resources.displayMetrics.density).toInt()
        val bookIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.9f
        }

        val title = TextView(this).apply {
            text = "小说阅读器"
            textSize = 32f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            setPadding(0, 32, 0, 12)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "输入小说章节 URL，在线加载阅读"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 32
                marginEnd = 32
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            elevation = 8 * resources.displayMetrics.density
            setPadding(24, 24, 24, 24)
        }

        val cardTitle = TextView(this).apply {
            text = "开始阅读"
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val cardSubtitle = TextView(this).apply {
            text = "输入小说页面的 URL 地址即可开始阅读"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            setPadding(0, 0, 0, 20)
        }

        val loadUrlButton = Button(this).apply {
            text = "加载在线小说"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textOnPrimary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            elevation = 4 * resources.displayMetrics.density
            setOnClickListener {
                showUrlInputDialog()
            }
        }

        cardLayout.addView(cardTitle)
        cardLayout.addView(cardSubtitle)
        cardLayout.addView(loadUrlButton)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val spacer2 = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1.5f }
        }

        val footerText = TextView(this).apply {
            text = "v1.0"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        mainLayout.addView(spacer1)
        mainLayout.addView(bookIcon)
        mainLayout.addView(title)
        mainLayout.addView(subtitle)
        mainLayout.addView(cardLayout)
        mainLayout.addView(historyContainer)
        mainLayout.addView(spacer2)
        mainLayout.addView(footerText)

        scrollView.addView(mainLayout)
        rootLayout.addView(scrollView)
    }

    private fun refreshHistory() {
        historyContainer.removeAllViews()
        val history = HistoryStore(this).getHistory()
        if (history.isEmpty()) return

        val density = resources.displayMetrics.density

        val sectionTitle = TextView(this).apply {
            text = "阅读历史"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((32 * density).toInt(), (24 * density).toInt(), (32 * density).toInt(), (12 * density).toInt())
        }
        historyContainer.addView(sectionTitle)

        for (item in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (32 * density).toInt()
                    marginEnd = (32 * density).toInt()
                    bottomMargin = (12 * density).toInt()
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
                elevation = 4 * density
                setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val tvTitle = TextView(this).apply {
                text = item.title
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val tvSub = TextView(this).apply {
                text = if (item.totalPages > 0) "上次读到第 ${item.position + 1} / ${item.totalPages} 页" else "点击继续阅读"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            textLayout.addView(tvTitle)
            textLayout.addView(tvSub)

            val arrow = TextView(this).apply {
                text = "继续 ›"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
                setPadding((16 * density).toInt(), 0, 0, 0)
            }

            row.addView(textLayout)
            row.addView(arrow)

            row.setOnClickListener {
                val intent = Intent(this@MainActivity, ReadingActivity::class.java).apply {
                    putExtra("EXTRA_URL", item.url)
                    putExtra("EXTRA_NEXT_CHAPTER", item.nextChapterUrl ?: "")
                    putExtra("EXTRA_POSITION", item.position)
                }
                startActivity(intent)
            }

            historyContainer.addView(row)
        }

        val clearBtn = TextView(this).apply {
            text = "清除历史记录"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
            setOnClickListener {
                HistoryStore(this@MainActivity).clear()
                refreshHistory()
            }
        }
        historyContainer.addView(clearBtn)
    }

    private fun showUrlInputDialog(presetUrl: String = "http://www.bookszw.com/156/156468/52087819.html") {
        val dialog = UrlInputDialog.newInstance(presetUrl)
        dialog.setOnLoadListener(
            onSubmit = { url ->
                loadUrlOnCurrentPage(url)
            }
        )
        dialog.show(supportFragmentManager, UrlInputDialog.TAG)
    }

    private fun loadUrlOnCurrentPage(url: String) {
        loadingView.visibility = View.VISIBLE
        val loader = UrlLoader(this)
        lifecycleScope.launch {
            when (val result = loader.loadUrl(url)) {
                is UrlLoader.LoadResult.Success -> {
                    loadingView.visibility = View.GONE
                    val intent = Intent(this@MainActivity, ReadingActivity::class.java).apply {
                        putExtra("EXTRA_TITLE", result.title)
                        putExtra("EXTRA_CONTENT", result.content)
                        putExtra("EXTRA_URL", url)
                        putExtra("EXTRA_NEXT_CHAPTER", result.nextChapterUrl ?: "")
                    }
                    startActivity(intent)
                }
                is UrlLoader.LoadResult.Error -> {
                    loadingView.visibility = View.GONE
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkClipboardForNovelUrl() {
        val url = ClipboardHelper.detectNovelUrl(this) ?: return

        val lastShown = prefs.getString(KEY_LAST_CLIP_PROMPT, "")
        if (url == lastShown) return

        prefs.edit().putString(KEY_LAST_CLIP_PROMPT, url).apply()

        AlertDialog.Builder(this)
            .setTitle("检测到小说链接")
            .setMessage("剪贴板中检测到链接：\n$url\n\n是否作为小说打开？")
            .setPositiveButton("打开") { _, _ ->
                loadUrlOnCurrentPage(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val KEY_LAST_CLIP_PROMPT = "last_clip_prompt"
    }
}
