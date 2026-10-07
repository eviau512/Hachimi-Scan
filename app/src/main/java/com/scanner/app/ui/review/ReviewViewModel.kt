package com.scanner.app.ui.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.data.util.ExifUtils
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.domain.model.ScannedPage
import com.scanner.app.engine.NativePerspective
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream

class ReviewViewModel : ViewModel() {
    val pages: StateFlow<List<ScannedPage>> = PageRepository.pages

    private val _selectedPageId = MutableStateFlow<String?>(null)
    val selectedPageId: StateFlow<String?> = _selectedPageId.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _imageVersion = MutableStateFlow(0)
    val imageVersion: StateFlow<Int> = _imageVersion.asStateFlow()

    fun selectPage(pageId: String?) {
        _selectedPageId.value = pageId
    }

    fun deletePage(pageId: String) {
        if (_selectedPageId.value == pageId) {
            _selectedPageId.value = null
        }
        PageRepository.deletePage(pageId)
    }

    fun rotatePage(pageId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.Default) {
            val page = PageRepository.getPage(pageId) ?: run {
                withContext(Dispatchers.Main) { onComplete() }
                return@launch
            }
            val path = page.processedImagePath ?: page.imagePath
            val file = File(path)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    val matrix = Matrix().apply { postRotate(90f) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    FileOutputStream(file).use { out ->
                        rotated.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    if (rotated != bmp) rotated.recycle()
                    bmp.recycle()
                    val origFile = File(page.originalImagePath)
                    ExifUtils.copyAndStampExif(origFile, file)
                }
            }
            val newRotation = (page.rotation + 90) % 360
            PageRepository.updatePage(page.copy(rotation = newRotation))
            withContext(Dispatchers.Main) {
                _imageVersion.value++
                onComplete()
            }
        }
    }

    private fun applyFilterToPage(page: ScannedPage, newFilter: ImageFilter) {
        val origPath = page.originalImagePath
        val srcMat = Imgcodecs.imread(origPath)
        if (!srcMat.empty()) {
            val quad = page.quad ?: DocumentQuad(
                PointF(0f, 0f),
                PointF(srcMat.cols().toFloat(), 0f),
                PointF(srcMat.cols().toFloat(), srcMat.rows().toFloat()),
                PointF(0f, srcMat.rows().toFloat())
            )
            val corrector = NativePerspective()
            val targetRatio = page.targetAspectRatio ?: 0f
            var warpedMat = corrector.processDocument(srcMat, quad, newFilter, targetRatio)

            val rot = (page.rotation % 360 + 360) % 360
            if (rot != 0) {
                val rotatedMat = Mat()
                when (rot) {
                    90 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_180)
                    270 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> warpedMat.copyTo(rotatedMat)
                }
                warpedMat.release()
                warpedMat = rotatedMat
            }

            val origFile = File(origPath)
            val croppedFile = File(origFile.parentFile, "crop_${page.id}.jpg")
            val saveParams = MatOfInt(
                Imgcodecs.IMWRITE_JPEG_QUALITY, 100,
                Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1
            )
            Imgcodecs.imwrite(croppedFile.absolutePath, warpedMat, saveParams)
            saveParams.release()
            ExifUtils.copyAndStampExif(origFile, croppedFile)

            srcMat.release()
            warpedMat.release()

            val updated = page.copy(
                processedImagePath = croppedFile.absolutePath,
                filter = newFilter
            )
            PageRepository.updatePage(updated)
        }
    }

    fun setPageFilter(pageId: String, newFilter: ImageFilter) {
        val page = PageRepository.getPage(pageId) ?: return
        if (page.filter == newFilter && page.processedImagePath != null) return

        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                applyFilterToPage(page, newFilter)
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun setBatchFilter(pageIds: Collection<String>, newFilter: ImageFilter, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                for (pageId in pageIds) {
                    val page = PageRepository.getPage(pageId) ?: continue
                    if (page.filter == newFilter && page.processedImagePath != null) continue
                    applyFilterToPage(page, newFilter)
                }
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun deleteBatchPages(pageIds: Collection<String>) {
        for (pageId in pageIds) {
            if (_selectedPageId.value == pageId) {
                _selectedPageId.value = null
            }
            PageRepository.deletePage(pageId)
        }
    }

    private fun applyAspectRatioToPage(page: ScannedPage, targetRatio: Float?) {
        val origPath = page.originalImagePath
        val srcMat = Imgcodecs.imread(origPath)
        if (!srcMat.empty()) {
            val quad = page.quad ?: DocumentQuad(
                PointF(0f, 0f),
                PointF(srcMat.cols().toFloat(), 0f),
                PointF(srcMat.cols().toFloat(), srcMat.rows().toFloat()),
                PointF(0f, srcMat.rows().toFloat())
            )
            val corrector = NativePerspective()
            val ratio = targetRatio ?: 0f
            var warpedMat = corrector.processDocument(srcMat, quad, page.filter, ratio)

            val rot = (page.rotation % 360 + 360) % 360
            if (rot != 0) {
                val rotatedMat = Mat()
                when (rot) {
                    90 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_180)
                    270 -> Core.rotate(warpedMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> warpedMat.copyTo(rotatedMat)
                }
                warpedMat.release()
                warpedMat = rotatedMat
            }

            val origFile = File(origPath)
            val croppedFile = File(origFile.parentFile, "crop_${page.id}.jpg")
            val saveParams = MatOfInt(
                Imgcodecs.IMWRITE_JPEG_QUALITY, 100,
                Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1
            )
            Imgcodecs.imwrite(croppedFile.absolutePath, warpedMat, saveParams)
            saveParams.release()
            ExifUtils.copyAndStampExif(origFile, croppedFile)

            srcMat.release()
            warpedMat.release()

            val updated = page.copy(
                processedImagePath = croppedFile.absolutePath,
                targetAspectRatio = targetRatio
            )
            PageRepository.updatePage(updated)
        }
    }

    fun setPageAspectRatio(pageId: String, preset: AspectRatioPreset) {
        val page = PageRepository.getPage(pageId) ?: return
        val newTargetRatio = preset.ratio
        if (page.targetAspectRatio == newTargetRatio && page.processedImagePath != null) return

        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                applyAspectRatioToPage(page, newTargetRatio)
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun setPageCustomRatio(pageId: String, customRatio: Float) {
        val page = PageRepository.getPage(pageId) ?: return
        if (page.targetAspectRatio == customRatio && page.processedImagePath != null) return

        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                applyAspectRatioToPage(page, customRatio)
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun setBatchAspectRatio(pageIds: Collection<String>, preset: AspectRatioPreset, onComplete: () -> Unit = {}) {
        val newTargetRatio = preset.ratio
        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                for (pageId in pageIds) {
                    val page = PageRepository.getPage(pageId) ?: continue
                    if (page.targetAspectRatio == newTargetRatio && page.processedImagePath != null) continue
                    applyAspectRatioToPage(page, newTargetRatio)
                }
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun setBatchCustomRatio(pageIds: Collection<String>, customRatio: Float, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            try {
                for (pageId in pageIds) {
                    val page = PageRepository.getPage(pageId) ?: continue
                    if (page.targetAspectRatio == customRatio && page.processedImagePath != null) continue
                    applyAspectRatioToPage(page, customRatio)
                }
                withContext(Dispatchers.Main) {
                    _imageVersion.value++
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        PageRepository.reorderPages(fromIndex, toIndex)
    }
}
