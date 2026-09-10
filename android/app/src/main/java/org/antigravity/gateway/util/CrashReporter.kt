package org.antigravity.gateway.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions into the app's private storage so OEM-specific launch crashes
 * (for example MIUI/HyperOS variants) can still be diagnosed after the fact.
 *
 * The report is written before the process dies and is surfaced from the status card, where the
 * user can copy it. Only the newest [MAX_REPORTS] files are kept; older ones are deleted.
 *
 * Two copies are attempted: the private `files/crash-logs` directory and the app-external one
 * (`Android/data/<pkg>/files/crash-logs`), which a PC can open over USB without root even when
 * the app itself cannot start any more.
 */
object CrashReporter {

    const val DIR_NAME = "crash-logs"
    const val MAX_REPORTS = 5
    private const val ATTEMPTS_FILE = "startup-attempts"
    /** Launch attempts without a healthy UI before the app falls back to safe mode. */
    const val SAFE_MODE_THRESHOLD = 3
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"
    private val FILE_NAME_PATTERN = Regex("^crash-(\\d{8}-\\d{6}-\\d{3})\\.txt$")
    private const val TAG = "CrashReporter"

    @Volatile
    private var installed = false

    /** Chains a handler in front of the platform default so normal crash reporting still runs. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (write(appContext, thread, throwable) == null) {
                    Log.e(TAG, "crash storage unavailable; report not persisted")
                }
            } catch (writeError: Throwable) {
                Log.e(TAG, "failed to persist crash report", writeError)
            }
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "uncaught exception handler installed")
    }

    /** Returns the file the report was written to, or null when storage is unavailable. */
    fun write(context: Context, thread: Thread, throwable: Throwable): File? {
        val name = "$FILE_PREFIX${timestamp()}$FILE_SUFFIX"
        val body = CrashReportFormat.format(header(context, thread), throwable)
        var first: File? = null
        val targets = directories(context)
        for (dir in targets) {
            val file = File(dir, name)
            runCatching { file.writeText(body, Charsets.UTF_8) }
                .onSuccess {
                    if (first == null) first = file
                    Log.i(TAG, "crash report stored at ${file.absolutePath}")
                }
                .onFailure { Log.e(TAG, "cannot write crash report to ${dir.absolutePath}", it) }
            deleteOutdated(dir)
        }
        if (first == null) Log.e(TAG, "crash storage unavailable; report not persisted")
        return first
    }

    /** Every directory that can hold reports, internal first. */
    fun directories(context: Context): List<File> = listOfNotNull(
        privateDirectory(context),
        externalDirectory(context)
    )

    /** Path a PC can read over USB (`Android/data/<pkg>/files/crash-logs`), or null when absent. */
    fun externalDirectory(context: Context): File? {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return null
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir.takeIf { it.isDirectory }
    }

    /** Paths offered to the user and to support: internal first, then the PC-readable copy. */
    fun describe(context: Context): String = directories(context)
        .joinToString("\n") { dir ->
            val label = if (dir.absolutePath.contains("/emulated/0/")) "（可用电脑直接打开）" else "（应用内部）"
            "${dir.absolutePath} $label"
        }

    fun reports(context: Context): List<File> =
        directories(context)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { FILE_NAME_PATTERN.matches(it.name) }
            .distinctBy { it.name }
            .sortedByDescending { it.name }

    /** Records this launch; a value >= [SAFE_MODE_THRESHOLD] means repeated startup failures. */
    fun beginStartup(context: Context): Int = StartupAttempts.begin(File(context.filesDir, ATTEMPTS_FILE))

    /** The dashboard is up, so the launch counter can be reset. */
    fun markStartupHealthy(context: Context) {
        StartupAttempts.reset(File(context.filesDir, ATTEMPTS_FILE))
    }

    fun consecutiveFailedStartups(context: Context): Int =
        StartupAttempts.read(File(context.filesDir, ATTEMPTS_FILE))

    fun latest(context: Context): File? = reports(context).firstOrNull()

    fun readLatest(context: Context): String? =
        latest(context)?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    fun clear(context: Context) {
        directories(context).forEach { dir ->
            dir.listFiles()?.filter { FILE_NAME_PATTERN.matches(it.name) }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun privateDirectory(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir.takeIf { it.isDirectory }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun deleteOutdated(dir: File) {
        val names = dir.listFiles()?.map { it.name }.orEmpty()
        CrashReportFormat.outdatedReports(names, MAX_REPORTS).forEach { name ->
            runCatching { File(dir, name).delete() }
        }
    }

    private fun header(context: Context, thread: Thread): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        appendLine("time: $now")
        appendLine("app: ${context.packageName} ${versionName(context)}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("thread: ${thread.name}")
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionName ?: "unknown"
    }.recoverCatching {
        if (it is PackageManager.NameNotFoundException) "unknown" else throw it
    }.getOrDefault("unknown")
}

/** Pure formatting/rotation helpers, kept Android-free so they stay unit-testable on the JVM. */
/**
 * Launch-attempt bookkeeping kept next to the reports; a plain file is used instead of
 * SharedPreferences so a corrupted preference file cannot itself break the guard.
 */
internal object StartupAttempts {

    fun begin(file: File): Int {
        val next = (read(file) + 1).coerceAtMost(99)
        runCatching { file.writeText(next.toString(), Charsets.UTF_8) }
        return next
    }

    fun reset(file: File) {
        runCatching { file.writeText("0", Charsets.UTF_8) }
    }

    fun read(file: File): Int =
        runCatching { file.readText(Charsets.UTF_8).trim().toInt() }.getOrDefault(0)
}

internal object CrashReportFormat {

    fun format(header: String, throwable: Throwable): String = buildString {
        append(header)
        appendLine("exception: ${throwable.javaClass.name}: ${throwable.message}")
        appendLine()
        append(stackTrace(throwable))
    }

    fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString()
    }

    /** File names are timestamp-prefixed, so lexicographic order matches chronological order. */
    fun outdatedReports(names: List<String>, keep: Int): List<String> {
        if (keep <= 1) return names.sorted().dropLast(keep.coerceAtLeast(0))
        return names.sorted().dropLast(keep)
    }
}
