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
        val photoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
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
    // Apple Deep Fusion / Smart HDR 9-Frame Capture Pipeline
    //
    // Frame sequence (9 frames total):
    //   Tier 1 (4 frames, EV 0):
    //     Frames 0, 1, 2, 3: AE locked, base exposure & sub-pixel super-res anchors
    //   Tier 2 (3 frames, EV -2.5):
    //     Frames 4, 5, 6: Manual midtone exposure transition
    //   Tier 3 (2 frames, EV -5.0):
    //     Frames 7, 8: Manual deep highlight recovery (lamp bulb/filament un-saturation)
    //
    // All frames are fused by OpenCV MergeMertens multi-scale Laplacian pyramid
    // + 50MP sub-pixel high-frequency detail transfer.
    // Output: ~50MP JPEG @ quality 95, EXIF mode = "Full HDR"
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

                val tier1Files = mutableListOf<File>()
                val tier2Files = mutableListOf<File>()
                val tier3Files = mutableListOf<File>()

                // ─────────────────────────────────────────────────────────────
                // Tier 1: 4 帧正常曝光 (EV 0) — 50MP 亚像素超分与暗部降噪核心锚点
                // ─────────────────────────────────────────────────────────────
                coroutineScope {
                    val t1Jobs = (0 until 4).map { i ->
                        val f = File(context.cacheDir, "hdr_${UUID.randomUUID()}_t1_$i.jpg")
                        async {
                            if (takeSinglePicture(capture, context, f) && f.exists() && f.length() > 0) f else null
                        }
                    }
                    tier1Files.addAll(t1Jobs.awaitAll().filterNotNull())
                }

                if (tier1Files.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }

                // Inspect Frame 0 exposure parameters from EXIF
                val file0 = tier1Files[0]
                val baseIso: Int
                val baseExpSec: Double
                if (file0.exists()) {
                    val ex0 = ExifInterface(file0.absolutePath)
                    baseIso = ex0.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 800).coerceAtLeast(100)
                    baseExpSec = parseExposureTime(ex0.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)) ?: 0.033
                } else {
                    baseIso = 800
                    baseExpSec = 0.033
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
                // Tier 2: 2 帧中灰曝光 (-2.5 EV) — 快速获取过渡中灰
                // ─────────────────────────────────────────────────────────────
                if (control != null) {
                    setManualCaptureOptions(control, context, midExpNanos, midIso)
                    delay(40) // Sensor register latch
                }
                coroutineScope {
                    val t2Jobs = (0 until 2).map { i ->
                        val f = File(context.cacheDir, "hdr_${UUID.randomUUID()}_t2_$i.jpg")
                        async {
                            if (takeSinglePicture(capture, context, f) && f.exists() && f.length() > 0) f else null
                        }
                    }
                    tier2Files.addAll(t2Jobs.awaitAll().filterNotNull())
                }

                // ─────────────────────────────────────────────────────────────
                // Tier 3: 1 帧短曝光 (-6.0 EV) — 极速提取灯丝/灯芯与高光轮廓
                // ─────────────────────────────────────────────────────────────
                if (control != null) {
                    setManualCaptureOptions(control, context, shortExpNanos, shortIso)
                    delay(40) // Sensor register latch
                }
                coroutineScope {
                    val t3Jobs = (0 until 1).map { i ->
                        val f = File(context.cacheDir, "hdr_${UUID.randomUUID()}_t3_$i.jpg")
                        async {
                            if (takeSinglePicture(capture, context, f) && f.exists() && f.length() > 0) f else null
                        }
                    }
                    tier3Files.addAll(t3Jobs.awaitAll().filterNotNull())
                }

                // Restore Auto-Exposure immediately after burst capture
                if (control != null) {
                    clearManualCaptureOptions(control, context)
                }

                val allFiles = mutableListOf<File>().apply {
                    addAll(tier1Files)
                    addAll(tier2Files)
                    addAll(tier3Files)
                }

                allFiles.forEachIndexed { idx, f ->
                    try {
                        val ex = ExifInterface(f.absolutePath)
                        val iso = ex.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        val exp = ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                        android.util.Log.i("HachiCam-Burst", "Burst Frame $idx (total ${allFiles.size}): ISO=$iso, ExpTime=$exp, size=${f.length()} bytes")
                    } catch (e: Exception) {
                        android.util.Log.w("HachiCam-Burst", "Failed to inspect Frame $idx EXIF: ${e.message}")
                    }
                }

                val finalPhotoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")

                withContext(Dispatchers.IO) {
                    val deferredMats = allFiles.map { file ->
                        async(Dispatchers.IO) {
                            Imgcodecs.imread(file.absolutePath)
                        }
                    }
                    val mats = deferredMats.awaitAll().filter { !it.empty() }

                    if (mats.isNotEmpty()) {
                        val fusionEngine = NativeBurstFusion()
                        val fusedMat = fusionEngine.fuseBurstFrames(
                            burstFrames = mats,
                            removeGlare = true,
                            isScreenMode = true,
                            superResolution = true
                        )
                        if (!fusedMat.empty()) {
                            rotateAndSaveFusedMat(fusedMat, allFiles[0], finalPhotoFile, mode = "Full HDR", jpegQuality = 95)
                        } else {
                            allFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            normalizeExifOrientation(finalPhotoFile)
                            ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                        }
                        for (m in mats) m.release()
                    } else {
                        allFiles[0].copyTo(finalPhotoFile, overwrite = true)
                        normalizeExifOrientation(finalPhotoFile)
                        ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                    }

                    for (f in allFiles) f.delete()
                }

                val (photoW, photoH) = getImageDimensions(finalPhotoFile)
                val currentResult = _detectedQuad.value
                val finalQuad = computeTargetQuad(currentResult, photoW, photoH)

                val newPage = ScannedPage(
                    id = UUID.randomUUID().toString(),
                    originalImagePath = finalPhotoFile.absolutePath,
                    quad = finalQuad,
                    filter = ImageFilter.MAGIC_COLOR
                )
                PageRepository.addPage(newPage)

                withContext(Dispatchers.Main) {
                    _isCapturing.value = false
                    frameAnalyzer?.resetStability()
                    onPageSaved(newPage.id)
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
                val photoFile = File(context.cacheDir, "${java.util.UUID.randomUUID()}.jpg")
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
