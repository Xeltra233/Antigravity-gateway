package org.antigravity.gateway

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.antigravity.gateway.data.ThemePreferences

/**
 * Applies the persisted theme mode before any Activity is created so cold starts render with the
 * correct day/night resources.
 */
class GatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(ThemePreferences(this).mode.delegateMode)
    }
}
