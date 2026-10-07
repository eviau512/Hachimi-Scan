package com.scanner.app.data.util

import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.scanner.app.BuildConfig
import java.io.File
import java.io.FileDescriptor

object ExifUtils {

    private val CAMERA_TAGS = arrayOf(
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION
    )

    fun extractMode(exif: ExifInterface): String? {
        val desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        if (!desc.isNullOrBlank()) {
            if (desc.contains("Full HDR", ignoreCase = true)) return "Full HDR"
            if (desc.contains("50MP HDR", ignoreCase = true)) return "50MP HDR"
            if (desc.contains("50MP", ignoreCase = true)) return "50MP"
            if (desc.contains("HDR", ignoreCase = true)) return "HDR"
            if (desc.contains("Normal", ignoreCase = true)) return "Normal"
        }
        val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
        if (!comment.isNullOrBlank()) {
            if (comment.contains("Full HDR", ignoreCase = true)) return "Full HDR"
            if (comment.contains("50MP HDR", ignoreCase = true)) return "50MP HDR"
            if (comment.contains("50MP", ignoreCase = true)) return "50MP"
            if (comment.contains("HDR", ignoreCase = true)) return "HDR"
            if (comment.contains("Normal", ignoreCase = true)) return "Normal"
        }
        val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
        if (!software.isNullOrBlank()) {
            if (software.contains("[Full HDR]", ignoreCase = true)) return "Full HDR"
            if (software.contains("[50MP HDR]", ignoreCase = true)) return "50MP HDR"
            if (software.contains("[50MP]", ignoreCase = true)) return "50MP"
            if (software.contains("[HDR]", ignoreCase = true)) return "HDR"
            if (software.contains("[Normal]", ignoreCase = true)) return "Normal"
        }
        return null
    }

    fun stampSignature(dstExif: ExifInterface, mode: String? = null) {
        try {
            val resolvedMode = mode ?: extractMode(dstExif)
            val modeSuffix = if (!resolvedMode.isNullOrBlank()) " [$resolvedMode]" else ""
            dstExif.setAttribute(
                ExifInterface.TAG_SOFTWARE,
                "HachiCam v0.1.1 (${BuildConfig.GIT_HASH})$modeSuffix"
            )
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, BuildConfig.GIT_HASH)
            dstExif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "HachiCam v0.1.1 (Build: ${BuildConfig.GIT_HASH}, ${BuildConfig.BUILD_TIME})${if (!resolvedMode.isNullOrBlank()) ", Mode: $resolvedMode" else ""}"
            )
            if (!resolvedMode.isNullOrBlank()) {
                dstExif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Capture Mode: $resolvedMode")
            }
            if (BuildConfig.DEBUG) {
                if (dstExif.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank()) {
                    dstExif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                }
                if (dstExif.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) {
                    dstExif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                }
            } else {
                dstExif.setAttribute(ExifInterface.TAG_MAKE, null)
                dstExif.setAttribute(ExifInterface.TAG_MODEL, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stampSignature(file: File, mode: String? = null) {
        if (!file.exists()) return
        try {
            val exif = ExifInterface(file.absolutePath)
            stampSignature(exif, mode)
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstExif: ExifInterface, mode: String? = null) {
        if (!srcFile.exists()) return
        try {
            val srcExif = ExifInterface(srcFile.absolutePath)
            for (tag in CAMERA_TAGS) {
                val value = srcExif.getAttribute(tag)
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            }
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            val effectiveMode = mode ?: extractMode(srcExif)
            stampSignature(dstExif, effectiveMode)
            dstExif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstFile: File, mode: String? = null) {
        if (!srcFile.exists() || !dstFile.exists()) return
        try {
            val dstExif = ExifInterface(dstFile.absolutePath)
            copyAndStampExif(srcFile, dstExif, mode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstFd: FileDescriptor, mode: String? = null) {
        if (!srcFile.exists()) return
        try {
            val dstExif = ExifInterface(dstFd)
            copyAndStampExif(srcFile, dstExif, mode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcBytes: ByteArray, dstFile: File, mode: String? = null) {
        if (!dstFile.exists()) return
        try {
            val dstExif = ExifInterface(dstFile.absolutePath)
            java.io.ByteArrayInputStream(srcBytes).use { inStream ->
                val srcExif = ExifInterface(inStream)
                for (tag in CAMERA_TAGS) {
                    val value = srcExif.getAttribute(tag)
                    if (value != null) {
                        dstExif.setAttribute(tag, value)
                    }
                }
                dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                val effectiveMode = mode ?: extractMode(srcExif)
                stampSignature(dstExif, effectiveMode)
                dstExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
