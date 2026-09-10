package org.antigravity.gateway.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
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
 * Copies are attempted in three places: the private `files/crash-logs` directory, the app-external
 * one (`Android/data/<pkg>/files/crash-logs`, still browsable up to Android 10) and - on Android
 * 11+, where `Android/data` became unreadable - the public `Downloads/[PUBLIC_DIR_NAME]` folder,
 * which the built-in file manager and chat apps can open.
 */
object CrashReporter {

    const val DIR_NAME = "crash-logs"

    /** Folder inside the public Downloads directory, used from Android 11 on. */
    const val PUBLIC_DIR_NAME = "AntigravityGateway"
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
        exportToPublicDownloads(context, name, body)?.let { Log.i(TAG, "crash report exported to $it") }
        return first
    }

    /**
     * True when the report can also be dropped in the public Downloads folder. Android 11 locked
     * down `Android/data` for file managers and MTP, so the private/app-external copies alone are
     * not reachable for a normal user any more.
     */
    fun publicExportEnabled(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

    /** User-visible path of the exported copy, e.g. `下载/AntigravityGateway/crash-...txt`. */
    fun publicExportPath(name: String): String = "下载/$PUBLIC_DIR_NAME/$name"

    private fun exportToPublicDownloads(context: Context, name: String, body: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { exportViaMediaStore(context, name, body) }
            .onFailure { Log.e(TAG, "cannot export crash report to Downloads", it) }
            .getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(context: Context, name: String, body: String): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) } ?: return null
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        deleteOutdatedExports(resolver, collection)
        return publicExportPath(name)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteOutdatedExports(resolver: ContentResolver, collection: Uri) {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val entries = mutableListOf<Pair<Long, String>>()
        runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("$FILE_PREFIX%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) entries += cursor.getLong(0) to (cursor.getString(1) ?: "")
            }
        }.onFailure { Log.e(TAG, "cannot list exported crash reports", it) }
        entries.filter { FILE_NAME_PATTERN.matches(it.second) }
            .sortedByDescending { it.second }
            .drop(MAX_REPORTS)
            .forEach { (id, _) -> runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) } }
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

    /** User-facing locations, most accessible first. */
    fun describe(context: Context): String = buildList {
        if (publicExportEnabled(Build.VERSION.SDK_INT)) {
            add("下载/${PUBLIC_DIR_NAME}（文件管理器可直接打开）")
        }
        directories(context).forEach { dir ->
            val label = if (dir.absolutePath.contains("/emulated/0/")) "（电脑 USB/adb 可读）" else "（应用私有）"
            add("${dir.absolutePath} $label")
        }
    }.joinToString("\n")

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
        clearExports(context)
    }

    private fun clearExports(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("$FILE_PREFIX%")
            )
        }.onFailure { Log.e(TAG, "cannot clear exported crash reports", it) }
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

/** Pure formatting/rotation helpers, kept Android-free so they stay unit-testable on the JVM. */
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
