package com.example.novelreader

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

class GestureCameraOverlay(private val context: Context) {

    companion object {
        private const val TAG = "GestureCamera"
        private const val MIN_TRACKING_FRAMES = 4
        private const val MAX_TRACKING_FRAMES = 20
        // 连续丢失手腕的帧数上限，超过才清空追踪（容忍偶发丢帧，避免追踪频繁被打断）
        private const val MAX_MISS_FRAMES = 3
        // 时间窗口：只保留最近 900ms 的样本。挥手动作通常 300~500ms，
        // 窗口过长会让静止帧稀释速度估计，表现为"要挥好几下才触发"
        private const val WINDOW_MS = 900L
        // 相邻两帧间超过该跳变（归一化 x）且间隔 <100ms，判定为 ML Kit 检测噪声，丢弃该帧
        private const val MAX_JUMP_PER_FRAME = 0.25f
        // 帧间位移小于该值视为静止噪声，不参与单调性统计
        private const val NOISE_EPS = 0.004f
        // 单调性门槛：与净位移同向的有效帧间移动占比，过滤来回晃动造成的误触发
        private const val MIN_MONO_RATIO = 0.6f
    }

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onGestureStateChanged: ((isActive: Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var cameraProvider: ProcessCameraProvider? = null

    // 当前使用的摄像头方向。前置摄像头的 ImageAnalysis 提供的是未经镜像的传感器原始图像
    // （镜像只发生在 Preview 显示层，本组件未使用 Preview）。detectSwipe() 需据此翻转
    // x 方向判定，使 onSwipeLeft/onSwipeRight 回调语义与用户感知的手势方向一致。
    private val lensFacing = CameraSelector.LENS_FACING_FRONT

    private var isRunning = false
    private var hasCameraPermission = false
    private var missCount = 0

    // 当前追踪的手腕（PoseLandmark.LEFT_WRIST / RIGHT_WRIST，-1 表示未锁定）。
    // 左右手腕的 x 坐标差异很大，逐帧在两手间 fallback 会让位置序列剧烈跳变，
    // 直接污染摆幅/位移计算。锁定单只手腕，确需切换时清空窗口重新积累。
    private var trackedWrist = -1

    // 触发手势后的硬锁定期：在此时间戳之前，processFrame 完全不积累帧。
    // 这样"挥手+回手"复合动作中的回手阶段被整体忽略，避免翻页后立即误触发反向翻页。
    // 锁定期时长由 setSensitivity() 按档位设置（低灵敏度更长、高灵敏度更短）。
    private var gestureLockUntil = 0L
    private var gestureLockMs = 1200L

    // 可调的识别阈值（x 已归一化到 [0,1]），通过 setSensitivity() 三档切换
    private var minSwipeSpan = 0.12f
    private var minNetDisplacement = 0.04f
    private var swipeVelocityThreshold = 0.5f
    private var wristConfidence = 0.3f

    private val recentPositions = ArrayDeque<Pair<Float, Long>>()

    private val poseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        processFrame(imageProxy)
    }

    fun setPermissionGranted(granted: Boolean) {
        hasCameraPermission = granted
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        if (isRunning) return
        if (!hasCameraPermission) {
            Log.w(TAG, "Camera permission not granted")
            return
        }
        isRunning = true
        startCamera(lifecycleOwner)
        onGestureStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        recentPositions.clear()
        missCount = 0
        trackedWrist = -1
        gestureLockUntil = 0L
        onGestureStateChanged?.invoke(false)
    }

    fun isActive(): Boolean = isRunning

    /**
     * 设置手势识别灵敏度（0=低/防误触，1=中/默认，2=高/易触发）。
     * 同时调整识别阈值与翻页后的锁定时长：灵敏度越低，锁定期越长（更防误触发反向翻页）。
     */
    fun setSensitivity(level: Int) {
        when (level) {
            0 -> {
                minSwipeSpan = 0.16f
                minNetDisplacement = 0.06f
                swipeVelocityThreshold = 0.6f
                wristConfidence = 0.35f
                gestureLockMs = 1500L
            }
            2 -> {
                minSwipeSpan = 0.08f
                minNetDisplacement = 0.03f
                swipeVelocityThreshold = 0.35f
                wristConfidence = 0.25f
                gestureLockMs = 800L
            }
            else -> { // 1 或未知值默认中灵敏度
                minSwipeSpan = 0.12f
                minNetDisplacement = 0.04f
                swipeVelocityThreshold = 0.5f
                wristConfidence = 0.3f
                gestureLockMs = 1200L
            }
        }
    }

    fun destroy() {
        stop()
        poseDetector.close()
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("DEPRECATION")
    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // 降低采集分辨率以提升 ML Kit 处理帧率，更快凑齐追踪帧；
            // x 坐标已归一化，阈值不受分辨率影响。
            .setTargetResolution(android.util.Size(320, 240))
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        try {
            // bindToLifecycle 返回非空 Camera；失败时会抛异常（已被下方 catch 捕获），
            // 不会静默返回 null，因此无需额外 null 检查。
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
            isRunning = false
            onGestureStateChanged?.invoke(false)
            ContextCompat.getMainExecutor(context).execute {
                onError?.invoke("摄像头启动失败: ${e.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isRunning) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val imageWidth = imageProxy.width.toFloat()
        val inputImage =
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        poseDetector.process(inputImage)
            .addOnSuccessListener { pose ->
                if (!isRunning) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val now = SystemClock.elapsedRealtime()

                // 硬锁定期内完全忽略帧：防止"挥手触发翻页 → 回手"中的回手阶段
                // 被积累成新的滑动并误触发反向翻页（左滑下一页后回手→误判右滑→又翻回去）。
                if (now < gestureLockUntil) {
                    recentPositions.clear()
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val wrist = selectWrist(pose)

                if (wrist != null && wrist.inFrameLikelihood > wristConfidence) {
                    missCount = 0
                    // 归一化到 [0,1]，使位移/速度阈值与采集分辨率无关
                    val nx = if (imageWidth > 0f) wrist.position.x / imageWidth else wrist.position.x

                    // 单帧跳变过滤：手腕不可能在 <100ms 内横跨 1/4 画面，
                    // 出现即为 ML Kit 检测噪声，丢弃该帧但不打断既有追踪
                    val lastSample = recentPositions.lastOrNull()
                    val isOutlier = lastSample != null &&
                        kotlin.math.abs(nx - lastSample.first) > MAX_JUMP_PER_FRAME &&
                        now - lastSample.second < 100L

                    if (!isOutlier) {
                        recentPositions.addLast(nx to now)
                        // 时间窗口裁剪：只保留最近 WINDOW_MS 的样本，
                        // 避免挥手前的静止帧稀释速度估计（"要挥好几下"的主因之一）
                        while (recentPositions.isNotEmpty() &&
                            now - recentPositions.first().second > WINDOW_MS
                        ) {
                            recentPositions.removeFirst()
                        }
                        if (recentPositions.size > MAX_TRACKING_FRAMES) {
                            recentPositions.removeFirst()
                        }
                        detectSwipe()
                    }
                } else {
                    // 容忍偶发丢帧：只有连续 MAX_MISS_FRAMES 帧都丢失手腕才清空追踪，
                    // 避免阅读场景下手腕在画面边缘抖动导致追踪频繁被打断（这是识别"需要挥多下"的主因）。
                    missCount++
                    if (missCount >= MAX_MISS_FRAMES) {
                        if (recentPositions.isNotEmpty()) recentPositions.clear()
                        trackedWrist = -1
                    }
                }
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Pose detection failed", e)
                imageProxy.close()
            }
    }

    /**
     * 选择本帧用于追踪的手腕。锁定单只手腕持续追踪，避免左右手 x 坐标跳变污染窗口：
     * - 未锁定：取置信度更高的一只；
     * - 已锁定且该手腕本帧可见：继续沿用；
     * - 已锁定但该手腕丢失、另一只手清晰可见：切换并清空窗口重新积累。
     */
    private fun selectWrist(pose: Pose): PoseLandmark? {
        val left = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val right = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)

        fun visible(w: PoseLandmark?) = w != null && w.inFrameLikelihood > wristConfidence

        when (trackedWrist) {
            PoseLandmark.LEFT_WRIST -> if (visible(left)) return left
            PoseLandmark.RIGHT_WRIST -> if (visible(right)) return right
        }

        // 锁定手腕丢失（或未锁定）：选置信度更高的一只
        val candidate = when {
            trackedWrist == PoseLandmark.LEFT_WRIST && visible(right) -> right
            trackedWrist == PoseLandmark.RIGHT_WRIST && visible(left) -> left
            trackedWrist == -1 -> listOfNotNull(left, right)
                .filter { it.inFrameLikelihood > wristConfidence }
                .maxByOrNull { it.inFrameLikelihood }
            else -> null
        }

        if (candidate != null) {
            if (trackedWrist != -1 && trackedWrist != candidate.landmarkType) {
                // 切换手腕：左右手 x 坐标差异大，直接混用会污染窗口，清空重积
                recentPositions.clear()
            }
            trackedWrist = candidate.landmarkType
        }
        return candidate
    }

    private fun detectSwipe() {
        if (recentPositions.size < MIN_TRACKING_FRAMES) return

        val now = SystemClock.elapsedRealtime()
        // 锁定期已在 processFrame 入口拦截，此处作为冗余兜底
        if (now < gestureLockUntil) return

        val n = recentPositions.size

        // 摆幅用截尾极值：丢弃最大/最小各一帧，抗单帧噪声（样本少时退化为普通极值）
        val xs = recentPositions.map { it.first }.sorted()
        val trim = if (n >= 6) 1 else 0
        val span = xs[n - 1 - trim] - xs[trim]
        if (span < minSwipeSpan) return

        // 净位移用端点均值（首 2 帧均值 → 尾 2 帧均值），比单点首尾差抗噪声
        val headAvg = (recentPositions.first().first + recentPositions.elementAt(1).first) / 2f
        val tailAvg = (recentPositions.elementAt(n - 2).first + recentPositions.last().first) / 2f
        val dx = tailAvg - headAvg
        // 净位移过小（来回大致抵消）时方向歧义，不触发
        if (kotlin.math.abs(dx) < minNetDisplacement) return

        // 速度用最小二乘拟合斜率（x/秒）：利用窗口内全部样本，
        // 比"摆幅/时长"抗噪声，也不会被窗口内静止段稀释
        val t0 = recentPositions.first().second
        var sumT = 0.0; var sumX = 0.0; var sumTT = 0.0; var sumTX = 0.0
        for ((x, t) in recentPositions) {
            val ts = (t - t0) / 1000.0
            sumT += ts; sumX += x; sumTT += ts * ts; sumTX += ts * x
        }
        val denom = n * sumTT - sumT * sumT
        if (denom < 1e-9) return
        val slope = ((n * sumTX - sumT * sumX) / denom).toFloat()
        if (kotlin.math.abs(slope) < swipeVelocityThreshold) return

        // 方向一致性：拟合斜率与端点位移必须同号，否则动作形状矛盾
        if (slope * dx <= 0f) return

        // 单调性校验：真实挥手近似单向移动，来回晃动/抖动在该比例上不达标
        val dir = if (dx > 0f) 1f else -1f
        var agree = 0; var total = 0
        var prevX = recentPositions.first().first
        for (i in 1 until n) {
            val cx = recentPositions.elementAt(i).first
            val d = cx - prevX
            if (kotlin.math.abs(d) > NOISE_EPS) {
                total++
                if (d * dir > 0f) agree++
            }
            prevX = cx
        }
        if (total < 2) return
        if (agree.toFloat() / total < MIN_MONO_RATIO) return

        // 触发：清空追踪并进入硬锁定期，期间完全忽略帧（防止回手误触发反向翻页）
        gestureLockUntil = now + gestureLockMs
        recentPositions.clear()
        trackedWrist = -1

        // 方向用净位移 dx（前置摄像头需翻转：原始图像 x 增大 = 用户向左挥手）
        val movingLeft =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) dx > 0 else dx < 0

        if (movingLeft) {
            Log.d(TAG, "Swipe LEFT (span=$span, dx=$dx, v=$slope/s, mono=$agree/$total, lock=${gestureLockMs}ms)")
            ContextCompat.getMainExecutor(context).execute {
                onSwipeLeft?.invoke()
            }
        } else {
            Log.d(TAG, "Swipe RIGHT (span=$span, dx=$dx, v=$slope/s, mono=$agree/$total, lock=${gestureLockMs}ms)")
            ContextCompat.getMainExecutor(context).execute {
                onSwipeRight?.invoke()
            }
        }
    }
}
