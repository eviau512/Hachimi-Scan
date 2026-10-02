package com.scanner.app.ui.crop

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.engine.NativeEdgeDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private data class SnapGuide(
    val start: PointF,
    val end: PointF
)

/**
 * Standard 2D algebraic line representation: A*x + B*y + C = 0
 * normalized such that A^2 + B^2 = 1.
 */
private data class Line2D(
    val a: Float,
    val b: Float,
    val c: Float
) {
    fun distanceTo(pt: PointF): Float {
        return abs(a * pt.x + b * pt.y + c)
    }

    fun project(pt: PointF): PointF {
        val d = a * pt.x + b * pt.y + c
        return PointF(pt.x - a * d, pt.y - b * d)
    }

    companion object {
        fun fromPoints(p1: PointF, p2: PointF): Line2D {
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < 1e-6f) {
                return Line2D(0f, 1f, -p1.y)
            }
            val a = dy / len
            val b = -dx / len
            val c = -(a * p1.x + b * p1.y)
            return Line2D(a, b, c)
        }

        fun intersect(l1: Line2D, l2: Line2D): PointF? {
            val d = l1.a * l2.b - l2.a * l1.b
            if (abs(d) < 1e-6f) return null
            val x = (l1.b * l2.c - l2.b * l1.c) / d
            val y = (l2.a * l1.c - l1.a * l2.c) / d
            return PointF(x, y)
        }
    }
}

private fun pointToSegmentDistance(
    px: Float, py: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    allowExtension: Boolean = true
): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1e-6f) return hypot(px - x1, py - y1)
    val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
    if (allowExtension) {
        if (t < -0.25f || t > 1.25f) return Float.MAX_VALUE
    } else {
        if (t < 0f || t > 1f) return Float.MAX_VALUE
    }
    val tClamped = t.coerceIn(0f, 1f)
    val projX = x1 + tClamped * dx
    val projY = y1 + tClamped * dy
    return hypot(px - projX, py - projY)
}

