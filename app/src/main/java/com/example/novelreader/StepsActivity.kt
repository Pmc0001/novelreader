package com.example.novelreader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 今日步数页：仅基于 Health Connect 统计（唯一数据源）。
 *
 * Health Connect 是系统级健康数据仓库，记录带时间戳的步数（StepsRecord），
 * 可直接查询「今天 00:00 ～ 现在」的步数求和，口径与微信运动“0 点重置”一致，
 * 且包含打开 App 之前走的步数、以及其它写入 HC 的健康 App/穿戴来源。
 *
 * 不依赖设备 TYPE_STEP_COUNTER 传感器（该传感器只给“自开机累计”、无历史、午夜不清零）。
 * 设备需 Android 8.0+ 且系统带有 Health Connect 模块；否则本页提示“不支持”。
 */
class StepsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var tvSteps: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvCalories: TextView
    private lateinit var tvGoalStatus: TextView
    private lateinit var progressGoal: ProgressBar
    private lateinit var layoutPermission: View
    private lateinit var tvPermissionHint: TextView
    private lateinit var btnGrant: View

    // Health Connect（唯一数据源，仅 Android 8.0+ / API 26+ 可用）
    private var healthConnectClient: HealthConnectClient? = null
    private var healthPermissionLauncher: ActivityResultLauncher<Set<String>>? = null
    private var useHealthConnect = false   // 是否使用 Health Connect 作为数据源
    private var healthGranted = false      // Health Connect 步数权限是否已授予
    private var healthSteps: Long? = null  // Health Connect 读取的今日步数

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (useHealthConnect && healthGranted) {
                lifecycleScope.launch { readStepsFromHealthConnect() }
                refreshHandler.postDelayed(this, 30_000)
            }
        }
    }

    companion object {
        const val TAG = "StepsActivity"
        const val STEP_PREFS = "step_prefs"
        const val KEY_HC_GRANTED = "hc_granted"     // Health Connect 步数授权本地记忆（避免反复弹授权）
        const val DAILY_GOAL = 8000

        // 经验估算常数（基于步数推算距离/卡路里，HC 不提供这两项）
        const val STRIDE_METERS = 0.715   // 平均步幅
        const val KCAL_PER_STEP = 0.04     // 每步约消耗千卡
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_steps)

        enableEdgeToEdge()
        // 顶栏为深色背景，状态栏图标使用浅色以保证可见
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        applyCutoutInsets()

        prefs = getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE)

        tvSteps = findViewById(R.id.tv_steps)
        tvDistance = findViewById(R.id.tv_distance)
        tvCalories = findViewById(R.id.tv_calories)
        tvGoalStatus = findViewById(R.id.tv_goal_status)
        progressGoal = findViewById(R.id.progress_goal)
        layoutPermission = findViewById(R.id.layout_permission)
        tvPermissionHint = findViewById(R.id.tv_permission_hint)
        btnGrant = findViewById(R.id.btn_grant)

        progressGoal.max = DAILY_GOAL

        findViewById<View>(R.id.steps_back).setOnClickListener { finish() }
        btnGrant.setOnClickListener {
            val perm = setOf(HealthPermission.getReadPermission(StepsRecord::class))
            val launcher = healthPermissionLauncher
            if (launcher == null) {
                // 合约注册失败（个别 ROM）：绝不能静默无反应，直接走设置兜底
                Log.w(TAG, "授权合约未注册成功，转系统设置兜底")
                openHealthConnectSettings()
                return@setOnClickListener
            }
            try {
                launcher.launch(perm)
            } catch (e: Exception) {
                // launch 抛异常同样兜底，保证点击必有响应
                Log.w(TAG, "拉起授权界面失败，转系统设置兜底", e)
                openHealthConnectSettings()
            }
        }

        // Health Connect 客户端与权限请求器（仅 Android 8.0+ / API 26+，HC 需要较新系统）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            healthConnectClient = try {
                HealthConnectClient.getOrCreate(this)
            } catch (e: Exception) {
                null
            }
            healthPermissionLauncher = try {
                registerForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { granted ->
                    val perm = HealthPermission.getReadPermission(StepsRecord::class)
                    if (granted.contains(perm)) {
                        // 授权成功：本地记忆 + 直接读取今日步数
                        prefs.edit().putBoolean(KEY_HC_GRANTED, true).apply()
                        useHealthConnect = true
                        healthGranted = true
                        layoutPermission.visibility = View.GONE
                        lifecycleScope.launch { readStepsFromHealthConnect() }
                    } else {
                        lifecycleScope.launch {
                            // 关键修正：部分设备在权限“已授予”时，授权页会无 UI 秒退并返回空结果，
                            // 这不代表被拒绝。先复核真实授权状态，已授予则直接读取，不要跳设置页。
                            if (hasHealthPermission()) {
                                Log.d(TAG, "授权页秒退但权限实际已授予，直接读取")
                                prefs.edit().putBoolean(KEY_HC_GRANTED, true).apply()
                                useHealthConnect = true
                                healthGranted = true
                                layoutPermission.visibility = View.GONE
                                readStepsFromHealthConnect()
                            } else {
                                // 确认未授予：兜底引导到系统设置手动授权
                                openHealthConnectSettings()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "注册 HealthConnect 授权合约失败", e)
                null
            }
        }

        updateUI()
        checkHealthConnect()
    }

    private fun showUnsupported() {
        layoutPermission.visibility = View.VISIBLE
        btnGrant.visibility = View.GONE
        tvPermissionHint.setTextColor(ContextCompat.getColor(this, R.color.error))
        tvPermissionHint.text = "当前设备不支持 Health Connect，无法统计步数"
        updateUI()
    }

    /**
     * 检测 Health Connect 可用性并决定取数：
     * - SDK_AVAILABLE 且已授权 → 查询今日步数；
     * - SDK_AVAILABLE 但未授权 → 显示授权提示；
     * - 不可用（系统低于 Android 8.0 / 无 HC 模块）→ 提示不支持。
     */
    private fun checkHealthConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || healthConnectClient == null) {
            useHealthConnect = false
            showUnsupported()
            return
        }
        lifecycleScope.launch {
            val status = try {
                HealthConnectClient.getSdkStatus(this@StepsActivity)
            } catch (e: Exception) {
                -1
            }
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                useHealthConnect = true
                Log.d(TAG, "HealthConnect 可用(status=$status)，作为唯一数据源")
                // 诊断信息：部分 ROM 该接口不可靠，仅用于日志比对，不作为判定依据
                val grantedNow = healthConnectClient?.permissionController?.getGrantedPermissions()
                Log.d(TAG, "getGrantedPermissions=$grantedNow")
                val locallyGranted = prefs.getBoolean(KEY_HC_GRANTED, false)
                if (locallyGranted) {
                    // 本地已记录授权：直接读取，不再每次进入都弹授权提示（打断重复授权循环）
                    healthGranted = true
                    layoutPermission.visibility = View.GONE
                    readStepsFromHealthConnect()
                } else {
                    // 不依赖 getGrantedPermissions（部分 ROM 该接口返回不可靠，会谎报未授权）：
                    // 直接尝试读取今日步数，成功即视为已授权；SecurityException 才显示授权提示，
                    // 其它异常走 showHealthErrorPrompt，避免把服务异常误报成“需要授权”。
                    readStepsFromHealthConnect()
                }
            } else {
                useHealthConnect = false
                Log.d(TAG, "HealthConnect 不可用(status=$status)")
                showUnsupported()
            }
        }
    }

    private suspend fun hasHealthPermission(): Boolean {
        val perm = HealthPermission.getReadPermission(StepsRecord::class)
        return try {
            healthConnectClient?.permissionController?.getGrantedPermissions()?.contains(perm) == true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readStepsFromHealthConnect() {
        try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val end = Instant.now()
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            val response = healthConnectClient?.readRecords(request) ?: return
            healthSteps = response.records.sumOf { it.count }
            Log.d(TAG, "HealthConnect 读取今日步数=${healthSteps}（记录数=${response.records.size}）")
            healthGranted = true
            prefs.edit().putBoolean(KEY_HC_GRANTED, true).apply()
            layoutPermission.visibility = View.GONE
            updateUI()
        } catch (e: SecurityException) {
            // 真正的未授权：显示授权提示
            Log.w(TAG, "HealthConnect 未授权(SecurityException)", e)
            healthGranted = false
            showHealthPermissionPrompt()
        } catch (e: Exception) {
            // 非权限类异常（HC 服务异常/IPC 失败等）：不能误导用户去重复授权，
            // 否则表现为“已同意过还一直要权限、点授权又无反应”的死循环
            Log.w(TAG, "HealthConnect 读取异常(非权限问题, exception=${e.javaClass.simpleName})", e)
            healthGranted = false
            showHealthErrorPrompt(e)
        }
    }

    private fun showHealthPermissionPrompt() {
        layoutPermission.visibility = View.VISIBLE
        btnGrant.visibility = View.VISIBLE
        btnGrant.isEnabled = true
        tvPermissionHint.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        tvPermissionHint.text = "需要授权读取健康数据（Health Connect）。点击「授予权限」将弹出系统授权窗口，授权后即可统计今日步数（与微信运动同源）"
        updateUI()
    }

    private fun showHealthErrorPrompt(e: Exception) {
        layoutPermission.visibility = View.VISIBLE
        btnGrant.visibility = View.VISIBLE
        btnGrant.isEnabled = true
        tvPermissionHint.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        tvPermissionHint.text = "Health Connect 服务异常（${e.javaClass.simpleName}），不是权限问题。可点击「检查设置」到系统健康设置中确认本应用状态，或稍后重试"
        updateUI()
    }

    /**
     * 引导用户到 Health Connect 权限授权入口。
     *
     * 优先使用官方常量 ACTION_HEALTH_CONNECT_SETTINGS（Android 14+ 可直接深链到「本应用」权限页，
     * 用户可一键授权）；只有在该 action 在当前 ROM 上无法解析时，才兜底打开系统设置首页并给出手动路径。
     * 用户在系统侧授权后返回本页，onResume 会自动检测并读取步数。
     */
    private fun openHealthConnectSettings() {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        intent.putExtra("android.intent.extra.PACKAGE_NAME", packageName)
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // 解析到但启动仍失败，继续走下方兜底
            }
        }
        // 定制 ROM：改开系统设置首页并提示手动路径
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            tvPermissionHint.text = "已打开系统设置。请进入：健康 / Health Connect → 本应用（小说阅读器）→ 开启“步数”权限，返回后自动生效"
        } catch (e: Exception) {
            tvPermissionHint.text = "请在系统设置 → 健康(Health Connect) → 本应用 中手动开启“步数”读取权限，返回后自动生效"
        }
    }

    private fun updateUI() {
        val steps = healthSteps ?: 0L
        tvSteps.text = steps.toString()

        val distanceKm = (steps * STRIDE_METERS) / 1000.0
        tvDistance.text = String.format(Locale.US, "%.2f", distanceKm)

        val calories = steps * KCAL_PER_STEP
        tvCalories.text = String.format(Locale.US, "%.0f", calories)

        progressGoal.progress = steps.coerceAtMost(DAILY_GOAL.toLong()).toInt()
        tvGoalStatus.text = if (steps >= DAILY_GOAL) {
            "已达成今日目标"
        } else {
            "距目标还差 ${DAILY_GOAL - steps} 步"
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统 Health Connect 设置页/授权页返回后，重新检测授权是否已在系统侧生效。
        // 不只信 getGrantedPermissions（部分 ROM 谎报）：直接尝试读取，以读取结果为准。
        if (useHealthConnect && !healthGranted) {
            lifecycleScope.launch {
                if (hasHealthPermission()) {
                    prefs.edit().putBoolean(KEY_HC_GRANTED, true).apply()
                    healthGranted = true
                    layoutPermission.visibility = View.GONE
                    readStepsFromHealthConnect()
                } else {
                    // 接口谎报未授权时，读一次验证：成功则视为已授权，失败会给出准确提示
                    readStepsFromHealthConnect()
                }
            }
        }
        if (useHealthConnect && healthGranted) {
            refreshHandler.postDelayed(refreshRunnable, 30_000)
        }
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    /**
     * 全面屏/挖孔适配：
     * - 顶栏加 statusBar 顶部留白，避免被状态栏/挖孔遮挡；
     * - ScrollView 加 navigationBars 底部留白，避免被导航栏手势条遮挡。
     */
    private fun applyCutoutInsets() {
        val topBar = findViewById<View>(R.id.top_bar)
        val scrollView = findViewById<View>(R.id.scroll_view)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = sb.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nb.bottom)
            insets
        }
    }
}
