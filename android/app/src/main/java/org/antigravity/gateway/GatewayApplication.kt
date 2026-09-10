package org.antigravity.gateway

import android.app.Application
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
        // Never let a preference/theme failure take the whole process down at startup.
        runCatching {
            AppCompatDelegate.setDefaultNightMode(ThemePreferences(this).mode.delegateMode)
        }
    }
}
