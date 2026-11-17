package org.helix.gateway.data

import org.helix.gateway.util.NetworkUtils
import java.util.UUID

data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val upstreamUrl: String = "",
    val upstreamKey: String = ""
)

/** Outbound request guard strength. */
enum class AntiRouteLevel(val wireName: String) {
    OFF("off"),
    WIRE("wire"),
    STANDARD("standard"),
    DEEP("deep");

    companion object {
        fun fromName(name: String?): AntiRouteLevel =
            entries.firstOrNull { it.wireName == name?.trim()?.lowercase() } ?: WIRE
    }
}

/** Separator inserted into a masked term. */
enum class AntiRouteSeparator(val wireName: String) {
    HYPHEN("hyphen"),
    WORD_JOINER("wordjoiner");

    companion object {
        fun fromName(name: String?): AntiRouteSeparator =
            entries.firstOrNull { it.wireName == name?.trim()?.lowercase() } ?: HYPHEN
    }
}

/** Scope of the instruction-block scrub. */
enum class AntiRouteScrubScope(val wireName: String) {
    LEADING("leading"),
    ALL("all");

    companion object {
        fun fromName(name: String?): AntiRouteScrubScope =
            entries.firstOrNull { it.wireName == name?.trim()?.lowercase() } ?: LEADING
    }
}

/** How the resolved upstream model is reported back to the client. */
enum class ModelReportMode(val wireName: String) {
    OFF("off"),
    HEADERS("headers"),
    BODY("body"),
    BOTH("both");

    companion object {
        fun fromName(name: String?): ModelReportMode =
            entries.firstOrNull { it.wireName == name?.trim()?.lowercase() } ?: HEADERS
    }
}

/** Settings that used to live only in `.env`; exposed in the app so a phone can change them. */
data class GuardSettings(
    val antiRouteLevel: AntiRouteLevel = AntiRouteLevel.WIRE,
    val wireEscape: Boolean = true,
    val scrub: Boolean = true,
    val split: Boolean = false,
    val scrubScope: AntiRouteScrubScope = AntiRouteScrubScope.LEADING,
    val demotePrefill: Boolean = false,
    val separator: AntiRouteSeparator = AntiRouteSeparator.HYPHEN,
    val modelReport: ModelReportMode = ModelReportMode.HEADERS,
    val modelReportKey: String = DEFAULT_MODEL_REPORT_KEY
) {
    companion object {
        const val DEFAULT_MODEL_REPORT_KEY = "helix_model"
    }
}

data class GatewayConfig(
    val schemaVersion: Int = 1,
    val currentProviderId: String,
    val downstreamKey: String,
    val port: Int = NetworkUtils.GATEWAY_PORT,
    val providers: List<Provider>,
    val guard: GuardSettings = GuardSettings()
) {
    fun getCurrentProvider(): Provider? {
        return providers.firstOrNull { it.id == currentProviderId } ?: providers.firstOrNull()
    }
}