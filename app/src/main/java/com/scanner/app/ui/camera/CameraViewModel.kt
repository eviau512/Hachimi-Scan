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
    // Full HDR Unified Capture Pipeline  (SPEC_16 §2)
    //
    // Frame sequence:
    //   Frame 0: EV  0   (base, spatial reference + super-res anchor)
    //   Frame 1: EV -2   (highlight recovery)
    //   Frame 2: EV  0   (sub-pixel super-res aux 1)
    //   Frame 3: EV  0   (sub-pixel super-res aux 2)
    //
    // fuseBurstFrames flags: removeGlare=true, isScreenMode=true, superResolution=true
    // Output: ~50MP JPEG @ quality 95, EXIF mode = "Full HDR"
    // ─────────────────────────────────────────────

    private fun captureFullHdrPhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        viewModelScope.launch {
            _isCapturing.value = true
            val control = cameraControl
            val info = cameraInfo
            try {
                // Wait for stable frame before burst (up to 1.5s)
                if (!_isStable.value) {
                    withTimeoutOrNull(1500L) {
                        _isStable.first { it }
                    }
                }

                // Resolve highlight-recovery EV index (target ≈ -2 EV, clamped to sensor range)
                val exposureState = info?.exposureState
                val minIndex = exposureState?.exposureCompensationRange?.lower ?: 0
                val step = exposureState?.exposureCompensationStep?.let {
                    if (it.denominator != 0) it.numerator.toFloat() / it.denominator.toFloat() else 1.0f
                } ?: 1.0f
                // Deep underexposure for extreme highlight recovery (SPEC_17 §2: -4.5 EV or physical minIndex)
                val targetHighlightIndex = if (step > 0f) {
                    kotlin.math.round(-4.5f / step).toInt().coerceIn(minIndex, 0)
                } else {
                    minIndex.coerceAtMost(0)
                }

                val tempFiles = mutableListOf<File>()

                // Frame 0: EV 0 — base frame
                val file0 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_0.jpg")
                if (takeSinglePicture(capture, context, file0) && file0.exists() && file0.length() > 0) {
                    tempFiles.add(file0)
                }

                // Frame 1: EV targetHighlightIndex — highlight recovery
                if (targetHighlightIndex < 0 && control != null) {
                    setExposureIndex(control, context, targetHighlightIndex)
                    delay(50)
                }
                val file1 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_1.jpg")
                if (takeSinglePicture(capture, context, file1) && file1.exists() && file1.length() > 0) {
                    tempFiles.add(file1)
                }

                // Frame 2: EV 0 — sub-pixel aux 1 (restore AE before firing)
                if (targetHighlightIndex < 0 && control != null) {
                    setExposureIndex(control, context, 0)
                    delay(50)
                }
                val file2 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_2.jpg")
                if (takeSinglePicture(capture, context, file2) && file2.exists() && file2.length() > 0) {
                    tempFiles.add(file2)
                }

                // Frame 3: EV 0 — sub-pixel aux 2
                val file3 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_3.jpg")
                if (takeSinglePicture(capture, context, file3) && file3.exists() && file3.length() > 0) {
                    tempFiles.add(file3)
                }

                if (tempFiles.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }

                val finalPhotoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")

                withContext(Dispatchers.IO) {
                    val mats = mutableListOf<Mat>()
                    for (file in tempFiles) {
                        val mat = Imgcodecs.imread(file.absolutePath)
                        if (!mat.empty()) mats.add(mat)
                    }

                    if (mats.isNotEmpty()) {
                        val fusionEngine = NativeBurstFusion()
                        val fusedMat = fusionEngine.fuseBurstFrames(
                            burstFrames = mats,
                            removeGlare = true,
                            isScreenMode = true,   // HDR highlight graft + shadow S-curve
                            superResolution = true  // 2× canvas → ~50MP
                        )
                        if (!fusedMat.empty()) {
                            rotateAndSaveFusedMat(fusedMat, tempFiles[0], finalPhotoFile, mode = "Full HDR", jpegQuality = 95)
                        } else {
                            tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            normalizeExifOrientation(finalPhotoFile)
                            ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                        }
                        for (m in mats) m.release()
                    } else {
                        tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                        normalizeExifOrientation(finalPhotoFile)
                        ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                    }

                    for (f in tempFiles) f.delete()
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
                control?.setExposureCompensationIndex(0)
            }
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

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

    private suspend fun setExposureIndex(
        control: CameraControl,
        context: Context,
        index: Int
    ): Int = suspendCancellableCoroutine { continuation ->
        try {
            val future = control.setExposureCompensationIndex(index)
            val executor = ContextCompat.getMainExecutor(context)
            future.addListener({
                try {
                    val result = future.get()
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(index)
                }
            }, executor)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(index)
        }
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
