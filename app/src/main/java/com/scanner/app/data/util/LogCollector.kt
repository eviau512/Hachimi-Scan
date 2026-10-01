package com.scanner.app.data.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.scanner.app.BuildConfig
import com.scanner.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCollector {

    suspend fun collectAndShareLogs(context: Context) = withContext(Dispatchers.IO) {
        try {
            val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }

            // Keep only the 5 most recent diagnostic logs
            val oldLogs = logsDir.listFiles { file -> file.isFile && file.name.startsWith("hachicam_log_") }
            oldLogs?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logsDir, "hachicam_log_$timeStamp.txt")

            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

            sb.appendLine("==================================================")
            sb.appendLine("             HACHICAM DIAGNOSTIC LOG              ")
            sb.appendLine("==================================================")
            sb.appendLine("Generated At   : ${dateFormat.format(Date())}")
            sb.appendLine()

            // App Information
            sb.appendLine("--- App Information ---")
            sb.appendLine("Application ID : ${BuildConfig.APPLICATION_ID}")
            sb.appendLine("Version Name   : ${BuildConfig.VERSION_NAME}")
            sb.appendLine("Version Code   : ${BuildConfig.VERSION_CODE}")
            sb.appendLine("Build Type     : ${BuildConfig.BUILD_TYPE}")
            sb.appendLine("Git Hash       : ${BuildConfig.GIT_HASH}")
            sb.appendLine("Build Time     : ${BuildConfig.BUILD_TIME}")
            sb.appendLine()

            // Device Information
            sb.appendLine("--- Device Information ---")
            sb.appendLine("Manufacturer   : ${Build.MANUFACTURER}")
            sb.appendLine("Brand          : ${Build.BRAND}")
            sb.appendLine("Model          : ${Build.MODEL}")
            sb.appendLine("Product        : ${Build.PRODUCT}")
            sb.appendLine("Device         : ${Build.DEVICE}")
            sb.appendLine("Board          : ${Build.BOARD}")
            sb.appendLine("Hardware       : ${Build.HARDWARE}")
            sb.appendLine("Android OS     : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine("Fingerprint    : ${Build.FINGERPRINT}")
            sb.appendLine("Supported ABIs : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            sb.appendLine()

            // SharedPreferences State
            sb.appendLine("--- SharedPreferences State ---")
            try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val allPrefs = prefs.all
                if (allPrefs.isEmpty()) {
                    sb.appendLine("(No preferences recorded yet)")
                } else {
                    allPrefs.toSortedMap().forEach { (k, v) ->
                        sb.appendLine("$k = $v")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("Failed to read settings: ${e.message}")
            }
            sb.appendLine()

            // Memory Status
            sb.appendLine("--- Runtime Memory Status ---")
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory() / (1024 * 1024)
            val totalMem = runtime.totalMemory() / (1024 * 1024)
            val freeMem = runtime.freeMemory() / (1024 * 1024)
            val usedMem = totalMem - freeMem
            sb.appendLine("Memory Usage   : Used: ${usedMem}MB / Total: ${totalMem}MB / Max: ${maxMem}MB")
            sb.appendLine()

            // Logcat Capture (App process + Camera HAL logs via Root if granted)
            sb.appendLine("--- Recent Logcat (Process UID & Camera HAL Logs) ---")
            try {
                val hasSu = try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    p.waitFor() == 0
                } catch (e: Exception) {
                    false
                }

                val process = if (hasSu) {
                    sb.appendLine("(Root permission detected: capturing system & Qualcomm Camera HAL logs)")
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -d -v time -t 4000"))
                } else {
                    Runtime.getRuntime().exec("logcat -d -v time -t 2000")
                }

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    var count = 0
                    while (reader.readLine().also { line = it } != null) {
                        sb.appendLine(line)
                        count++
                    }
                    if (count == 0) {
                        sb.appendLine("(logcat stream returned 0 lines)")
                    }
                }
                process.waitFor()
            } catch (e: Exception) {
                sb.appendLine("Failed to collect logcat: ${e.message}")
            }
            sb.appendLine()
            sb.appendLine("=================== END OF LOG ===================")

            logFile.writeText(sb.toString(), Charsets.UTF_8)

            withContext(Dispatchers.Main) {
                shareLogFile(context, logFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Export log failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLogFile(context: Context, logFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, logFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HachiCam Diagnostic Log - ${logFile.name}")
            putExtra(Intent.EXTRA_TEXT, "HachiCam v0.1.1 (${BuildConfig.GIT_HASH}) Diagnostic Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, context.getString(R.string.share_logs_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
