package com.scanner.app.ui.camera

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.view.OrientationEventListener
import android.view.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.engine.NativeEdgeDetector
import com.scanner.app.ui.theme.PrismCyan
import com.scanner.app.ui.theme.SteadyEmerald
import com.scanner.app.ui.theme.SteadyAmber
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToCrop: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detectedQuad by viewModel.detectedQuad.collectAsState()
    val curvedModeEnabled by viewModel.curvedModeEnabled.collectAsState()
    val fullHdrEnabled by viewModel.fullHdrEnabled.collectAsState()
    val isStable by viewModel.isStable.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val capturedPages by viewModel.capturedPages.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.updateSettings(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.updateSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var deviceRotationDegrees by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                viewModel.imageCapture?.targetRotation = rotation

                val rawTarget = when (rotation) {
                    Surface.ROTATION_0 -> 0f
                    Surface.ROTATION_90 -> 90f
                    Surface.ROTATION_180 -> 180f
                    Surface.ROTATION_270 -> 270f
                    else -> 0f
                }
                var diff = (rawTarget - deviceRotationDegrees) % 360f
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                deviceRotationDegrees += diff
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    val uiRotation by animateFloatAsState(
        targetValue = deviceRotationDegrees,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "uiRotation"
    )

    val lastPage = capturedPages.lastOrNull()
    val lastThumbnailBitmap = remember(lastPage?.id, lastPage?.thumbnailPath, lastPage?.imagePath) {
        val path = lastPage?.thumbnailPath ?: lastPage?.imagePath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, boundsOpts)
                    val sampleSize = max(1, min(boundsOpts.outWidth / 120, boundsOpts.outHeight / 120))
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeFile(path, opts)
                } catch (e: Exception) { null }
            } else null
        } else null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importFromUri(context, uri) { pageId -> onNavigateToCrop(pageId) }
        }
    }

    // ─── Tap-to-focus state (SPEC_16 §4) ───
    // tapFocusOffset: screen pixel coords of last tap; null = no active focus ring
    var tapFocusOffset by remember { mutableStateOf<Offset?>(null) }
    // We need a reference to the PreviewView to create a MeteringPointFactory
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(tapFocusOffset) {
        if (tapFocusOffset != null) {
            // Focus ring shows for 1.5s then auto-clears (camera also auto-cancels after 3s)
            delay(1500)
            tapFocusOffset = null
        }
    }

    // ─── Ring & pulse animations ───
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val targetRingColor = when {
        fullHdrEnabled -> Color(0xFFFFB74D)
        isStable -> SteadyEmerald
        else -> Color.White.copy(alpha = 0.45f)
    }
    val ringColor by animateColorAsState(
        targetValue = targetRingColor,
        animationSpec = tween(durationMillis = 250),
        label = "stabilityRingColor"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (isStable || fullHdrEnabled) 4.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 250),
        label = "ringWidth"
    )

    val shutterInteractionSource = remember { MutableInteractionSource() }
    val isShutterPressed by shutterInteractionSource.collectIsPressedAsState()
    val shutterScale by animateFloatAsState(
        targetValue = if (isShutterPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "shutterScale"
    )

    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    DisposableEffect(Unit) {
        onDispose { cameraControl?.enableTorch(false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ─── 1. Fullscreen Viewfinder ───
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                previewViewRef.value = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val sensorResolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO
                            )
                        )
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(sensorResolutionSelector)
                        .build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    // Low latency capture mode for rapid multi-frame burst (SPEC_16 §2)
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95)
                        .setResolutionSelector(sensorResolutionSelector)

                    val camera2Extender = Camera2Interop.Extender(imageCaptureBuilder)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    camera2Extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

                    val imageCapture = imageCaptureBuilder.build()
                    viewModel.imageCapture = imageCapture

                    val edgeDetector = NativeEdgeDetector()
                    val frameAnalyzer = com.scanner.app.data.camera.FrameAnalyzer(
                        detector = edgeDetector,
                        curvedMode = curvedModeEnabled
                    ) { result -> viewModel.onFrameAnalyzed(result) }
                    viewModel.frameAnalyzer = frameAnalyzer

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1280, 960),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                                    )
                                )
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            val analyzerExecutor = Executors.newSingleThreadExecutor()
                            it.setAnalyzer(analyzerExecutor, frameAnalyzer)
                        }
                    viewModel.imageAnalysis = imageAnalysis

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                        viewModel.cameraControl = camera.cameraControl
                        viewModel.cameraInfo = camera.cameraInfo
                        camera.cameraControl.enableTorch(isTorchOn)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)

                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Tap-to-focus: AF + AE at tap location (SPEC_16 §4)
                    detectTapGestures { offset ->
                        tapFocusOffset = offset
                        val pv = previewViewRef.value ?: return@detectTapGestures
                        val ctrl = cameraControl ?: return@detectTapGestures
                        val factory = pv.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point)
                            .addPoint(point, FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        ctrl.startFocusAndMetering(action)
                    }
                }
        )

        // ─── 2. Real-time Document Edge Overlay ───
        detectedQuad?.let { quad ->
            EdgeOverlay(
                detectionResult = quad,
                isCurvedMode = curvedModeEnabled,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ─── 3. Tap-to-Focus Ring (SPEC_16 §4.3) ───
        tapFocusOffset?.let { offset ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val focusRingSize = 72.dp
            val offsetXDp = with(density) { offset.x.toDp() }
            val offsetYDp = with(density) { offset.y.toDp() }
            Box(
                modifier = Modifier
                    .offset(
                        x = offsetXDp - focusRingSize / 2,
                        y = offsetYDp - focusRingSize / 2
                    )
                    .size(focusRingSize)
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
            )
        }

        // ─── 4. Top Status Bar ───
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Torch toggle
            val torchBg = if (isTorchOn) Color(0x66FBBF24) else Color(0x990A0F1D)
            val torchBorder = if (isTorchOn) Color(0xFFFBBF24) else Color(0x2AFFFFFF)
            val torchTint = if (isTorchOn) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.85f)

            IconButton(
                onClick = {
                    val nextState = !isTorchOn
                    isTorchOn = nextState
                    cameraControl?.enableTorch(nextState)
                },
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(torchBg)
                    .border(1.2.dp, torchBorder, CircleShape)
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Torch",
                    tint = torchTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Stability pill
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x990A0F1D))
                    .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val dotColor = if (isStable) SteadyEmerald else SteadyAmber
                    val effectiveAlpha = if (isStable) pulseAlpha else 1.0f
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(dotColor.copy(alpha = effectiveAlpha), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStable) stringResource(R.string.steady) else stringResource(R.string.hold_steady),
                        color = if (isStable) SteadyEmerald else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                }
            }

            // Settings button
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(CircleShape)
                    .background(Color(0x990A0F1D))
                    .border(1.dp, Color(0x2AFFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ─── 5. Center Capture Processing Modal ───
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { rotationZ = uiRotation }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE60F172A))
                    .border(
                        1.dp,
                        if (fullHdrEnabled) Color(0xFFFFB74D).copy(alpha = 0.5f) else Color(0x33FFFFFF),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = if (fullHdrEnabled) Color(0xFFFFB74D) else SteadyEmerald,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (fullHdrEnabled)
                            stringResource(R.string.fusing_full_hdr)
                        else
                            stringResource(R.string.processing),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        // ─── 6. Bottom Controls ───
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode capsule row: Curved Dewarp
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val curveBg = if (curvedModeEnabled) SteadyEmerald.copy(alpha = 0.22f) else Color(0x770A0F1D)
                val curveBorder = if (curvedModeEnabled) SteadyEmerald else Color(0x2EFFFFFF)
                val curveTextColor = if (curvedModeEnabled) SteadyEmerald else Color(0xFFE2E8F0)

                Box(
                    modifier = Modifier
                        .graphicsLayer { rotationZ = uiRotation }
                        .clip(RoundedCornerShape(24.dp))
                        .background(curveBg)
                        .border(1.2.dp, curveBorder, RoundedCornerShape(24.dp))
                        .clickable { viewModel.toggleCurvedMode() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (curvedModeEnabled) SteadyEmerald else Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (curvedModeEnabled) stringResource(R.string.curve_on) else stringResource(R.string.curve_off),
                            fontSize = 12.sp,
                            fontWeight = if (curvedModeEnabled) FontWeight.Bold else FontWeight.Medium,
                            color = curveTextColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Shutter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Gallery import
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .graphicsLayer { rotationZ = uiRotation }
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x990A0F1D))
                            .border(1.5.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { galleryLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = stringResource(R.string.filter_original),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Center: Shutter
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .border(width = ringWidth, color = ringColor, shape = CircleShape)
                        .padding(6.dp)
                        .scale(shutterScale),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(54.dp),
                            color = if (fullHdrEnabled) Color(0xFFFFB74D) else SteadyEmerald,
                            strokeWidth = 3.5.dp
                        )
                    } else {
                        val innerColor = if (fullHdrEnabled) Color(0xFFFFB74D) else Color.White
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(innerColor)
                                .clickable(
                                    interactionSource = shutterInteractionSource,
                                    indication = null
                                ) {
                                    viewModel.capturePhoto(context) { pageId ->
                                        onNavigateToCrop(pageId)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (fullHdrEnabled) {
                                Icon(
                                    imageVector = Icons.Default.HdrOn,
                                    contentDescription = stringResource(R.string.capture),
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .graphicsLayer { rotationZ = uiRotation }
                                )
                            }
                        }
                    }
                }

                // Right: Document gallery with count badge
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (pageCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .graphicsLayer { rotationZ = uiRotation }
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x990A0F1D))
                                .border(1.5.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                                .clickable { onNavigateToReview() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (lastThumbnailBitmap != null) {
                                Image(
                                    bitmap = lastThumbnailBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.review),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Collections,
                                    contentDescription = stringResource(R.string.review),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .clip(CircleShape)
                                    .background(SteadyEmerald)
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$pageCount",
                                    color = Color(0xFF0F172A),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
