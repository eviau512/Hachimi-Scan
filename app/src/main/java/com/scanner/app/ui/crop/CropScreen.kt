package com.scanner.app.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.ui.components.AspectRatioBanner
import com.scanner.app.ui.theme.PrismCyan
import com.scanner.app.ui.theme.SteadyEmerald
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    pageId: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    viewModel: CropViewModel = viewModel()
) {
    LaunchedEffect(pageId) {
        viewModel.loadPage(pageId)
    }

    val currentQuad by viewModel.currentQuad.collectAsState()
    val detectedQuad by viewModel.detectedQuad.collectAsState()
    val edgeMatAddr by viewModel.edgeMatAddr.collectAsState()
    val horizontalLines by viewModel.horizontalLines.collectAsState()
    val verticalLines by viewModel.verticalLines.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedRatio by viewModel.selectedRatio.collectAsState()
    val customRatioValue by viewModel.customRatioValue.collectAsState()
    val imagePath by viewModel.imagePath.collectAsState()
    val imageVersion by viewModel.imageVersion.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    var isSaving by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val rotationAngle = remember { Animatable(0f) }
    var isRotating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.crop_title),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !isRotating && !isSaving && !isProcessing,
                        onClick = {
                            isRotating = true
                            coroutineScope.launch {
                                val animJob = launch {
                                    rotationAngle.animateTo(
                                        targetValue = 90f,
                                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                    )
                                }
                                viewModel.rotateImage {
                                    coroutineScope.launch {
                                        animJob.join()
                                        rotationAngle.snapTo(0f)
                                        isRotating = false
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.rotate_90),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        enabled = !isRotating && !isSaving && !isProcessing,
                        onClick = { viewModel.resetToFullImage() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = stringResource(R.string.full_image),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        enabled = !isRotating && !isSaving && !isProcessing,
                        onClick = { viewModel.reDetect() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = stringResource(R.string.auto_detect),
                            tint = PrismCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0F172A),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Aspect ratio banner row directly above the filter selector
                    AspectRatioBanner(
                        selectedRatio = selectedRatio,
                        customRatioValue = customRatioValue,
                        onSelectRatio = { viewModel.setAspectRatio(it) },
                        onSelectCustomRatio = { viewModel.setCustomRatio(it) },
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    HorizontalDivider(
                        color = Color(0x1FFFFFFF),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CropFilterChip(
                                label = stringResource(R.string.filter_original),
                                icon = Icons.Default.Image,
                                isSelected = selectedFilter == ImageFilter.ORIGINAL,
                                onClick = { viewModel.setFilter(ImageFilter.ORIGINAL) }
                            )
                            CropFilterChip(
                                label = stringResource(R.string.filter_magic),
                                icon = Icons.Default.AutoAwesome,
                                isSelected = selectedFilter == ImageFilter.MAGIC_COLOR,
                                onClick = { viewModel.setFilter(ImageFilter.MAGIC_COLOR) }
                            )
                            CropFilterChip(
                                label = stringResource(R.string.filter_bw),
                                icon = Icons.Default.Contrast,
                                isSelected = selectedFilter == ImageFilter.BW,
                                onClick = { viewModel.setFilter(ImageFilter.BW) }
                            )
                            CropFilterChip(
                                label = stringResource(R.string.filter_grayscale),
                                icon = Icons.Default.FilterBAndW,
                                isSelected = selectedFilter == ImageFilter.GRAYSCALE,
                                onClick = { viewModel.setFilter(ImageFilter.GRAYSCALE) }
                            )
                        }

                        // Confirm Action Button
                        Button(
                            enabled = !isSaving && !isRotating && !isProcessing,
                            onClick = {
                                isSaving = true
                                viewModel.confirmCrop {
                                    isSaving = false
                                    onConfirm()
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SteadyEmerald,
                                contentColor = Color(0xFF0F172A)
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF0F172A),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.confirm),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.confirm),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0A0F1D))
        ) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()

            if (isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = SteadyEmerald,
                            strokeWidth = 3.5.dp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "正在进行高动态多帧超分融合...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                val (origW, origH) = remember(imagePath, imageVersion) {
                    imagePath?.let { getUprightDimensions(it) } ?: Pair(0f, 0f)
                }

                val bitmap = remember(imagePath, imageVersion) {
                    imagePath?.let { path ->
                        loadUprightBitmap(path)
                    }
                }

                LaunchedEffect(bitmap, origW, origH) {
                    if (bitmap != null) {
                        viewModel.onBitmapLoaded(bitmap, origW, origH)
                    }
                }

                // Continuous rotation scale compensation:
                // When rotating 90 deg, fit the rotated aspect ratio without clipping or layout jumping
                val targetScale = remember(bitmap, containerW, containerH) {
                    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0 && containerW > 0 && containerH > 0) {
                        val bw = bitmap.width.toFloat()
                        val bh = bitmap.height.toFloat()
                        val sOrig = kotlin.math.min(containerW / bw, containerH / bh)
                        val sRot = kotlin.math.min(containerW / bh, containerH / bw)
                        if (sOrig > 0f) sRot / sOrig else 1.0f
                    } else 1.0f
                }

                val rotProgress = (rotationAngle.value / 90f).coerceIn(0f, 1f)
                val dynamicScale = 1.0f + (targetScale - 1.0f) * rotProgress

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = dynamicScale
                            scaleY = dynamicScale
                            rotationZ = rotationAngle.value
                        }
                ) {
                    QuadCropView(
                        bitmap = bitmap,
                        quad = currentQuad,
                        detectedQuad = detectedQuad,
                        edgeMatAddr = edgeMatAddr,
                        horizontalLines = horizontalLines,
                        verticalLines = verticalLines,
                        originalWidth = origW,
                        originalHeight = origH,
                        onCornerUpdated = { cornerIndex, newPosition ->
                            viewModel.updateCorner(cornerIndex, newPosition)
                        },
                        onEdgeUpdated = { edgeIndex, deltaX, deltaY ->
                            viewModel.updateEdge(edgeIndex, deltaX, deltaY)
                        },
                        onQuadChanged = { newQuad ->
                            viewModel.updateQuad(newQuad)
                        },
                        onTapToSnap = { x, y ->
                            viewModel.tapToSnap(x, y)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            AnimatedVisibility(
                visible = isSaving,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xEE0F172A),
                            border = BorderStroke(1.dp, PrismCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = PrismCyan,
                                    strokeWidth = 2.5.dp
                                )
                                Text(
                                    text = stringResource(R.string.processing),
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF)
    val border = if (isSelected) PrismCyan else Color(0x22FFFFFF)
    val contentColor = if (isSelected) PrismCyan else Color(0xFFE2E8F0)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun CropFilterChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF)
    val border = if (isSelected) PrismCyan else Color(0x22FFFFFF)
    val contentColor = if (isSelected) PrismCyan else Color(0xFFE2E8F0)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = contentColor
            )
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}

private fun getUprightDimensions(path: String): Pair<Float, Float> {
    val f = File(path)
    if (!f.exists()) return Pair(0f, 0f)
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return Pair(0f, 0f)
    val exif = try { ExifInterface(path) } catch (e: Exception) { null }
    val orientation = exif?.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    ) ?: ExifInterface.ORIENTATION_NORMAL
    return if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
        Pair(boundsOpts.outHeight.toFloat(), boundsOpts.outWidth.toFloat())
    } else {
        Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }
}

private fun loadUprightBitmap(path: String, maxDimension: Int = 4096): Bitmap? {
    val f = File(path)
    if (!f.exists()) return null
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

    var inSampleSize = 1
    val maxSide = max(boundsOpts.outWidth, boundsOpts.outHeight)
    while ((maxSide / inSampleSize) > maxDimension) {
        inSampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = BitmapFactory.decodeFile(path, decodeOpts) ?: return null

    return try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp) {
            bmp.recycle()
        }
        rotated
    } catch (e: Exception) {
        bmp
    }
}
