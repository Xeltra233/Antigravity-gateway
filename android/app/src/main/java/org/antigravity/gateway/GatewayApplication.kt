package org.antigravity.gateway

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import org.antigravity.gateway.data.ThemePreferences
import org.antigravity.gateway.util.CrashReporter

/**
 * Applies the persisted theme mode before any Activity is created so cold starts render with the
 * correct day/night resources, and installs the crash recorder so launch failures on OEM ROMs
 * leave a readable stack trace behind.
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
        // Never let a preference/theme failure take the whole process down at startup.
        runCatching {
            AppCompatDelegate.setDefaultNightMode(ThemePreferences(this).mode.delegateMode)
        }
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
    }
}
