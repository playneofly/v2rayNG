package com.v2ray.ang.handler

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FILTERNET: catches every uncaught exception in every process and stores the
 * stack trace on disk, so the next app start can show the user exactly what
 * went wrong instead of the bare "FILTERNET keeps stopping" system dialog.
 */
object FilternetCrashHandler {

    private const val FILE_NAME = "filternet_last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val process = runCatching { android.app.Application.getProcessName() }.getOrNull()
        val body = buildString {
            appendLine("FILTERNET crash report")
            appendLine("time: $stamp")
            appendLine("process: ${process ?: "?"}")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("----------------------------------------")
            append(sw.toString())
        }
        file(context).writeText(body)
    }

    /** Returns the stored crash report, if any. */
    fun consume(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}