private fun isConvexQuad(quad: DocumentQuad, bmpW: Float, bmpH: Float): Boolean {
    val p0 = quad.topLeft
    val p1 = quad.topRight
    val p2 = quad.bottomRight
    val p3 = quad.bottomLeft

    fun cross(a: PointF, b: PointF, c: PointF): Float {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    val cp0 = cross(p0, p1, p2)
    val cp1 = cross(p1, p2, p3)
    val cp2 = cross(p2, p3, p0)
    val cp3 = cross(p3, p0, p1)

    val allPos = cp0 > 0f && cp1 > 0f && cp2 > 0f && cp3 > 0f
    val allNeg = cp0 < 0f && cp1 < 0f && cp2 < 0f && cp3 < 0f
    if (!allPos && !allNeg) return false

    val midTop = PointF((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
    val midBottom = PointF((p3.x + p2.x) / 2f, (p3.y + p2.y) / 2f)
    val midLeft = PointF((p0.x + p3.x) / 2f, (p0.y + p3.y) / 2f)
    val midRight = PointF((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)

    if (hypot(midTop.x - midBottom.x, midTop.y - midBottom.y) < bmpH * 0.02f) return false
    if (hypot(midLeft.x - midRight.x, midLeft.y - midRight.y) < bmpW * 0.02f) return false

    return true
}

@Composable
fun QuadCropView(
    bitmap: Bitmap?,
    quad: DocumentQuad,
    detectedQuad: DocumentQuad? = null,
    edgeMatAddr: Long = 0L,
    horizontalLines: FloatArray = FloatArray(0),
    verticalLines: FloatArray = FloatArray(0),
    originalWidth: Float = 0f,
    originalHeight: Float = 0f,
    onCornerUpdated: (cornerIndex: Int, newImagePos: PointF) -> Unit = { _, _ -> },
    onEdgeUpdated: (edgeIndex: Int, deltaImageX: Float, deltaImageY: Float) -> Unit = { _, _, _ -> },
    onQuadChanged: ((DocumentQuad) -> Unit)? = null,
    onTapToSnap: ((touchImgX: Float, touchImgY: Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val handleTouchRadiusPx = with(density) { 34.dp.toPx() } // R_touch = 34dp per SPEC_06 §3.2
    val strokeWidthPx = with(density) { 2.5.dp.toPx() }
    val cornerHandleRadiusPx = with(density) { 15.dp.toPx() }
    val midpointHandleRadiusPx = with(density) { 13.dp.toPx() }
    val magRadiusPx = with(density) { 54.dp.toPx() }

    // Unified 8-handle interaction state (SPEC_06 §3.1)
    // 0: TOP_LEFT, 1: LEFT_CENTER, 2: BOTTOM_LEFT, 3: BOTTOM_CENTER,
    // 4: BOTTOM_RIGHT, 5: RIGHT_CENTER, 6: TOP_RIGHT, 7: TOP_CENTER
    var draggingHandleIndex by remember { mutableStateOf<Int?>(null) }
    var touchPosition by remember { mutableStateOf(Offset.Zero) }
    var wasSnapped by remember { mutableStateOf(false) }
    var isEdgeSnapped by remember { mutableStateOf(false) }
    var activeSnapGuides by remember { mutableStateOf<List<SnapGuide>>(emptyList()) }

    var dragBaseQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var dragTotalOffset by remember { mutableStateOf(Offset.Zero) }

    val currentQuad by rememberUpdatedState(quad)
    val currentHorizontalLines by rememberUpdatedState(horizontalLines)
    val currentVerticalLines by rememberUpdatedState(verticalLines)
    val currentOnCornerUpdated by rememberUpdatedState(onCornerUpdated)
    val currentOnEdgeUpdated by rememberUpdatedState(onEdgeUpdated)
    val currentOnQuadChanged by rememberUpdatedState(onQuadChanged)
    val currentOnTapToSnap by rememberUpdatedState(onTapToSnap)

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    val loupeClipPath = remember { android.graphics.Path() }
    val loupeMatrix = remember { android.graphics.Matrix() }
    val loupeBgPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.DKGRAY
        }
    }
    val loupeBmpPaint = remember {
        android.graphics.Paint().apply {
            isFilterBitmap = true
            isDither = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTapGestures { offset ->
                    if (bitmap == null) return@detectTapGestures

                    val viewW = size.width.toFloat()
                    val viewH = size.height.toFloat()
                    val fullW = if (originalWidth > 0f) originalWidth else bitmap.width.toFloat()
                    val fullH = if (originalHeight > 0f) originalHeight else bitmap.height.toFloat()
                    val scale = min(viewW / fullW, viewH / fullH)
                    val offsetX = (viewW - fullW * scale) / 2f
                    val offsetY = (viewH - fullH * scale) / 2f

                    fun imgToScreen(pt: PointF) = Offset(pt.x * scale + offsetX, pt.y * scale + offsetY)

                    val activeQuad = currentQuad
                    val sTL = imgToScreen(activeQuad.topLeft)
                    val sTR = imgToScreen(activeQuad.topRight)
                    val sBR = imgToScreen(activeQuad.bottomRight)
                    val sBL = imgToScreen(activeQuad.bottomLeft)

                    val handles = listOf(
                        sTL,                                                   // 0: TOP_LEFT
                        Offset((sTL.x + sBL.x) / 2f, (sTL.y + sBL.y) / 2f),   // 1: LEFT_CENTER
                        sBL,                                                   // 2: BOTTOM_LEFT
                        Offset((sBL.x + sBR.x) / 2f, (sBL.y + sBR.y) / 2f),   // 3: BOTTOM_CENTER
                        sBR,                                                   // 4: BOTTOM_RIGHT
                        Offset((sBR.x + sTR.x) / 2f, (sBR.y + sTR.y) / 2f),   // 5: RIGHT_CENTER
                        sTR,                                                   // 6: TOP_RIGHT
                        Offset((sTR.x + sTL.x) / 2f, (sTR.y + sTL.y) / 2f)    // 7: TOP_CENTER
                    )

                    // 1. Check if tap hits any of the 8 handles (equal 34dp radius)
                    val hitHandle = handles.any { pt ->
                        hypot(offset.x - pt.x, offset.y - pt.y) < handleTouchRadiusPx
                    }
                    if (hitHandle) return@detectTapGestures

                    // 2. Tap outside handles - Convert to image coordinates for Tap-to-Snap
                    val imgX = (offset.x - offsetX) / scale
                    val imgY = (offset.y - offsetY) / scale

                    if (imgX in 0f..fullW && imgY in 0f..fullH) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnTapToSnap?.invoke(imgX, imgY)
                    }
                }
            }
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (bitmap == null) return@detectDragGestures

                        val viewW = size.width.toFloat()
                        val viewH = size.height.toFloat()
                        val fullW = if (originalWidth > 0f) originalWidth else bitmap.width.toFloat()
                        val fullH = if (originalHeight > 0f) originalHeight else bitmap.height.toFloat()
                        val scale = min(viewW / fullW, viewH / fullH)
                        val offsetX = (viewW - fullW * scale) / 2f
                        val offsetY = (viewH - fullH * scale) / 2f

                        fun imgToScreen(pt: PointF) = Offset(pt.x * scale + offsetX, pt.y * scale + offsetY)

                        val activeQuad = currentQuad
                        val sTL = imgToScreen(activeQuad.topLeft)
                        val sTR = imgToScreen(activeQuad.topRight)
                        val sBR = imgToScreen(activeQuad.bottomRight)
                        val sBL = imgToScreen(activeQuad.bottomLeft)

                        // 8-Point Equal Proximity Hit Testing (SPEC_06 §3.2)
                        val handles = listOf(
                            sTL,                                                   // 0: TOP_LEFT
                            Offset((sTL.x + sBL.x) / 2f, (sTL.y + sBL.y) / 2f),   // 1: LEFT_CENTER
                            sBL,                                                   // 2: BOTTOM_LEFT
                            Offset((sBL.x + sBR.x) / 2f, (sBL.y + sBR.y) / 2f),   // 3: BOTTOM_CENTER
                            sBR,                                                   // 4: BOTTOM_RIGHT
                            Offset((sBR.x + sTR.x) / 2f, (sBR.y + sTR.y) / 2f),   // 5: RIGHT_CENTER
                            sTR,                                                   // 6: TOP_RIGHT
                            Offset((sTR.x + sTL.x) / 2f, (sTR.y + sTL.y) / 2f)    // 7: TOP_CENTER
                        )

                        var nearestHandle = -1
                        var minHandleDist = Float.MAX_VALUE
                        handles.forEachIndexed { i, pt ->
                            val dist = hypot(offset.x - pt.x, offset.y - pt.y)
                            if (dist < minHandleDist) {
                                minHandleDist = dist
                                nearestHandle = i
                            }
                        }

                        if (nearestHandle != -1 && minHandleDist <= handleTouchRadiusPx) {
                            draggingHandleIndex = nearestHandle
                            touchPosition = offset
                            dragBaseQuad = activeQuad
                            dragTotalOffset = Offset.Zero
                            wasSnapped = false
                            isEdgeSnapped = false
                            activeSnapGuides = emptyList()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            draggingHandleIndex = null
                        }
                    },
                    onDragEnd = {
                        draggingHandleIndex = null
                        dragBaseQuad = null
                        dragTotalOffset = Offset.Zero
                        wasSnapped = false
                        isEdgeSnapped = false
                        activeSnapGuides = emptyList()
                    },
                    onDragCancel = {
                        draggingHandleIndex = null
                        dragBaseQuad = null
                        dragTotalOffset = Offset.Zero
                        wasSnapped = false
                        isEdgeSnapped = false
                        activeSnapGuides = emptyList()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (bitmap == null) return@detectDragGestures

                        val viewW = size.width.toFloat()
                        val viewH = size.height.toFloat()
                        val fullW = if (originalWidth > 0f) originalWidth else bitmap.width.toFloat()
                        val fullH = if (originalHeight > 0f) originalHeight else bitmap.height.toFloat()
                        val scale = min(viewW / fullW, viewH / fullH)
                        val offsetX = (viewW - fullW * scale) / 2f
                        val offsetY = (viewH - fullH * scale) / 2f

                        touchPosition += dragAmount
                        dragTotalOffset += dragAmount

                        val activeQuad = currentQuad
                        val handleIdx = draggingHandleIndex ?: return@detectDragGestures
                        val baseQuad = dragBaseQuad ?: activeQuad

                        val totalDeltaX = dragTotalOffset.x / scale
                        val totalDeltaY = dragTotalOffset.y / scale

                        // Dynamic Screen-Space Snapping Threshold (28dp converted to image pixels)
                        val snapThresholdPx = with(density) { 28.dp.toPx() }
                        val tauSnap = snapThresholdPx / scale

                        if (handleIdx % 2 != 0) {
                            // --- MIDPOINT DRAGGING (SPEC_06 §4 & §5) ---
                            // 1: Left-Center, 3: Bottom-Center, 5: Right-Center, 7: Top-Center
                            val pool = if (handleIdx == 1 || handleIdx == 5) currentVerticalLines else currentHorizontalLines

                            val baseMid = when (handleIdx) {
                                1 -> PointF((baseQuad.topLeft.x + baseQuad.bottomLeft.x) / 2f, (baseQuad.topLeft.y + baseQuad.bottomLeft.y) / 2f)
                                3 -> PointF((baseQuad.bottomLeft.x + baseQuad.bottomRight.x) / 2f, (baseQuad.bottomLeft.y + baseQuad.bottomRight.y) / 2f)
                                5 -> PointF((baseQuad.bottomRight.x + baseQuad.topRight.x) / 2f, (baseQuad.bottomRight.y + baseQuad.topRight.y) / 2f)
                                else -> PointF((baseQuad.topRight.x + baseQuad.topLeft.x) / 2f, (baseQuad.topRight.y + baseQuad.topLeft.y) / 2f)
                            }
                            val curMid = PointF(
                                (baseMid.x + totalDeltaX).coerceIn(0f, fullW),
                                (baseMid.y + totalDeltaY).coerceIn(0f, fullH)
                            )

                            // Point-to-segment perpendicular distance snapping
                            var bestDist = tauSnap
                            var bestLineA: PointF? = null
                            var bestLineB: PointF? = null

                            var i = 0
                            while (i + 3 < pool.size) {
                                val ax = pool[i]
                                val ay = pool[i + 1]
                                val bx = pool[i + 2]
                                val by = pool[i + 3]
                                i += 4

                                val d = pointToSegmentDistance(curMid.x, curMid.y, ax, ay, bx, by, allowExtension = true)
                                if (d < bestDist) {
                                    bestDist = d
                                    bestLineA = PointF(ax, ay)
                                    bestLineB = PointF(bx, by)
                                }
                            }

                            // Four-Line Interception Geometry (SPEC_06 §5.1)
                            val lTop = Line2D.fromPoints(baseQuad.topLeft, baseQuad.topRight)
                            val lRight = Line2D.fromPoints(baseQuad.topRight, baseQuad.bottomRight)
                            val lBottom = Line2D.fromPoints(baseQuad.bottomRight, baseQuad.bottomLeft)
                            val lLeft = Line2D.fromPoints(baseQuad.bottomLeft, baseQuad.topLeft)

                            val didSnap = (bestLineA != null && bestLineB != null)
                            val targetLine = if (didSnap) {
                                Line2D.fromPoints(bestLineA!!, bestLineB!!)
                            } else {
                                val baseL = when (handleIdx) {
                                    1 -> lLeft
                                    3 -> lBottom
                                    5 -> lRight
                                    else -> lTop
                                }
                                Line2D(baseL.a, baseL.b, -(baseL.a * curMid.x + baseL.b * curMid.y))
                            }

                            val tentativeQuad = when (handleIdx) {
                                1 -> { // Left edge
                                    val newTL = Line2D.intersect(lTop, targetLine) ?: baseQuad.topLeft
                                    val newBL = Line2D.intersect(targetLine, lBottom) ?: baseQuad.bottomLeft
                                    baseQuad.copy(
                                        topLeft = PointF(newTL.x.coerceIn(0f, fullW), newTL.y.coerceIn(0f, fullH)),
                                        bottomLeft = PointF(newBL.x.coerceIn(0f, fullW), newBL.y.coerceIn(0f, fullH))
                                    )
                                }
                                3 -> { // Bottom edge
                                    val newBL = Line2D.intersect(lLeft, targetLine) ?: baseQuad.bottomLeft
                                    val newBR = Line2D.intersect(targetLine, lRight) ?: baseQuad.bottomRight
                                    baseQuad.copy(
                                        bottomLeft = PointF(newBL.x.coerceIn(0f, fullW), newBL.y.coerceIn(0f, fullH)),
                                        bottomRight = PointF(newBR.x.coerceIn(0f, fullW), newBR.y.coerceIn(0f, fullH))
                                    )
                                }
                                5 -> { // Right edge
                                    val newTR = Line2D.intersect(lTop, targetLine) ?: baseQuad.topRight
                                    val newBR = Line2D.intersect(targetLine, lBottom) ?: baseQuad.bottomRight
                                    baseQuad.copy(
                                        topRight = PointF(newTR.x.coerceIn(0f, fullW), newTR.y.coerceIn(0f, fullH)),
                                        bottomRight = PointF(newBR.x.coerceIn(0f, fullW), newBR.y.coerceIn(0f, fullH))
                                    )
                                }
                                else -> { // 7: Top edge
                                    val newTL = Line2D.intersect(targetLine, lLeft) ?: baseQuad.topLeft
                                    val newTR = Line2D.intersect(targetLine, lRight) ?: baseQuad.topRight
                                    baseQuad.copy(
                                        topLeft = PointF(newTL.x.coerceIn(0f, fullW), newTL.y.coerceIn(0f, fullH)),
                                        topRight = PointF(newTR.x.coerceIn(0f, fullW), newTR.y.coerceIn(0f, fullH))
                                    )
                                }
                            }

                            // Convexity Verification (SPEC_06 §5.2)
                            if (isConvexQuad(tentativeQuad, fullW, fullH)) {
                                val onQuadChangedCb = currentOnQuadChanged
                                if (onQuadChangedCb != null) {
                                    onQuadChangedCb(tentativeQuad)
                                } else {
                                    val edgeIdx = when (handleIdx) { 7 -> 0; 5 -> 1; 3 -> 2; else -> 3 }
                                    val deltaX = dragAmount.x / scale
                                    val deltaY = dragAmount.y / scale
                                    currentOnEdgeUpdated(edgeIdx, deltaX, deltaY)
                                }
                            }

                            if (didSnap && !wasSnapped) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            wasSnapped = didSnap
                            isEdgeSnapped = didSnap
                            activeSnapGuides = if (didSnap) listOf(SnapGuide(bestLineA!!, bestLineB!!)) else emptyList()
                        } else {
                            // --- CORNER DRAGGING (SPEC_06 §6.1) ---
                            // 0: TOP_LEFT, 2: BOTTOM_LEFT, 4: BOTTOM_RIGHT, 6: TOP_RIGHT
                            val rawImgX = ((touchPosition.x - offsetX) / scale).coerceIn(0f, fullW)
                            val rawImgY = ((touchPosition.y - offsetY) / scale).coerceIn(0f, fullH)

                            // Search for nearest horizontal line
                            var bestHDist = tauSnap
                            var bestHLineA: PointF? = null
                            var bestHLineB: PointF? = null
                            var hIdx = 0
                            while (hIdx + 3 < currentHorizontalLines.size) {
                                val ax = currentHorizontalLines[hIdx]
                                val ay = currentHorizontalLines[hIdx + 1]
                                val bx = currentHorizontalLines[hIdx + 2]
                                val by = currentHorizontalLines[hIdx + 3]
                                hIdx += 4
                                val d = pointToSegmentDistance(rawImgX, rawImgY, ax, ay, bx, by, allowExtension = true)
                                if (d < bestHDist) {
                                    bestHDist = d
                                    bestHLineA = PointF(ax, ay)
                                    bestHLineB = PointF(bx, by)
                                }
                            }

                            // Search for nearest vertical line
                            var bestVDist = tauSnap
                            var bestVLineA: PointF? = null
                            var bestVLineB: PointF? = null
                            var vIdx = 0
                            while (vIdx + 3 < currentVerticalLines.size) {
                                val ax = currentVerticalLines[vIdx]
                                val ay = currentVerticalLines[vIdx + 1]
                                val bx = currentVerticalLines[vIdx + 2]
                                val by = currentVerticalLines[vIdx + 3]
                                vIdx += 4
                                val d = pointToSegmentDistance(rawImgX, rawImgY, ax, ay, bx, by, allowExtension = true)
                                if (d < bestVDist) {
                                    bestVDist = d
                                    bestVLineA = PointF(ax, ay)
                                    bestVLineB = PointF(bx, by)
                                }
                            }

                            var snappedPt = PointF(rawImgX, rawImgY)
                            var didSnap = false
                            val guides = mutableListOf<SnapGuide>()

                            if (bestHLineA != null && bestVLineA != null) {
                                val lH = Line2D.fromPoints(bestHLineA, bestHLineB!!)
                                val lV = Line2D.fromPoints(bestVLineA, bestVLineB!!)
                                val intersection = Line2D.intersect(lH, lV)
                                if (intersection != null && hypot(intersection.x - rawImgX, intersection.y - rawImgY) < tauSnap * 1.5f) {
                                    snappedPt = PointF(intersection.x.coerceIn(0f, fullW), intersection.y.coerceIn(0f, fullH))
                                    didSnap = true
                                    guides.add(SnapGuide(bestHLineA, bestHLineB))
                                    guides.add(SnapGuide(bestVLineA, bestVLineB))
                                } else {
                                    if (bestHDist <= bestVDist) {
                                        val proj = lH.project(PointF(rawImgX, rawImgY))
                                        snappedPt = PointF(proj.x.coerceIn(0f, fullW), proj.y.coerceIn(0f, fullH))
                                        didSnap = true
                                        guides.add(SnapGuide(bestHLineA, bestHLineB))
                                    } else {
                                        val proj = lV.project(PointF(rawImgX, rawImgY))
                                        snappedPt = PointF(proj.x.coerceIn(0f, fullW), proj.y.coerceIn(0f, fullH))
                                        didSnap = true
                                        guides.add(SnapGuide(bestVLineA, bestVLineB))
                                    }
                                }
                            } else if (bestHLineA != null) {
                                val lH = Line2D.fromPoints(bestHLineA, bestHLineB!!)
                                val proj = lH.project(PointF(rawImgX, rawImgY))
                                snappedPt = PointF(proj.x.coerceIn(0f, fullW), proj.y.coerceIn(0f, fullH))
                                didSnap = true
                                guides.add(SnapGuide(bestHLineA, bestHLineB))
                            } else if (bestVLineA != null) {
                                val lV = Line2D.fromPoints(bestVLineA, bestVLineB!!)
                                val proj = lV.project(PointF(rawImgX, rawImgY))
                                snappedPt = PointF(proj.x.coerceIn(0f, fullW), proj.y.coerceIn(0f, fullH))
                                didSnap = true
                                guides.add(SnapGuide(bestVLineA, bestVLineB))
                            }

                            val tentativeQuad = when (handleIdx) {
                                0 -> activeQuad.copy(topLeft = snappedPt)
                                2 -> activeQuad.copy(bottomLeft = snappedPt)
                                4 -> activeQuad.copy(bottomRight = snappedPt)
                                else -> activeQuad.copy(topRight = snappedPt) // 6
                            }

                            if (isConvexQuad(tentativeQuad, fullW, fullH)) {
                                val cornerIndex = when (handleIdx) {
                                    0 -> 0 // TL
                                    6 -> 1 // TR
                                    4 -> 2 // BR
                                    else -> 3 // BL
                                }
                                val onQuadChangedCb = currentOnQuadChanged
                                if (onQuadChangedCb != null) {
                                    onQuadChangedCb(tentativeQuad)
                                } else {
                                    currentOnCornerUpdated(cornerIndex, snappedPt)
                                }
                            }

                            if (didSnap && !wasSnapped) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            wasSnapped = didSnap
                            isEdgeSnapped = didSnap
                            activeSnapGuides = guides
                        }
                    }
                )
            }
    ) {
        val viewW = size.width
        val viewH = size.height

        if (bitmap != null && imageBitmap != null) {
            val fullW = if (originalWidth > 0f) originalWidth else bitmap.width.toFloat()
            val fullH = if (originalHeight > 0f) originalHeight else bitmap.height.toFloat()
            val scale = min(viewW / fullW, viewH / fullH)
            val offsetX = (viewW - fullW * scale) / 2f
            val offsetY = (viewH - fullH * scale) / 2f

            fun imgToScreenX(x: Float) = x * scale + offsetX
            fun imgToScreenY(y: Float) = y * scale + offsetY
            fun imgToScreen(pt: PointF) = Offset(imgToScreenX(pt.x), imgToScreenY(pt.y))

            // 1. Draw base image Fit centered
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize((fullW * scale).toInt(), (fullH * scale).toInt())
            )

            val sTL = Offset(imgToScreenX(quad.topLeft.x), imgToScreenY(quad.topLeft.y))
            val sTR = Offset(imgToScreenX(quad.topRight.x), imgToScreenY(quad.topRight.y))
            val sBR = Offset(imgToScreenX(quad.bottomRight.x), imgToScreenY(quad.bottomRight.y))
            val sBL = Offset(imgToScreenX(quad.bottomLeft.x), imgToScreenY(quad.bottomLeft.y))

            val polyPath = Path().apply {
                moveTo(sTL.x, sTL.y)
                lineTo(sTR.x, sTR.y)
                lineTo(sBR.x, sBR.y)
                lineTo(sBL.x, sBL.y)
                close()
            }

            // 2. Dark semi-transparent scrim outside document area
            clipPath(polyPath, clipOp = ClipOp.Difference) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    size = size
                )
            }

            // 3. Alignment Snap Guide Lines
            activeSnapGuides.forEach { guide ->
                val gStart = imgToScreen(guide.start)
                val gEnd = imgToScreen(guide.end)
                drawLine(
                    color = Color(0xAA00E676),
                    start = gStart,
                    end = gEnd,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
                )
            }

            // 4. Document Border Lines with Snapping Highlights
            val themeTeal = Color(0xFF00E5FF)
            val snapGreen = Color(0xFF00E676)
            val screenCorners = listOf(sTL, sTR, sBR, sBL)

            fun edgeIndexToHandle(edgeIdx: Int): Int = when (edgeIdx) {
                0 -> 7 // Top edge
                1 -> 5 // Right edge
                2 -> 3 // Bottom edge
                else -> 1 // Left edge
            }

            for (i in 0 until 4) {
                val p1 = screenCorners[i]
                val p2 = screenCorners[(i + 1) % 4]
                val matchingHandle = edgeIndexToHandle(i)
                val isThisEdgeDragging = (draggingHandleIndex == matchingHandle)
                val isThisEdgeSnapped = (isThisEdgeDragging && isEdgeSnapped)

                if (isThisEdgeSnapped) {
                    // Snapped halo glow
                    drawLine(
                        color = snapGreen.copy(alpha = 0.35f),
                        start = p1,
                        end = p2,
                        strokeWidth = strokeWidthPx * 3.5f,
                        cap = StrokeCap.Round
                    )
                    // Snapped vivid core
                    drawLine(
                        color = snapGreen,
                        start = p1,
                        end = p2,
                        strokeWidth = strokeWidthPx * 1.8f,
                        cap = StrokeCap.Round
                    )
                } else if (isThisEdgeDragging) {
                    // Dragging active edge
                    drawLine(
                        color = themeTeal,
                        start = p1,
                        end = p2,
                        strokeWidth = strokeWidthPx * 1.4f,
                        cap = StrokeCap.Round
                    )
                } else {
                    // Normal idle edge
                    drawLine(
                        color = themeTeal,
                        start = p1,
                        end = p2,
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round
                    )
                }
            }

            // 5. Prominent Midpoint Handles
            for (i in 0 until 4) {
                val p1 = screenCorners[i]
                val p2 = screenCorners[(i + 1) % 4]
                val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)

                val matchingHandle = edgeIndexToHandle(i)
                val isThisEdgeDragging = (draggingHandleIndex == matchingHandle)
                val isThisEdgeSnapped = (isThisEdgeDragging && isEdgeSnapped)

                val outerRadius = if (isThisEdgeDragging) midpointHandleRadiusPx + 2.dp.toPx() else midpointHandleRadiusPx
                val borderColor = if (isThisEdgeSnapped) snapGreen else Color.White
                val fillColor = if (isThisEdgeSnapped) snapGreen else if (isThisEdgeDragging) themeTeal else Color(0xFF003840)

                // Drop shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f),
                    radius = outerRadius + 2.dp.toPx(),
                    center = mid
                )
                // Outer ring
                drawCircle(
                    color = borderColor,
                    radius = outerRadius,
                    center = mid
                )
                // Core fill
                drawCircle(
                    color = fillColor,
                    radius = outerRadius - 2.5.dp.toPx(),
                    center = mid
                )

                // Orientation-aware slider bar/pill inside handle
                val barLen = 5.dp.toPx()
                val barStroke = 2.dp.toPx()
                if (i == 0 || i == 2) {
                    // Horizontal edges (Top & Bottom): Horizontal grip bar
                    drawLine(
                        color = Color.White,
                        start = Offset(mid.x - barLen, mid.y),
                        end = Offset(mid.x + barLen, mid.y),
                        strokeWidth = barStroke,
                        cap = StrokeCap.Round
                    )
                } else {
                    // Vertical edges (Right & Left): Vertical grip bar
                    drawLine(
                        color = Color.White,
                        start = Offset(mid.x, mid.y - barLen),
                        end = Offset(mid.x, mid.y + barLen),
                        strokeWidth = barStroke,
                        cap = StrokeCap.Round
                    )
                }
            }

            // 6. Draw 4 Corner Handles
            val cornerHandleIndices = listOf(0, 6, 4, 2)
            screenCorners.forEachIndexed { idx, corner ->
                val handleIdx = cornerHandleIndices[idx]
                val isThisCornerDragging = (draggingHandleIndex == handleIdx)
                val isThisCornerSnapped = (isThisCornerDragging && isEdgeSnapped)
                val cornerColor = if (isThisCornerSnapped) snapGreen else themeTeal
                val cornerBorderColor = if (isThisCornerSnapped) snapGreen else Color.White

                drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = cornerHandleRadiusPx + 3.dp.toPx(), center = corner)
                drawCircle(color = cornerBorderColor, radius = cornerHandleRadiusPx + 1.5.dp.toPx(), center = corner)
                drawCircle(color = cornerColor, radius = cornerHandleRadiusPx, center = corner)
                drawCircle(color = Color.White, radius = cornerHandleRadiusPx * 0.35f, center = corner)
            }

            // 7. High-Precision Magnifier Loupe with Center Crosshair (SPEC_06 §6)
            // Visible exclusively when dragging a corner handle (0, 2, 4, 6); hidden on midpoint drags.
            val activeCornerImg = when (draggingHandleIndex) {
                0 -> quad.topLeft
                6 -> quad.topRight
                4 -> quad.bottomRight
                2 -> quad.bottomLeft
                else -> null
            }

            if (activeCornerImg != null) {
                val liftDist = 100.dp.toPx()
                var loupeCenterY = touchPosition.y - liftDist
                if (loupeCenterY - magRadiusPx < 20.dp.toPx()) {
                    loupeCenterY = touchPosition.y + liftDist
                }
                val loupeCenterX = touchPosition.x.coerceIn(
                    magRadiusPx + 16.dp.toPx(),
                    viewW - magRadiusPx - 16.dp.toPx()
                )
                val loupeCenter = Offset(loupeCenterX, loupeCenterY)

                val zoom = 2.8f
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    nativeCanvas.save()

                    loupeClipPath.rewind()
                    loupeClipPath.addCircle(loupeCenter.x, loupeCenter.y, magRadiusPx, android.graphics.Path.Direction.CW)
                    nativeCanvas.clipPath(loupeClipPath)
                    nativeCanvas.drawPaint(loupeBgPaint)

                    val downsampleRatio = fullW / bitmap.width.toFloat()
                    loupeMatrix.reset()
                    loupeMatrix.postTranslate(-activeCornerImg.x / downsampleRatio, -activeCornerImg.y / downsampleRatio)
                    loupeMatrix.postScale(scale * zoom * downsampleRatio, scale * zoom * downsampleRatio)
                    loupeMatrix.postTranslate(loupeCenter.x, loupeCenter.y)
                    nativeCanvas.drawBitmap(bitmap, loupeMatrix, loupeBmpPaint)

                    nativeCanvas.restore()
                }

                val loupeColor = if (isEdgeSnapped) snapGreen else themeTeal

                // Loupe outer chrome rings
                drawCircle(
                    color = if (isEdgeSnapped) snapGreen else Color.White,
                    radius = magRadiusPx + 3.dp.toPx(),
                    center = loupeCenter,
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = loupeColor,
                    radius = magRadiusPx + 1.dp.toPx(),
                    center = loupeCenter,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // High-Contrast Dual-Tone Crosshairs
                val crosshairLen = 18.dp.toPx()
                val crosshairGap = 4.dp.toPx()
                val chStroke = 2.dp.toPx()

                // Horizontal crosshairs
                drawLine(
                    color = Color.Black,
                    start = Offset(loupeCenter.x - crosshairLen, loupeCenter.y),
                    end = Offset(loupeCenter.x - crosshairGap, loupeCenter.y),
                    strokeWidth = chStroke + 2f
                )
                drawLine(
                    color = Color.White,
                    start = Offset(loupeCenter.x - crosshairLen, loupeCenter.y),
                    end = Offset(loupeCenter.x - crosshairGap, loupeCenter.y),
                    strokeWidth = chStroke
                )

                drawLine(
                    color = Color.Black,
                    start = Offset(loupeCenter.x + crosshairGap, loupeCenter.y),
                    end = Offset(loupeCenter.x + crosshairLen, loupeCenter.y),
                    strokeWidth = chStroke + 2f
                )
                drawLine(
                    color = Color.White,
                    start = Offset(loupeCenter.x + crosshairGap, loupeCenter.y),
                    end = Offset(loupeCenter.x + crosshairLen, loupeCenter.y),
                    strokeWidth = chStroke
                )

                // Vertical crosshairs
                drawLine(
                    color = Color.Black,
                    start = Offset(loupeCenter.x, loupeCenter.y - crosshairLen),
                    end = Offset(loupeCenter.x, loupeCenter.y - crosshairGap),
                    strokeWidth = chStroke + 2f
                )
                drawLine(
                    color = Color.White,
                    start = Offset(loupeCenter.x, loupeCenter.y - crosshairLen),
                    end = Offset(loupeCenter.x, loupeCenter.y - crosshairGap),
                    strokeWidth = chStroke
                )

                drawLine(
                    color = Color.Black,
                    start = Offset(loupeCenter.x, loupeCenter.y + crosshairGap),
                    end = Offset(loupeCenter.x, loupeCenter.y + crosshairLen),
                    strokeWidth = chStroke + 2f
                )
                drawLine(
                    color = Color.White,
                    start = Offset(loupeCenter.x, loupeCenter.y + crosshairGap),
                    end = Offset(loupeCenter.x, loupeCenter.y + crosshairLen),
                    strokeWidth = chStroke
                )

                // Center tiny target dot
                drawCircle(color = loupeColor, radius = 2.5.dp.toPx(), center = loupeCenter)
            }
        }
    }
}
