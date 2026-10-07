package com.scanner.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.ui.components.AspectRatioBanner
import com.scanner.app.ui.export.ExportDialog
import com.scanner.app.ui.theme.PrismCyan
import com.scanner.app.ui.theme.SteadyEmerald
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToCrop: (String) -> Unit,
    onExport: () -> Unit = {},
    viewModel: ReviewViewModel = viewModel()
) {
    val pages by viewModel.pages.collectAsState()
    val selectedPageId by viewModel.selectedPageId.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val imageVersion by viewModel.imageVersion.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }

    val selectedPage = remember(pages, selectedPageId) {
        pages.find { it.id == selectedPageId }
    }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedPageIds = remember { mutableStateListOf<String>() }
    var activeBatchPanel by remember { mutableStateOf<BatchPanelType?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
        selectedPageIds.clear()
        activeBatchPanel = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isMultiSelectMode) {
                        Text(
                            text = stringResource(R.string.selected_count, selectedPageIds.size),
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_title),
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.review_title, pages.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (isMultiSelectMode) {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedPageIds.clear()
                            activeBatchPanel = null
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    } else {
                        IconButton(onClick = onNavigateToCamera) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.camera_title)
                            )
                        }
                    }
                },
                actions = {
                    if (isMultiSelectMode) {
                        val allSelected = pages.isNotEmpty() && selectedPageIds.size == pages.size
                        TextButton(onClick = {
                            if (allSelected) {
                                selectedPageIds.clear()
                            } else {
                                selectedPageIds.clear()
                                selectedPageIds.addAll(pages.map { it.id })
                            }
                        }) {
                            Text(
                                text = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all),
                                color = PrismCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (pages.isNotEmpty()) {
                            TextButton(onClick = {
                                isMultiSelectMode = true
                                selectedPageIds.clear()
                                activeBatchPanel = null
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Checklist,
                                    contentDescription = null,
                                    tint = PrismCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.multi_select),
                                    color = PrismCyan,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (isMultiSelectMode) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                    color = Color(0xF20F172A),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val hasSelection = selectedPageIds.isNotEmpty()

                        // Sub-panel for Aspect Ratio
                        AnimatedVisibility(
                            visible = activeBatchPanel == BatchPanelType.RATIO && hasSelection,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.batch_apply_title, selectedPageIds.size),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                AspectRatioBanner(
                                    selectedRatio = AspectRatioPreset.CUSTOM,
                                    customRatioValue = null,
                                    onSelectRatio = { preset ->
                                        viewModel.setBatchAspectRatio(selectedPageIds.toList(), preset)
                                    },
                                    onSelectCustomRatio = { customRatio ->
                                        viewModel.setBatchCustomRatio(selectedPageIds.toList(), customRatio)
                                    }
                                )
                            }
                        }

                        // Sub-panel for Image Filter
                        AnimatedVisibility(
                            visible = activeBatchPanel == BatchPanelType.FILTER && hasSelection,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.batch_apply_title, selectedPageIds.size),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterOptionChip(
                                        label = stringResource(R.string.filter_original),
                                        icon = Icons.Default.Image,
                                        isSelected = false,
                                        onClick = { viewModel.setBatchFilter(selectedPageIds.toList(), ImageFilter.ORIGINAL) }
                                    )
                                    FilterOptionChip(
                                        label = stringResource(R.string.filter_magic),
                                        icon = Icons.Default.AutoAwesome,
                                        isSelected = false,
                                        onClick = { viewModel.setBatchFilter(selectedPageIds.toList(), ImageFilter.MAGIC_COLOR) }
                                    )
                                    FilterOptionChip(
                                        label = stringResource(R.string.filter_bw),
                                        icon = Icons.Default.Contrast,
                                        isSelected = false,
                                        onClick = { viewModel.setBatchFilter(selectedPageIds.toList(), ImageFilter.BW) }
                                    )
                                    FilterOptionChip(
                                        label = stringResource(R.string.filter_grayscale),
                                        icon = Icons.Default.FilterBAndW,
                                        isSelected = false,
                                        onClick = { viewModel.setBatchFilter(selectedPageIds.toList(), ImageFilter.GRAYSCALE) }
                                    )
                                }
                            }
                        }

                        if (!hasSelection) {
                            Text(
                                text = stringResource(R.string.batch_ratio_hint),
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Four action buttons dock: 比例, 滤镜, 导出为, 删除
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 比例 (Ratio)
                            BatchActionButton(
                                label = stringResource(R.string.ratio),
                                icon = Icons.Default.AspectRatio,
                                isActive = activeBatchPanel == BatchPanelType.RATIO,
                                enabled = hasSelection,
                                onClick = {
                                    activeBatchPanel = if (activeBatchPanel == BatchPanelType.RATIO) null else BatchPanelType.RATIO
                                }
                            )

                            // 2. 滤镜 (Filter)
                            BatchActionButton(
                                label = stringResource(R.string.filter),
                                icon = Icons.Default.AutoAwesome,
                                isActive = activeBatchPanel == BatchPanelType.FILTER,
                                enabled = hasSelection,
                                onClick = {
                                    activeBatchPanel = if (activeBatchPanel == BatchPanelType.FILTER) null else BatchPanelType.FILTER
                                }
                            )

                            // 3. 导出为 (Export As)
                            BatchActionButton(
                                label = stringResource(R.string.export_as),
                                icon = Icons.Default.Share,
                                isActive = false,
                                enabled = hasSelection,
                                onClick = {
                                    showExportDialog = true
                                }
                            )

                            // 4. 删除 (Delete)
                            BatchActionButton(
                                label = stringResource(R.string.delete),
                                icon = Icons.Default.Delete,
                                isActive = false,
                                enabled = hasSelection,
                                isDanger = true,
                                onClick = {
                                    showDeleteConfirmDialog = true
                                }
                            )
                        }
                    }
                }
            } else {
                // Floating Dock Action Bar
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onNavigateToCamera,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.add_more),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                showExportDialog = true
                                onExport()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            enabled = pages.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.export),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.review_title, 0),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToCamera,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.capture))
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(pages) { index, page ->
                    val bitmap = remember(page.imagePath, imageVersion) {
                        val path = page.thumbnailPath ?: page.imagePath
                        val file = File(path)
                        if (file.exists()) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeFile(path, opts)
                        } else null
                    }

                    val isSelected = selectedPageIds.contains(page.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (isMultiSelectMode) {
                                    if (isSelected) selectedPageIds.remove(page.id)
                                    else selectedPageIds.add(page.id)
                                } else {
                                    viewModel.selectPage(page.id)
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            if (isMultiSelectMode && isSelected) 2.5.dp else 1.dp,
                            if (isMultiSelectMode && isSelected) PrismCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (bitmap != null && page.status != com.scanner.app.domain.model.PageStatus.PROCESSING) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = SteadyEmerald,
                                            strokeWidth = 2.5.dp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "HDR...",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Top-Left Page Number Tag Pill
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xBB0A0F1D))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.page_number, index + 1),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (isMultiSelectMode) {
                                // Top-Right Selection Indicator Pill
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) PrismCyan else Color(0xBB0A0F1D))
                                        .border(1.5.dp, if (isSelected) PrismCyan else Color.White.copy(alpha = 0.8f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color(0xFF0F172A),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            } else {
                                // Top-Right Circular Delete Button
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xBB0A0F1D))
                                        .clickable { viewModel.deletePage(page.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete_page),
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Fullscreen Page Detail & Filter Adjustment Overlay
    selectedPage?.let { page ->
        BackHandler {
            viewModel.selectPage(null)
        }

        val pageIndex = pages.indexOfFirst { it.id == page.id } + 1
        val coroutineScope = rememberCoroutineScope()
        val scaleAnim = remember { Animatable(1.0f) }
        val offsetXAnim = remember { Animatable(0.0f) }
        val offsetYAnim = remember { Animatable(0.0f) }
        val rotationAngle = remember { Animatable(0f) }
        var isRotating by remember { mutableStateOf(false) }

        LaunchedEffect(page.id) {
            scaleAnim.snapTo(1.0f)
            offsetXAnim.snapTo(0.0f)
            offsetYAnim.snapTo(0.0f)
            rotationAngle.snapTo(0.0f)
        }

        val detailBitmap = remember(page.imagePath, imageVersion, page.status) {
            if (page.status == com.scanner.app.domain.model.PageStatus.PROCESSING) return@remember null
            val file = File(page.imagePath)
            if (file.exists()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.imagePath, bounds)
                var sampleSize = 1
                val maxDim = max(bounds.outWidth, bounds.outHeight)
                while ((maxDim / sampleSize) > 4096) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(page.imagePath, opts)
            } else null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0F1D))
        ) {
            // Main page image preview with dual zoom gestures (clearing top and bottom toolbars)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp, bottom = 96.dp)
                    .clipToBounds()
                    .pointerInput(page.id) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val currentScale = scaleAnim.value
                                // 3-stage double-tap zoom: 1.0x -> 2.5x -> 4.5x -> 1.0x
                                val nextScale = when {
                                    currentScale < 1.8f -> 2.5f
                                    currentScale < 3.5f -> 4.5f
                                    else -> 1.0f
                                }
                                val maxOx = if (nextScale > 1f) (w * (nextScale - 1f)) / 2f else 0f
                                val maxOy = if (nextScale > 1f) (h * (nextScale - 1f)) / 2f else 0f
                                val targetOx = if (nextScale > 1f) {
                                    (-(nextScale - 1f) * (tapOffset.x - w / 2f)).coerceIn(-maxOx, maxOx)
                                } else 0f
                                val targetOy = if (nextScale > 1f) {
                                    (-(nextScale - 1f) * (tapOffset.y - h / 2f)).coerceIn(-maxOy, maxOy)
                                } else 0f

                                coroutineScope.launch {
                                    launch {
                                        scaleAnim.animateTo(
                                            targetValue = nextScale,
                                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                        )
                                    }
                                    launch {
                                        offsetXAnim.animateTo(
                                            targetValue = targetOx,
                                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                        )
                                    }
                                    launch {
                                        offsetYAnim.animateTo(
                                            targetValue = targetOy,
                                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(page.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val currentScale = scaleAnim.value
                            val newScale = (currentScale * zoom).coerceIn(1.0f, 5.0f)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val maxOx = if (newScale > 1f) (w * (newScale - 1f)) / 2f else 0f
                            val maxOy = if (newScale > 1f) (h * (newScale - 1f)) / 2f else 0f
                            val newOx = if (newScale > 1f) {
                                (offsetXAnim.value + pan.x).coerceIn(-maxOx, maxOx)
                            } else 0f
                            val newOy = if (newScale > 1f) {
                                (offsetYAnim.value + pan.y).coerceIn(-maxOy, maxOy)
                            } else 0f

                            coroutineScope.launch {
                                scaleAnim.snapTo(newScale)
                                offsetXAnim.snapTo(newOx)
                                offsetYAnim.snapTo(newOy)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val containerW = constraints.maxWidth.toFloat()
                val containerH = constraints.maxHeight.toFloat()

                val targetRotScale = remember(detailBitmap, containerW, containerH) {
                    if (detailBitmap != null && detailBitmap.width > 0 && detailBitmap.height > 0 && containerW > 0 && containerH > 0) {
                        val bw = detailBitmap.width.toFloat()
                        val bh = detailBitmap.height.toFloat()
                        val sOrig = kotlin.math.min(containerW / bw, containerH / bh)
                        val sRot = kotlin.math.min(containerW / bh, containerH / bw)
                        if (sOrig > 0f) sRot / sOrig else 1.0f
                    } else 1.0f
                }

                val rotProgress = (rotationAngle.value / 90f).coerceIn(0f, 1f)
                val dynamicRotScale = 1.0f + (targetRotScale - 1.0f) * rotProgress

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scaleAnim.value * dynamicRotScale
                            scaleY = scaleAnim.value * dynamicRotScale
                            translationX = offsetXAnim.value
                            translationY = offsetYAnim.value
                            rotationZ = rotationAngle.value
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (detailBitmap != null) {
                        Image(
                            bitmap = detailBitmap.asImageBitmap(),
                            contentDescription = "Page $pageIndex",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        CircularProgressIndicator(
                            color = PrismCyan,
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            // Top Bar docked at top edge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                color = Color(0xEE0F172A),
                shadowElevation = 4.dp
            ) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Text(
                            text = stringResource(R.string.page_number, pageIndex),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.selectPage(null) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        // Re-crop button: opens CropScreen with original photo & quad
                        IconButton(onClick = {
                            viewModel.selectPage(null)
                            onNavigateToCrop(page.id)
                        }) {
                            Icon(
                                Icons.Default.Crop,
                                contentDescription = stringResource(R.string.re_crop),
                                tint = Color.White
                            )
                        }
                        // Rotate 90 CW button with smooth 250ms animation
                        IconButton(
                            enabled = !isRotating,
                            onClick = {
                                isRotating = true
                                coroutineScope.launch {
                                    val animJob = launch {
                                        rotationAngle.animateTo(
                                            targetValue = 90f,
                                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                        )
                                    }
                                    viewModel.rotatePage(page.id) {
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
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.rotate_90),
                                tint = Color.White
                            )
                        }
                        // Delete button
                        IconButton(onClick = {
                            viewModel.deletePage(page.id)
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = Color(0xFFFF6B6B)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }

            // Floating Aspect Ratio & Filter Capsule safely above navigation bars
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xEE0F172A),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                shadowElevation = 8.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val currentPreset = remember(page.targetAspectRatio) {
                        AspectRatioPreset.fromRatio(page.targetAspectRatio)
                    }

                    // Top Banner: Aspect Ratio Selection
                    AspectRatioBanner(
                        selectedRatio = currentPreset,
                        customRatioValue = if (currentPreset == AspectRatioPreset.CUSTOM) page.targetAspectRatio else null,
                        onSelectRatio = { preset ->
                            viewModel.setPageAspectRatio(page.id, preset)
                        },
                        onSelectCustomRatio = { customRatio ->
                            viewModel.setPageCustomRatio(page.id, customRatio)
                        },
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )

                    HorizontalDivider(
                        color = Color(0x1FFFFFFF),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Bottom Row: Image Filters
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterOptionChip(
                            label = stringResource(R.string.filter_original),
                            icon = Icons.Default.Image,
                            isSelected = page.filter == ImageFilter.ORIGINAL,
                            onClick = { viewModel.setPageFilter(page.id, ImageFilter.ORIGINAL) }
                        )
                        FilterOptionChip(
                            label = stringResource(R.string.filter_magic),
                            icon = Icons.Default.AutoAwesome,
                            isSelected = page.filter == ImageFilter.MAGIC_COLOR,
                            onClick = { viewModel.setPageFilter(page.id, ImageFilter.MAGIC_COLOR) }
                        )
                        FilterOptionChip(
                            label = stringResource(R.string.filter_bw),
                            icon = Icons.Default.Contrast,
                            isSelected = page.filter == ImageFilter.BW,
                            onClick = { viewModel.setPageFilter(page.id, ImageFilter.BW) }
                        )
                        FilterOptionChip(
                            label = stringResource(R.string.filter_grayscale),
                            icon = Icons.Default.FilterBAndW,
                            isSelected = page.filter == ImageFilter.GRAYSCALE,
                            onClick = { viewModel.setPageFilter(page.id, ImageFilter.GRAYSCALE) }
                        )
                    }
                }
            }

            // Filter processing spinner
            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xEE0F172A),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PrismCyan,
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            text = stringResource(R.string.applying_filter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Batch aspect ratio processing spinner when on grid
    AnimatedVisibility(
        visible = isProcessing && selectedPage == null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xEE0F172A),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = PrismCyan,
                    strokeWidth = 2.5.dp
                )
                Text(
                    text = stringResource(R.string.batch_processing, selectedPageIds.size.coerceAtLeast(1)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

    if (showExportDialog) {
        val exportPages = if (isMultiSelectMode && selectedPageIds.isNotEmpty()) {
            pages.filter { it.id in selectedPageIds }
        } else {
            pages
        }
        ExportDialog(
            pages = exportPages,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.delete_selected_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_selected_confirm, selectedPageIds.size),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idsToDelete = selectedPageIds.toList()
                        viewModel.deleteBatchPages(idsToDelete)
                        selectedPageIds.clear()
                        activeBatchPanel = null
                        showDeleteConfirmDialog = false
                        if (pages.size <= idsToDelete.size) {
                            isMultiSelectMode = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private enum class BatchPanelType {
    RATIO,
    FILTER
}

@Composable
private fun BatchActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean = false,
    enabled: Boolean = true,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        isDanger -> MaterialTheme.colorScheme.error
        isActive -> PrismCyan
        else -> Color.White
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun FilterOptionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrismCyan.copy(alpha = 0.25f) else Color(0x33FFFFFF)
    val border = if (isSelected) PrismCyan else Color(0x22FFFFFF)
    val contentColor = if (isSelected) PrismCyan else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
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
