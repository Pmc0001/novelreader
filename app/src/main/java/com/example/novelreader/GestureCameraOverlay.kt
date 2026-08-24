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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.Executors

/**
 * 摄像头手势翻页（无 Preview）。
 *
 * MediaPipe GestureRecognizer 离散手形识别：
 *  - 比赞 (Thumb_Up) → 下一页（onSwipeLeft）
 *  - 比耶 (Victory)   → 上一页（onSwipeRight）
 *
 * 速度/误触控制：
 *  1) 背压 inferenceInFlight：LIVE_STREAM 异步下同一时间只让一帧推理在飞，
 *     前帧结果回来才喂下一帧，避免喂帧过快导致结果排队滞后（"识别慢"主因）。
 *  2) 单帧即触发 + 置信度≥0.7：手形离散、高置信单帧即可信，省去保持时长延迟。
 *  3) 冷却 cooldownMs + 释放再触发：触发后须手离开/换非动作手形才能再触发，长按只翻一页。
 *  4) 降低跟踪/存在置信度，让跟踪别频繁掉线（少跑全量检测，更跟手）。
 *
 * 公共 API 与旧实现保持一致，ReadingActivity / SettingsActivity 无需改动。
 */
class GestureCameraOverlay(private val context: Context) {

    companion object {
        private const val TAG = "GestureCamera"
        private const val CATEGORY_THUMB_UP = "Thumb_Up"   // 比赞 → 下一页
        private const val CATEGORY_VICTORY = "Victory"      // 比耶 → 上一页
        private const val DEFAULT_COOLDOWN_MS = 700L
        private const val MIN_FIRE_SCORE = 0.7f             // 单帧触发置信度门槛
    }

    var onSwipeLeft: (() -> Unit)? = null   // 下一页
    var onSwipeRight: (() -> Unit)? = null  // 上一页
    var onGestureStateChanged: ((isActive: Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val lensFacing = CameraSelector.LENS_FACING_FRONT

    private var isRunning = false
    private var hasCameraPermission = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizer? = null

    // 触发后的冷却到期时间
    private var cooldownUntilMs = 0L
    // 触发后要求手离开/换成非动作手形才允许再次触发
    private var expectRelease = false
    private val cooldownMs = DEFAULT_COOLDOWN_MS

    // 背压：true=有一帧推理在飞，期间丢弃新帧，结果回来(handleResult)才清零
    @Volatile private var inferenceInFlight = false

    // LIVE_STREAM 要求时间戳单调递增(ms)，用 uptimeMillis 并强制 +1 防同帧相等
    @Volatile private var lastTsMs = 0L

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
        if (recognizer == null) {
            // 优先 GPU delegate（推理快得多），初始化失败自动回退 CPU
            recognizer = createRecognizer(useGpu = true) ?: createRecognizer(useGpu = false)
            if (recognizer == null) {
                isRunning = false
                ContextCompat.getMainExecutor(context).execute {
                    onError?.invoke("手势识别初始化失败")
                }
                return
            }
        }
        isRunning = true
        expectRelease = false
        cooldownUntilMs = 0L
        inferenceInFlight = false
        startCamera(lifecycleOwner)
        onGestureStateChanged?.invoke(true)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        expectRelease = false
        cooldownUntilMs = 0L
        inferenceInFlight = false
        onGestureStateChanged?.invoke(false)
    }

    fun isActive(): Boolean = isRunning

    fun destroy() {
        stop()
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close recognizer failed", e)
        }
        recognizer = null
    }

    private fun createRecognizer(useGpu: Boolean): GestureRecognizer? {
        return try {
            val delegate = if (useGpu) Delegate.GPU else Delegate.CPU
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .setDelegate(delegate)
                    .build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { e -> Log.w(TAG, "recognize error", e) }
                .setNumHands(1)
                // 降低跟踪/存在置信度，让跟踪尽量不掉线（少跑全量手部检测，更跟手）
                .setMinHandDetectionConfidence(0.4f)
                .setMinHandPresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .build()
            GestureRecognizer.createFromOptions(context, options).also {
                Log.d(TAG, "GestureRecognizer ready, delegate=$delegate")
            }
        } catch (e: Exception) {
            Log.w(TAG, "createRecognizer(gpu=$useGpu) failed: ${e.localizedMessage}")
            null
        }
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
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
            // RGBA_8888：imageProxy.toBitmap() 返回独立副本，close 后仍可用
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // 降分辨率提帧率、减每帧拷贝；手形分类对分辨率不敏感
            .setTargetResolution(android.util.Size(256, 192))
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        try {
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
        try {
            if (!isRunning) return
            val rec = recognizer
            if (rec == null) {
                imageProxy.close()
                return
            }
            // 背压：前一帧推理未回结果前，丢弃新帧（finally 仍会 close），
            // 保证结果永远跟最新帧、不排队滞后
            if (inferenceInFlight) return
            // toBitmap() 返回独立 Bitmap 副本，与 ImageProxy 生命周期解耦
            val raw = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            // 旋转经 ImageProcessingOptions 交给识别器内部处理，省去每帧 Matrix 旋转 + Bitmap 分配
            val mpImage = BitmapImageBuilder(raw).build()
            val opts = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTsMs) ts = lastTsMs + 1
            lastTsMs = ts
            inferenceInFlight = true
            rec.recognizeAsync(mpImage, opts, ts)
        } catch (e: Exception) {
            inferenceInFlight = false
            Log.w(TAG, "recognize failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: GestureRecognizerResult) {
        // 释放背压：允许喂下一帧
        inferenceInFlight = false
        if (!isRunning) return
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntilMs) return

        val topGesture = result.gestures().firstOrNull()?.firstOrNull()
        val top = topGesture?.categoryName()
        val score = topGesture?.score() ?: 0f
        // 单帧即触发，但要求较高置信度过滤噪声
        val actionable = (top == CATEGORY_THUMB_UP || top == CATEGORY_VICTORY) && score >= MIN_FIRE_SCORE

        if (expectRelease) {
            // 触发后必须先"释放"（非动作手形/无手）才允许再次触发
            if (!actionable) expectRelease = false
            return
        }
        if (actionable) {
            fire(top)
            cooldownUntilMs = now + cooldownMs
            expectRelease = true
        }
    }

    private fun fire(category: String?) {
        when (category) {
            CATEGORY_THUMB_UP -> {
                Log.d(TAG, "Thumb_Up -> next page (score>=${MIN_FIRE_SCORE}, cd=${cooldownMs}ms)")
                ContextCompat.getMainExecutor(context).execute { onSwipeLeft?.invoke() }
            }
            CATEGORY_VICTORY -> {
                Log.d(TAG, "Victory -> prev page (score>=${MIN_FIRE_SCORE}, cd=${cooldownMs}ms)")
                ContextCompat.getMainExecutor(context).execute { onSwipeRight?.invoke() }
            }
        }
    }
}
