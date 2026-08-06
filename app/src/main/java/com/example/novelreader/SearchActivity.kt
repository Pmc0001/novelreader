package com.example.novelreader

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnBack: ImageView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var loadingView: PremiumLoadingView
    private var searchJob: Job? = null

    /** 聚合行：跨书源搜索结果，bookUrl 交给 ReadingActivity 加载 */
    private data class SearchRow(
        val title: String,
        val bookUrl: String,
        val sourceName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        initViews()
        // 进入即聚焦并弹出键盘
        etSearch.requestFocus()
        window?.decorView?.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // 深链自动搜索（无此 extra 时不触发，主页入口不受影响）
        val deepQuery = intent.getStringExtra("EXTRA_QUERY")
        if (!deepQuery.isNullOrBlank()) {
            etSearch.setText(deepQuery)
            etSearch.postDelayed({ doSearch() }, 300)
        }
    }

    private fun initViews() {
        etSearch = findViewById(R.id.et_search)
        btnSearch = findViewById(R.id.btn_search)
        btnBack = findViewById(R.id.btn_search_back)
        resultsContainer = findViewById(R.id.search_results)
        statusText = findViewById(R.id.search_status)
        loadingView = findViewById(R.id.search_loading)

        btnBack.setOnClickListener { finish() }
        btnSearch.setOnClickListener { doSearch() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    private fun doSearch() {
        val q = etSearch.text.toString().trim()
        if (q.isBlank()) {
            statusText.text = "请输入书名或作者"
            statusText.visibility = View.VISIBLE
            resultsContainer.removeAllViews()
            return
        }
        // 收起键盘
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

        resultsContainer.removeAllViews()
        statusText.visibility = View.GONE
        loadingView.visibility = View.VISIBLE

        // 复用统一书源注册表（内置 + 用户自定义），并发搜索所有源
        val sources = SourceManager.all(this)
        val loader = UrlLoader(this)
        val sem = Semaphore(2)
        searchJob = lifecycleScope.launch {
            val rows = coroutineScope {
                sources.map { src ->
                    async {
                        sem.acquire()
                        try {
                            when (val r = loader.searchBooks(src, q)) {
                                is UrlLoader.SearchResult.Success ->
                                    r.items.map { SearchRow(it.title, it.url, src.name) }
                                is UrlLoader.SearchResult.Error -> emptyList()
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll().flatten()
            }
            loadingView.visibility = View.GONE
            cleanupWebViews()
            if (rows.isEmpty()) {
                statusText.text = "没有找到相关小说，可尝试更换书名，或该书源暂时无法访问"
                statusText.visibility = View.VISIBLE
            } else {
                renderResults(rows)
            }
        }
    }

    private fun renderResults(list: List<SearchRow>) {
        resultsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        // 按书源分组展示，便于对比不同站点结果
        val bySource = list.groupBy { it.sourceName }
        for ((sourceName, items) in bySource) {
            val header = TextView(this).apply {
                text = sourceName
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.textSecondary))
                setPadding(
                    (20 * density).toInt(),
                    (16 * density).toInt(),
                    0,
                    (8 * density).toInt()
                )
                typeface = Typeface.DEFAULT_BOLD
            }
            resultsContainer.addView(header)
            for (item in items) {
                resultsContainer.addView(buildResultCard(item, density))
            }
        }
    }

    private fun buildResultCard(item: SearchRow, density: Float): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * density).toInt()
                marginEnd = (16 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@SearchActivity, R.drawable.bg_card)
            elevation = 4 * density
            setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        // 书籍图标（取书名首字）
        val icon = TextView(this).apply {
            text = item.title.firstOrNull()?.toString() ?: "书"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.textOnPrimary))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@SearchActivity, R.drawable.bg_icon_circle)
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                (44 * density).toInt()
            ).apply { marginEnd = (14 * density).toInt() }
        }
        card.addView(icon)

        // 文本列（书名）
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
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textLayout.addView(tvTitle)
        card.addView(textLayout)

        // 来源标签
        val sourceTag = TextView(this).apply {
            text = item.sourceName
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.colorPrimary))
            setPadding((12 * density).toInt(), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(sourceTag)

        card.setOnClickListener {
            openBook(item)
        }
        return card
    }

    private fun openBook(item: SearchRow) {
        val intent = Intent(this, ReadingActivity::class.java).apply {
            putExtra("EXTRA_BOOK_URL", item.bookUrl)
            putExtra("EXTRA_BOOK_TITLE", item.title)
        }
        startActivity(intent)
    }

    override fun onPause() {
        super.onPause()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        // 先取消搜索协程（停止新的 evaluateJavascript），再暂停后台 WebView。
        // 切勿在此 destroy：销毁仍在 evaluateJavascript 的 WebView 会触发 native 崩溃（点开即闪退的根因）
        searchJob?.cancel()
        pauseWebViews()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        cleanupWebViews()
        super.onDestroy()
    }

    /** 暂停（不销毁）content 下所有搜索 WebView，避免活跃 WebView 被销毁引发 native 崩溃 */
    private fun pauseWebViews() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        root?.let { pauseRecursively(it) }
    }

    private fun pauseRecursively(vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is WebView) {
                child.pauseTimers()
                child.onPause()
            } else if (child is ViewGroup) {
                pauseRecursively(child)
            }
        }
    }

    /** 递归移除并销毁 content 下所有由搜索创建的 WebView */
    private fun cleanupWebViews() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        root?.let { removeWebViewsRecursively(it) }
    }

    private fun removeWebViewsRecursively(vg: ViewGroup) {
        for (i in vg.childCount - 1 downTo 0) {
            val child = vg.getChildAt(i)
            if (child is WebView) {
                vg.removeView(child)
                child.stopLoading()
                child.destroy()
            } else if (child is ViewGroup) {
                removeWebViewsRecursively(child)
            }
        }
    }
}
