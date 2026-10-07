package com.scanner.app.ui.camera

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.data.util.ExifUtils
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.domain.model.ScannedPage
import com.scanner.app.engine.NativeBurstFusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * CameraViewModel: manages camera state, capture pipeline, and document detection.
 *
 * Capture modes (SPEC_16):
 *   - Normal: single-frame, fast shutter, [Normal] EXIF tag.
 *   - Full HDR: 4-frame pipeline (1 base EV0 + 1 highlight EV-2 + 2 sub-pixel EV0),
 *     producing ~50MP super-resolution output with multi-EV HDR tone mapping. [Full HDR] EXIF tag.
 *
 * Tap-to-focus is handled in CameraScreen via PreviewView.meteringPointFactory; the ViewModel
 * exposes `tapToFocus(x, y, previewWidth, previewHeight)` for decoupled trigger.
 */
class CameraViewModel : ViewModel() {

    private val _detectedQuad = MutableStateFlow<DetectionResult?>(null)
    val detectedQuad: StateFlow<DetectionResult?> = _detectedQuad.asStateFlow()

    private val _curvedModeEnabled = MutableStateFlow(false)
    val curvedModeEnabled: StateFlow<Boolean> = _curvedModeEnabled.asStateFlow()

    /** Full HDR: 4-frame (1+3 super-res + multi-EV highlight recovery). SPEC_16 §2. */
    private val _fullHdrEnabled = MutableStateFlow(false)
    val fullHdrEnabled: StateFlow<Boolean> = _fullHdrEnabled.asStateFlow()

    private val _isStable = MutableStateFlow(false)
    val isStable: StateFlow<Boolean> = _isStable.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    val capturedPages: StateFlow<List<ScannedPage>> = PageRepository.pages

    val pageCount: StateFlow<Int> = PageRepository.pages
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    var imageCapture: ImageCapture? = null
    var imageAnalysis: ImageAnalysis? = null
    var cameraControl: CameraControl? = null
    var cameraInfo: CameraInfo? = null
    var frameAnalyzer: com.scanner.app.data.camera.FrameAnalyzer? = null

    fun updateSettings(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _fullHdrEnabled.value = prefs.getBoolean("full_hdr_enabled", false)
    }

    fun toggleCurvedMode() {
        val newVal = !_curvedModeEnabled.value
        _curvedModeEnabled.value = newVal
        frameAnalyzer?.curvedMode = newVal
    }

    fun setTouchPoint(normX: Float, normY: Float) {
        frameAnalyzer?.touchPoint = PointF(normX, normY)
    }

    fun onFrameAnalyzed(result: DetectionResult) {
        val prev = _detectedQuad.value
        val newQuad = result.quad
        val isStableFrame = result.found

        _isStable.value = isStableFrame

        if (prev == null || newQuad == null || prev.quad == null) {
            lastQuad = newQuad
            _detectedQuad.value = result
            return
        }

        val smoothedQuad = smoothQuad(prev.quad!!, newQuad)
        lastQuad = smoothedQuad
        _detectedQuad.value = result.copy(quad = smoothedQuad)
    }

    private var lastQuad: DocumentQuad? = null

    private fun smoothQuad(prev: DocumentQuad, newQuad: DocumentQuad): DocumentQuad {
        fun dist(a: PointF, b: PointF) = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        val avgDist = listOf(
            dist(prev.topLeft, newQuad.topLeft),
            dist(prev.topRight, newQuad.topRight),
            dist(prev.bottomRight, newQuad.bottomRight),
            dist(prev.bottomLeft, newQuad.bottomLeft)
        ).average().toFloat()

        return if (avgDist > 80f) {
            newQuad
        } else if (avgDist < 2.5f) {
            prev
        } else {
            val alpha = 0.22f
            val beta = 1f - alpha
            DocumentQuad(
                topLeft = PointF(prev.topLeft.x * beta + newQuad.topLeft.x * alpha, prev.topLeft.y * beta + newQuad.topLeft.y * alpha),
                topRight = PointF(prev.topRight.x * beta + newQuad.topRight.x * alpha, prev.topRight.y * beta + newQuad.topRight.y * alpha),
                bottomRight = PointF(prev.bottomRight.x * beta + newQuad.bottomRight.x * alpha, prev.bottomRight.y * beta + newQuad.bottomRight.y * alpha),
                bottomLeft = PointF(prev.bottomLeft.x * beta + newQuad.bottomLeft.x * alpha, prev.bottomLeft.y * beta + newQuad.bottomLeft.y * alpha)
            )
        }
    }

