package com.example.novelreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.app.DownloadManager

/**
 * 文件下载管理页：列出搜索页 WebView 里下载过的文件。
 * - 状态凭 DownloadManager 实时查询（下载中百分比 / 已完成 / 失败原因）；
 * - 点已完成项 → 用系统应用打开（TXT/EPUB 可直接用本书打开）；
 * - 点失败项 → 用原始链接重新下载；
 * - 删除 → 取消/移除下载任务并清理记录。
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        listContainer = findViewById(R.id.downloads_list)
        tvEmpty = findViewById(R.id.tv_downloads_empty)
        findViewById<ImageButton>(R.id.btn_downloads_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_downloads_refresh).setOnClickListener { renderList() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.top_bar)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = sb.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.downloads_scroll)) { v, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nb.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun downloadManager(): DownloadManager =
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun renderList() {
        listContainer.removeAllViews()
        val records = DownloadRepo.list(this)
        tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        val dm = downloadManager()
        records.forEach { record ->
            val item = layoutInflater.inflate(R.layout.item_download, listContainer, false)
            val body = item.findViewById<View>(R.id.download_item_body)
            val tvName = item.findViewById<TextView>(R.id.tv_download_name)
            val tvStatus = item.findViewById<TextView>(R.id.tv_download_status)
            val btnRemove = item.findViewById<TextView>(R.id.btn_download_remove)

            tvName.text = record.fileName
            val st = queryStatus(dm, record.dmId)

            when (st.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    tvStatus.text = if (record.fileName.endsWith(".txt", true)) {
                        "已完成 · ${formatSize(st.bytes)} · 点击用阅读页打开"
                    } else {
                        "已完成 · ${formatSize(st.bytes)} · 保存于 下载/NovelReader"
                    }
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.textPrimary))
                    body.setOnClickListener { openFile(st.localUri, record.fileName) }
                }
                DownloadManager.STATUS_FAILED -> {
                    tvStatus.text = "下载失败（${failureText(st.reason)}），点击重试"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                    body.setOnClickListener { retry(record) }
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    tvStatus.text = if (st.total > 0) {
                        "下载中 ${(st.bytes * 100 / st.total).coerceIn(0, 100)}%"
                    } else {
                        "下载中…"
                    }
                    body.setOnClickListener {
                        Toast.makeText(this, "文件正在下载，可在通知栏查看进度", Toast.LENGTH_SHORT).show()
                    }
                }
                DownloadManager.STATUS_PAUSED -> {
                    tvStatus.text = "已暂停（等待网络或存储就绪）"
                    body.setOnClickListener(null)
                }
                else -> {
                    tvStatus.text = "任务已失效，点击可重新下载"
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
                    body.setOnClickListener { retry(record) }
                }
            }

            btnRemove.setOnClickListener {
                runCatching { dm.remove(record.dmId) }
                DownloadRepo.remove(this, record.dmId)
                Toast.makeText(this, "已删除「${record.fileName}」", Toast.LENGTH_SHORT).show()
                renderList()
            }

            listContainer.addView(item)
        }
    }

    private data class DmStatus(
        val status: Int,
        val bytes: Long,
        val total: Long,
        val reason: Int,
        val localUri: Uri?
    )

    private fun queryStatus(dm: DownloadManager, id: Long): DmStatus {
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    DmStatus(
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    )
                } else {
                    DmStatus(-1, 0, 0, 0, null)
                }
            } ?: DmStatus(-1, 0, 0, 0, null)
        } catch (e: Exception) {
            DmStatus(-1, 0, 0, 0, null)
        }
    }

    /** 已完成：TXT 默认直接用本书阅读页打开；其他类型交给系统应用选择 */
    private fun openFile(uri: Uri?, fileName: String) {
        if (uri == null) {
            Toast.makeText(this, "文件位置不可用", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()

        if (ext == "txt") {
            // 走主页的 ACTION_VIEW 通道：自动解析为本地书、加入书架并进入阅读页
            val reader = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(reader)
            } catch (e: Exception) {
                Toast.makeText(this, "打开失败: ${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有能打开该文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 失败/失效：用原始链接重新排队下载 */
    private fun retry(record: DownloadRepo.Record) {
        val dm = downloadManager()
        return try {
            val request = DownloadManager.Request(Uri.parse(record.url)).apply {
                setTitle(record.fileName)
                setDescription("小说文件下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NovelReader/${record.fileName}")
                setAllowedOverMetered(true)
            }
            val newId = dm.enqueue(request)
            DownloadRepo.remove(this, record.dmId)
            DownloadRepo.add(this, DownloadRepo.Record(newId, record.fileName, record.url, System.currentTimeMillis()))
            Toast.makeText(this, "已重新开始下载", Toast.LENGTH_SHORT).show()
            renderList()
        } catch (e: Exception) {
            Toast.makeText(this, "重试失败: ${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun failureText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE, DownloadManager.ERROR_HTTP_DATA_ERROR -> "链接或网络异常"
        DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
        else -> "错误码 $reason"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}
