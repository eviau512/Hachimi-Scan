package com.scanner.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.opencv.android.OpenCVLoader
import java.util.Locale

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize OpenCV
        OpenCVLoader.initLocal()

        // Initialize saved language if configured
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val langCode = prefs.getString("language_code", null)
        if (!langCode.isNullOrEmpty()) {
            val tag = if (langCode == "zh") "zh-CN" else langCode
            val locale = if (langCode == "zh") Locale.SIMPLIFIED_CHINESE else Locale(langCode)
            Locale.setDefault(locale)
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        // Clean legacy orphaned temp files in cacheDir on launch
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.startsWith("hdr_") || file.name.endsWith(".jpg"))) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
