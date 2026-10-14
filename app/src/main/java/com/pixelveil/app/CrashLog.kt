package com.pixelveil.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PixelVeilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Offline crash notes. Nothing is sent anywhere: the report stays on the phone and the user
 * can copy it from the Home screen to share it themselves.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crashlog"
    private const val KEY_SEEN = "seen_exit_ts"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(
                    "PixelVeil ${versionName(app)} crashed at ${stamp(System.currentTimeMillis())}\n" +
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${thread.name}\n\n" + sw.toString().take(12_000),
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A report about the last unexpected exit, or null if there is nothing new. */
    fun readReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0L)
            if (last.timestamp <= seen) return null
            val reason = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by Android: low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed by Android: excessive resource use"
                else -> return null
            }
            "PixelVeil ${versionName(context)} closed at ${stamp(last.timestamp)}\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Reason: $reason\nDetails: ${last.description ?: "none"}\n" +
                "Memory at exit: ${last.pss / 1024} MB"
        } catch (_: Throwable) {
            null
        }
    }

    fun acknowledge(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SEEN, System.currentTimeMillis()).apply()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