    // ─────────────────────────────────────────────
    // Capture Entry Point
    // ─────────────────────────────────────────────

    fun capturePhoto(context: Context, onPageSaved: (String) -> Unit = {}) {
        if (_isCapturing.value) return
        val capture = imageCapture ?: return

        if (_fullHdrEnabled.value) {
            captureFullHdrPhoto(context, capture, onPageSaved)
        } else {
            captureSinglePhoto(context, capture, onPageSaved)
        }
    }

    // ─────────────────────────────────────────────
    // Normal Single-Frame Capture  (SPEC_16 §3)
    // ─────────────────────────────────────────────

    private fun captureSinglePhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        _isCapturing.value = true
        val storage = com.scanner.app.data.image.ImageStorage(context)
        val photoFile = File(storage.getStorageDir(), "${UUID.randomUUID()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        val executor = ContextCompat.getMainExecutor(context)

        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModelScope.launch(Dispatchers.Default) {
                        val (photoW, photoH) = normalizeExifOrientation(photoFile)
                        ExifUtils.stampSignature(photoFile, mode = "Normal")
                        val currentResult = _detectedQuad.value
                        val finalQuad = computeTargetQuad(currentResult, photoW, photoH)

                        val newPage = ScannedPage(
                            id = UUID.randomUUID().toString(),
                            originalImagePath = photoFile.absolutePath,
                            quad = finalQuad,
                            filter = ImageFilter.MAGIC_COLOR
                        )
                        PageRepository.addPage(newPage)
                        withContext(Dispatchers.Main) {
                            _isCapturing.value = false
                            onPageSaved(newPage.id)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    _isCapturing.value = false
                }
            }
        )
    }

    // ─────────────────────────────────────────────
    // Apple Deep Fusion / Smart HDR 7-Frame Unified Capture Pipeline
    //
    // Frame sequence (7 frames total):
    //   Tier 1 (4 frames, EV 0):
    //     Frames 0, 1, 2, 3: AE locked, base exposure & sub-pixel super-res anchors
    //   Tier 2 (2 frames, EV -2.5):
    //     Frames 4, 5: Manual midtone exposure transition
    //   Tier 3 (1 frame, EV -6.0):
    //     Frame 6: Manual deep highlight recovery
    //
    // 异步连拍架构 [SPEC_01 §7 / SPEC_02 §2.5]:
    // 1. 内存零磁盘连拍: 帧数据直接拉取至 RAM，避免每帧写磁盘造成的 IO 阻塞
    // 2. 拍摄后秒回取景: 连拍完成后瞬间停止转圈，立即恢复取景画面供用户继续操作
    // 3. 后台并发融合计算: 对齐与 Mertens 超分融合由后台协程异步处理
    // 4. 持久化存储规范: 照片直接存入 filesDir/scans/，不再占用系统 cacheDir 空间
    // ─────────────────────────────────────────────

    private fun captureFullHdrPhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        viewModelScope.launch {
            _isCapturing.value = true
            val control = cameraControl
            try {
                // 快速手抖稳定性确认 (至多等待 400ms 避免卡顿)
                if (!_isStable.value) {
                    withTimeoutOrNull(400L) {
                        _isStable.first { it }
                    }
                }

                val tier1Bytes = mutableListOf<ByteArray>()
                val tier2Bytes = mutableListOf<ByteArray>()
                val tier3Bytes = mutableListOf<ByteArray>()

                // ─────────────────────────────────────────────────────────────
                // Tier 1: 4 帧正常曝光 (EV 0) 内存快速连拍 (无磁盘 IO)
                // ─────────────────────────────────────────────────────────────
                for (i in 0 until 4) {
                    val bytes = takeSinglePictureInMemory(capture, context)
                    if (bytes != null && bytes.isNotEmpty()) {
                        tier1Bytes.add(bytes)
                    }
                }

                if (tier1Bytes.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }

                // 从内存 Frame 0 解析曝光参数
                val file0Bytes = tier1Bytes[0]
                val (baseIso, baseExpSec) = try {
                    val ex0 = ExifInterface(java.io.ByteArrayInputStream(file0Bytes))
                    val iso = ex0.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 800).coerceAtLeast(100)
                    val exp = parseExposureTime(ex0.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)) ?: 0.033
                    Pair(iso, exp)
                } catch (e: Exception) {
                    Pair(800, 0.033)
                }

                // Tier 2 (-2.5 EV): Midtone Transition
                val midIso = (baseIso / 4).coerceIn(100, 3200)
                val midExpSec = (baseExpSec / 3.0).coerceIn(1.0 / 2000.0, 1.0 / 30.0)
                val midExpNanos = (midExpSec * 1_000_000_000.0).toLong().coerceIn(500_000L, 33_333_333L)

                // Tier 3 (-6.0 EV): Deep Highlight Recovery (Desk lamp bulb & filaments)
                val shortIso = (midIso / 8).coerceIn(100, 800)
                val shortExpSec = (midExpSec / 4.0).coerceIn(1.0 / 4000.0, 1.0 / 250.0)
                val shortExpNanos = (shortExpSec * 1_000_000_000.0).toLong().coerceIn(250_000L, 4_000_000L)

                android.util.Log.i(
                    "HachiCam-Burst",
                    "Base Frame 0: ISO=$baseIso, Exp=${baseExpSec}s. " +
                    "Tier 2 Target: ISO=$midIso, Exp=${midExpSec}s ($midExpNanos ns). " +
                    "Tier 3 Target: ISO=$shortIso, Exp=${shortExpSec}s ($shortExpNanos ns)"
                )

                // ─────────────────────────────────────────────────────────────
                // Tier 2: 2 帧中灰曝光 (-2.5 EV) 内存快速连拍
                // ─────────────────────────────────────────────────────────────
                if (control != null) {
                    setManualCaptureOptions(control, context, midExpNanos, midIso)
                    delay(25) // Sensor register latch
                }
                for (i in 0 until 2) {
                    val bytes = takeSinglePictureInMemory(capture, context)
                    if (bytes != null && bytes.isNotEmpty()) {
                        tier2Bytes.add(bytes)
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Tier 3: 1 帧短曝光 (-6.0 EV) 内存快速连拍
                // ─────────────────────────────────────────────────────────────
                if (control != null) {
                    setManualCaptureOptions(control, context, shortExpNanos, shortIso)
                    delay(25) // Sensor register latch
                }
                for (i in 0 until 1) {
                    val bytes = takeSinglePictureInMemory(capture, context)
                    if (bytes != null && bytes.isNotEmpty()) {
                        tier3Bytes.add(bytes)
                    }
                }

                // 连拍获取完毕，立即恢复相机自动曝光
                if (control != null) {
                    clearManualCaptureOptions(control, context)
                }

                // ─────────────────────────────────────────────────────────────
                // 交互体验关键：连拍接收完成，立即解除转圈状态，立即恢复取景画面！
                // ─────────────────────────────────────────────────────────────
                _isCapturing.value = false
                frameAnalyzer?.resetStability()

                val allBytes = mutableListOf<ByteArray>().apply {
                    addAll(tier1Bytes)
                    addAll(tier2Bytes)
                    addAll(tier3Bytes)
                }

                val storage = com.scanner.app.data.image.ImageStorage(context)
                val pageId = UUID.randomUUID().toString()
                val finalPhotoFile = File(storage.getStorageDir(), "${pageId}.jpg")

                // 将 Frame 0 写入持久化存储 filesDir/scans/ 作为初始原图 (永不随系统清理 cacheDir 丢失)
                FileOutputStream(finalPhotoFile).use { fos ->
                    fos.write(file0Bytes)
                }
                val (photoW, photoH) = normalizeExifOrientation(finalPhotoFile)
                ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")

                val currentResult = _detectedQuad.value
                val finalQuad = computeTargetQuad(currentResult, photoW, photoH)

                val newPage = ScannedPage(
                    id = pageId,
                    originalImagePath = finalPhotoFile.absolutePath,
                    quad = finalQuad,
                    filter = ImageFilter.MAGIC_COLOR
                )
                PageRepository.addPage(newPage)
                onPageSaved(newPage.id)

                // ─────────────────────────────────────────────────────────────
                // 后台并发执行 Native 对齐与 Mertens 超分融合 (不阻塞主线程/取景)
                // ─────────────────────────────────────────────────────────────
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val deferredMats = allBytes.map { b ->
                            async(Dispatchers.IO) {
                                val mob = org.opencv.core.MatOfByte(*b)
                                val m = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR)
                                mob.release()
                                m
                            }
                        }
                        val mats = deferredMats.awaitAll().filter { it != null && !it.empty() }

                        if (mats.isNotEmpty()) {
                            val fusionEngine = NativeBurstFusion()
                            val fusedMat = fusionEngine.fuseBurstFrames(
                                burstFrames = mats,
                                removeGlare = true,
                                isScreenMode = true,
                                superResolution = true
                            )
                            if (!fusedMat.empty()) {
                                rotateAndSaveFusedMat(fusedMat, finalPhotoFile, finalPhotoFile, mode = "Full HDR", jpegQuality = 95)
                                val updatedPage = PageRepository.getPage(pageId)
                                if (updatedPage != null) {
                                    PageRepository.updatePage(updatedPage.copy(originalImagePath = finalPhotoFile.absolutePath))
                                }
                            }
                            for (m in mats) m.release()
                            fusedMat.release()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HachiCam-Burst", "Background fusion failed: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isCapturing.value = false
            } finally {
                control?.let { ctrl ->
                    clearManualCaptureOptions(ctrl, context)
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun parseExposureTime(expStr: String?): Double? {
        if (expStr.isNullOrBlank()) return null
        return try {
            if (expStr.contains("/")) {
                val parts = expStr.split("/")
                val num = parts[0].trim().toDouble()
                val den = parts[1].trim().toDouble()
                if (den > 0.0) num / den else null
            } else {
                expStr.trim().toDoubleOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private suspend fun setManualCaptureOptions(
        control: CameraControl,
        context: Context,
        exposureTimeNanos: Long,
        iso: Int
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNanos)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .build()
            val future = camera2Control.setCaptureRequestOptions(options)
            val executor = ContextCompat.getMainExecutor(context)
            future.addListener({
                try {
                    future.get()
                    if (continuation.isActive) continuation.resume(true)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, executor)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(false)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private suspend fun clearManualCaptureOptions(
        control: CameraControl,
        context: Context
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            val future = camera2Control.clearCaptureRequestOptions()
            val executor = ContextCompat.getMainExecutor(context)
            future.addListener({
                try {
                    future.get()
                    if (continuation.isActive) continuation.resume(true)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, executor)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Read EXIF orientation from [refFile], rotate [fusedMat] using OpenCV SIMD rotate,
     * write JPEG at [jpegQuality]%, then stamp EXIF provenance from [refFile].
     * This avoids the expensive Java Bitmap decode/re-encode path for rotation.
     */
    private fun rotateAndSaveFusedMat(
        fusedMat: Mat,
        refFile: File,
        outFile: File,
        mode: String,
        jpegQuality: Int = 95
    ) {
        val orientation = try {
            ExifInterface(refFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val uprightMat = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_90_CLOCKWISE); fusedMat.release(); r
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_180); fusedMat.release(); r
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_90_COUNTERCLOCKWISE); fusedMat.release(); r
            }
            else -> fusedMat
        }

        val saveParams = org.opencv.core.MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
        Imgcodecs.imwrite(outFile.absolutePath, uprightMat, saveParams)
        saveParams.release()
        uprightMat.release()
        ExifUtils.copyAndStampExif(refFile, outFile, mode = mode)
    }

    private suspend fun takeSinglePictureInMemory(
        capture: ImageCapture,
        context: Context
    ): ByteArray? = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(context)
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (continuation.isActive) continuation.resume(null)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        )
    }

    private suspend fun takeSinglePicture(
        capture: ImageCapture,
        context: Context,
        targetFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(targetFile).build()
        val executor = ContextCompat.getMainExecutor(context)
        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        )
    }

    /**
     * Normalise EXIF orientation in-place using Java Bitmap (used for single-frame Normal captures).
     * For fused multi-frame output, use [rotateAndSaveFusedMat] instead to avoid full JPEG re-decode.
     */
    private fun normalizeExifOrientation(file: File): Pair<Float, Float> {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    FileOutputStream(file).use { out ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    val existingMode = ExifUtils.extractMode(exif)
                    val newExif = ExifInterface(file.absolutePath)
                    newExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    ExifUtils.stampSignature(newExif, existingMode)
                    newExif.saveAttributes()
                    if (rotatedBitmap != bitmap) bitmap.recycle()
                    val w = rotatedBitmap.width.toFloat()
                    val h = rotatedBitmap.height.toFloat()
                    rotatedBitmap.recycle()
                    return Pair(w, h)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        return Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }

    private fun getImageDimensions(file: File): Pair<Float, Float> {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        return Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }

    private fun computeTargetQuad(currentResult: DetectionResult?, photoW: Float, photoH: Float): DocumentQuad {
        return if (currentResult?.found == true && currentResult.quad != null && currentResult.frameWidth > 0 && currentResult.frameHeight > 0) {
            val sx = photoW / currentResult.frameWidth.toFloat()
            val sy = photoH / currentResult.frameHeight.toFloat()
            val q = currentResult.quad
            DocumentQuad(
                topLeft = PointF((q.topLeft.x * sx).coerceIn(0f, photoW), (q.topLeft.y * sy).coerceIn(0f, photoH)),
                topRight = PointF((q.topRight.x * sx).coerceIn(0f, photoW), (q.topRight.y * sy).coerceIn(0f, photoH)),
                bottomRight = PointF((q.bottomRight.x * sx).coerceIn(0f, photoW), (q.bottomRight.y * sy).coerceIn(0f, photoH)),
                bottomLeft = PointF((q.bottomLeft.x * sx).coerceIn(0f, photoW), (q.bottomLeft.y * sy).coerceIn(0f, photoH))
            )
        } else {
            val insetX = photoW * 0.08f
            val insetY = photoH * 0.08f
            DocumentQuad(
                topLeft = PointF(insetX, insetY),
                topRight = PointF(photoW - insetX, insetY),
                bottomRight = PointF(photoW - insetX, photoH - insetY),
                bottomLeft = PointF(insetX, photoH - insetY)
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri, onPageSaved: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val storage = com.scanner.app.data.image.ImageStorage(context)
                val photoFile = File(storage.getStorageDir(), "${java.util.UUID.randomUUID()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    photoFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (photoFile.exists() && photoFile.length() > 0) {
                    val (photoW, photoH) = normalizeExifOrientation(photoFile)
                    val mat = Imgcodecs.imread(photoFile.absolutePath)
                    val detector = com.scanner.app.engine.NativeEdgeDetector()
                    val detected = if (!mat.empty()) detector.detectDocument(mat, false) else null
                    mat.release()

                    val targetQuad = if (detected?.found == true && detected.quad != null) {
                        detected.quad
                    } else {
                        computeTargetQuad(null, photoW, photoH)
                    }

                    val page = ScannedPage(originalImagePath = photoFile.absolutePath, quad = targetQuad)
                    PageRepository.addPage(page)
                    withContext(Dispatchers.Main) { onPageSaved(page.id) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
