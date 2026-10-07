package com.scanner.app.domain.model

import java.util.UUID

enum class PageStatus {
    PROCESSING,
    READY,
    FAILED
}

data class ScannedPage(
    val id: String = UUID.randomUUID().toString(),
    val originalImagePath: String,
    val processedImagePath: String? = null,
    val thumbnailPath: String? = null,
    val quad: DocumentQuad? = null,
    val curvedBoundary: CurvedBoundary? = null,
    val filter: ImageFilter = ImageFilter.MAGIC_COLOR,
    val rotation: Int = 0, // 0, 90, 180, 270
    val targetAspectRatio: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: PageStatus = PageStatus.READY
) {
    val imagePath: String
        get() = processedImagePath ?: originalImagePath

    constructor(
        id: String = UUID.randomUUID().toString(),
        imagePath: String,
        thumbnailPath: String? = null,
        quad: DocumentQuad? = null,
        curvedBoundary: CurvedBoundary? = null,
        filter: ImageFilter = ImageFilter.MAGIC_COLOR,
        rotation: Int = 0,
        targetAspectRatio: Float? = null,
        createdAt: Long = System.currentTimeMillis(),
        status: PageStatus = PageStatus.READY
    ) : this(
        id = id,
        originalImagePath = imagePath,
        processedImagePath = null,
        thumbnailPath = thumbnailPath,
        quad = quad,
        curvedBoundary = curvedBoundary,
        filter = filter,
        rotation = rotation,
        targetAspectRatio = targetAspectRatio,
        createdAt = createdAt,
        status = status
    )
}

enum class ImageFilter {
    ORIGINAL,
    MAGIC_COLOR,
    GRAYSCALE,
    BW
}
