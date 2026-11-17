package org.helix.gateway

import android.app.Application
import android.util.Log
import org.helix.gateway.data.SkinPreferences
import org.helix.gateway.util.CrashReporter

/**
 * Installs the crash recorder so launch failures on OEM ROMs leave a readable stack trace behind.
 * The skin itself is applied per Activity, because each skin is a separate fixed theme rather than
 * a system night-mode toggle.
 */
class GatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // A launch that dies before the UI appears cannot be recovered from inside the app, so a
        // repeated startup crash falls back to safe mode: optional initialisation is skipped and
        // the stored reports stay reachable.
        startupAttempts = CrashReporter.beginStartup(this)
        safeMode = startupAttempts >= CrashReporter.SAFE_MODE_THRESHOLD
        if (safeMode) {
            Log.w(TAG, "safe mode: $startupAttempts startup attempts without a healthy UI")
            return
        }
        // Touch the stored skin once at startup so a corrupted preference is replaced by the
        // default instead of surfacing later during Activity creation.
        runCatching { SkinPreferences(this).skin }
    }

    companion object {
        private const val TAG = "GatewayApplication"

        /** Consecutive launch attempts recorded for this process, kept for the safe-mode banner. */
        @Volatile
        var startupAttempts: Int = 0
            private set

        /** True when the previous launches kept dying before the dashboard became usable. */
        @Volatile
        var safeMode: Boolean = false
            private set

        /**
         * Clears the safe-mode state once a launch proved healthy. [CrashReporter.markStartupHealthy]
         * only rewrites the counter file; these fields were read in [onCreate] and would otherwise
         * keep reporting the pre-reset count for the rest of the process.
         */
        fun clearSafeMode() {
            startupAttempts = 0
            safeMode = false
        }
    }
}
