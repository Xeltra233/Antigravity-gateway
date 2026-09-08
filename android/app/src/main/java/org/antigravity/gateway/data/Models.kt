package org.antigravity.gateway.data

import org.antigravity.gateway.util.NetworkUtils
import java.util.UUID

data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val upstreamUrl: String = "",
    val upstreamKey: String = ""
)

data class GatewayConfig(
    val schemaVersion: Int = 1,
    val currentProviderId: String,
    val downstreamKey: String,
    val port: Int = NetworkUtils.GATEWAY_PORT,
    val providers: List<Provider>
) {
    fun getCurrentProvider(): Provider? {
        return providers.firstOrNull { it.id == currentProviderId } ?: providers.firstOrNull()
    }
}
