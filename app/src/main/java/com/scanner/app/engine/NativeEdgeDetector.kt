package com.scanner.app.engine

import android.graphics.Bitmap
import android.graphics.PointF
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.domain.model.DocumentQuad
import org.opencv.core.Mat

class NativeEdgeDetector {
    
    init {
        System.loadLibrary("doc_scanner_engine")
    }

    fun detectDocument(grayMat: Mat, curvedMode: Boolean, touchX: Float = -1.0f, touchY: Float = -1.0f): DetectionResult {
        val resultArr = nativeDetectDocument(grayMat.nativeObjAddr, curvedMode, touchX, touchY)
        if (resultArr.isEmpty() || resultArr[0] == 0.0f) {
            return DetectionResult(
                found = false,
                isCurved = curvedMode,
                frameWidth = grayMat.cols(),
                frameHeight = grayMat.rows()
            )
        }
        
        val isCurved = resultArr[1] == 1.0f
        val numPoints = resultArr[2].toInt()

        if (!isCurved && numPoints >= 4 && resultArr.size >= 11) {
            val quad = DocumentQuad(
                topLeft = PointF(resultArr[3], resultArr[4]),
                topRight = PointF(resultArr[5], resultArr[6]),
                bottomRight = PointF(resultArr[7], resultArr[8]),
                bottomLeft = PointF(resultArr[9], resultArr[10])
            )
            return DetectionResult(
                found = true,
                quad = quad,
                isCurved = false,
                frameWidth = grayMat.cols(),
                frameHeight = grayMat.rows()
            )
        }
        
        if (isCurved && numPoints > 0 && resultArr.size >= 3 + numPoints * 2) {
            val pts = mutableListOf<PointF>()
            var offset = 3
            for (i in 0 until numPoints) {
                pts.add(PointF(resultArr[offset], resultArr[offset + 1]))
                offset += 2
            }
            return DetectionResult(
                found = true,
                boundaryPoints = pts,
                isCurved = true,
                frameWidth = grayMat.cols(),
                frameHeight = grayMat.rows()
            )
        }
        
        return DetectionResult(
            found = false,
            isCurved = curvedMode,
            frameWidth = grayMat.cols(),
            frameHeight = grayMat.rows()
        )
    }

    fun findSnapPoint(edgeMat: Mat, touchX: Float, touchY: Float, radius: Float): PointF? {
        return findSnapPointAddr(edgeMat.nativeObjAddr, touchX, touchY, radius)
    }

    fun findSnapPointAddr(edgeMatAddr: Long, touchX: Float, touchY: Float, radius: Float): PointF? {
        if (edgeMatAddr == 0L) return null
        val result = nativeFindSnapPoint(edgeMatAddr, touchX, touchY, radius)
        return if (result.size >= 3 && result[0] == 1.0f) {
            PointF(result[1], result[2])
        } else {
            null
        }
    }

    fun findLineOffset(edgeMat: Mat, p1: PointF, p2: PointF, maxOffset: Float): Float? {
        return findLineOffsetAddr(edgeMat.nativeObjAddr, p1, p2, maxOffset)
    }

    fun findLineOffsetAddr(edgeMatAddr: Long, p1: PointF, p2: PointF, maxOffset: Float): Float? {
        if (edgeMatAddr == 0L) return null
        val offset = nativeFindLineOffset(edgeMatAddr, p1.x, p1.y, p2.x, p2.y, maxOffset)
        return if (!offset.isNaN()) offset else null
    }

    fun findContourAtPoint(grayMat: Mat, touchX: Float, touchY: Float): DocumentQuad? {
        return findContourAtPointAddr(grayMat.nativeObjAddr, touchX, touchY)
    }

    fun findContourAtPointAddr(grayMatAddr: Long, touchX: Float, touchY: Float): DocumentQuad? {
        if (grayMatAddr == 0L) return null
        val result = nativeFindContourAtPoint(grayMatAddr, touchX, touchY)
        return if (result.size >= 9 && result[0] == 1.0f) {
            DocumentQuad(
                topLeft = PointF(result[1], result[2]),
                topRight = PointF(result[3], result[4]),
                bottomRight = PointF(result[5], result[6]),
                bottomLeft = PointF(result[7], result[8])
            )
        } else {
            null
        }
    }

    fun detectStructuralLines(bitmap: Bitmap, origWidth: Float = 0f, origHeight: Float = 0f): Pair<FloatArray, FloatArray> {
        val arrays = nativeDetectStructuralLines(bitmap, origWidth, origHeight)
        val hLines = if (arrays.isNotEmpty()) arrays[0] else FloatArray(0)
        val vLines = if (arrays.size > 1) arrays[1] else FloatArray(0)
        return Pair(hLines, vLines)
    }

    private external fun nativeDetectDocument(matAddr: Long, curvedMode: Boolean, touchX: Float, touchY: Float): FloatArray
    private external fun nativeFindSnapPoint(edgeMatAddr: Long, touchX: Float, touchY: Float, radius: Float): FloatArray
    private external fun nativeFindLineOffset(
        edgeMatAddr: Long,
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float,
        maxOffset: Float
    ): Float
    private external fun nativeFindContourAtPoint(grayMatAddr: Long, touchX: Float, touchY: Float): FloatArray
    private external fun nativeDetectStructuralLines(bitmap: Bitmap, origWidth: Float, origHeight: Float): Array<FloatArray>
}


