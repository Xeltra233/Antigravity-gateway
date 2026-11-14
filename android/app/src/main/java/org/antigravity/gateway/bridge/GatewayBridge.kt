package org.antigravity.gateway.bridge

import org.antigravity.gateway.data.GuardSettings

object GatewayBridge {
    private var isLoaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("antigravity")
            isLoaded = true
        } catch (t: Throwable) {
            loadError = t
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded
    fun getLoadError(): Throwable? = loadError

    @Synchronized
    fun startGateway(
        upstreamUrl: String,
        upstreamKey: String,
        downstreamKey: String,
        port: Int = 38472,
        guard: GuardSettings = GuardSettings()
    ): Result<Unit> {
        if (!isLoaded) {
            return Result.failure(IllegalStateException("Native library not loaded: ${loadError?.message}"))
        }
        val code = nativeStartGateway(
            upstreamUrl,
            upstreamKey,
            downstreamKey,
            port,
            guard.antiRouteLevel.wireName,
            guard.wireEscape,
            guard.scrub,
            guard.split,
            guard.scrubScope.wireName,
            guard.demotePrefill,
            guard.separator.wireName,
            guard.modelReport.wireName,
            guard.modelReportKey
        )
        return if (code == 0) {
            Result.success(Unit)
        } else {
            val err = nativeGetLastError()
            Result.failure(RuntimeException(if (err.isNullOrEmpty()) "Failed to start gateway (code $code)" else err))
        }
    }

    @Synchronized
    fun stopGateway(): Result<Unit> {
        if (!isLoaded) {
            return Result.failure(IllegalStateException("Native library not loaded: ${loadError?.message}"))
        }
        val code = nativeStopGateway()
        return if (code == 0) {
            Result.success(Unit)
        } else {
            val err = nativeGetLastError()
            Result.failure(RuntimeException(if (err.isNullOrEmpty()) "Failed to stop gateway (code $code)" else err))
        }
    }

    @Synchronized
    fun isRunning(): Boolean {
        if (!isLoaded) return false
        return nativeIsRunning()
    }

    @Synchronized
    fun getLastError(): String {
        if (!isLoaded) return loadError?.message ?: "Library not loaded"
        return nativeGetLastError() ?: ""
    }

    @JvmStatic
    private external fun nativeStartGateway(
        upstreamUrl: String,
        upstreamKey: String,
        downstreamKey: String,
        port: Int,
        antiRouteLevel: String,
        wireEscape: Boolean,
        scrub: Boolean,
        split: Boolean,
        scrubScope: String,
        demotePrefill: Boolean,
        separator: String,
        modelReport: String,
        modelReportKey: String
    ): Int

    @JvmStatic
    private external fun nativeStopGateway(): Int

    @JvmStatic
    private external fun nativeIsRunning(): Boolean

    @JvmStatic
    private external fun nativeGetLastError(): String?
}
