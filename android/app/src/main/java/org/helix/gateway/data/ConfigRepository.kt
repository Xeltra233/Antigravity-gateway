package org.helix.gateway.data

import org.helix.gateway.util.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

class ConfigRepository(
    private val storageFile: File,
    private val cryptoProvider: CryptoProvider
) {
    private var inMemoryConfig: GatewayConfig? = null

    /** Set when a save could not be persisted (for example a device Keystore failure). */
    @Volatile
    var lastSaveError: Throwable? = null
        private set
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val DEFAULT_CONFIG_FILE_NAME = "gateway_config.json"

        fun generateDownstreamKey(): String {
            val randomBytes = ByteArray(24)
            SecureRandom().nextBytes(randomBytes)
            val hex = randomBytes.joinToString("") { "%02x".format(it) }
            return "sk-agw-$hex"
        }
    }

    @Synchronized
    fun load(): GatewayConfig {
        inMemoryConfig?.let { return it }

        if (!storageFile.exists() || storageFile.length() == 0L) {
            val defaultConfig = createInitialConfig()
            save(defaultConfig)
            inMemoryConfig = defaultConfig
            return defaultConfig
        }

        try {
            val jsonStr = storageFile.readText(Charsets.UTF_8)
            val root = JSONObject(jsonStr)
            val version = root.optInt("schemaVersion", 1)
            val currentId = root.optString("currentProviderId", "")
            val downstreamCipher = root.optString("downstreamKeyCiphertext", "")
            val downstreamKey = if (downstreamCipher.isNotEmpty()) {
                cryptoProvider.decrypt(downstreamCipher)
            } else {
                generateDownstreamKey()
            }
            val rawPort = root.optInt("port", NetworkUtils.GATEWAY_PORT)
            val port = if (rawPort in 1..65535) rawPort else NetworkUtils.GATEWAY_PORT
            val guard = readGuard(root.optJSONObject("guard"))

            val providersArray = root.optJSONArray("providers") ?: JSONArray()
            val providersList = mutableListOf<Provider>()
            for (i in 0 until providersArray.length()) {
                val item = providersArray.getJSONObject(i)
                val id = item.optString("id", UUID.randomUUID().toString())
                val name = item.optString("name", "供应商 ${i + 1}")
                val url = item.optString("upstreamUrl", "")
                val keyCipher = item.optString("upstreamKeyCiphertext", "")
                val key = if (keyCipher.isNotEmpty()) cryptoProvider.decrypt(keyCipher) else ""
                providersList.add(Provider(id = id, name = name, upstreamUrl = url, upstreamKey = key))
            }

            if (providersList.isEmpty()) {
                providersList.add(Provider(name = "供应商 1"))
            }

            val finalCurrentId = if (providersList.any { it.id == currentId }) {
                currentId
            } else {
                providersList.first().id
            }

            val loadedConfig = GatewayConfig(
                schemaVersion = version,
                currentProviderId = finalCurrentId,
                downstreamKey = downstreamKey,
                port = port,
                providers = providersList,
                guard = guard
            )
            inMemoryConfig = loadedConfig
            return loadedConfig
        } catch (e: Exception) {
            // If corrupted, create fallback and backup corrupted file
            val backupFile = File(storageFile.parentFile, "gateway_config_corrupted_${System.currentTimeMillis()}.bak")
            try {
                storageFile.copyTo(backupFile, overwrite = true)
            } catch (_: Exception) {}

            val fallback = createInitialConfig()
            save(fallback)
            inMemoryConfig = fallback
            return fallback
        }
    }

    private fun createInitialConfig(): GatewayConfig {
        val initialProvider = Provider(
            id = UUID.randomUUID().toString(),
            name = "供应商 1",
            upstreamUrl = "",
            upstreamKey = ""
        )
        return GatewayConfig(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            currentProviderId = initialProvider.id,
            downstreamKey = generateDownstreamKey(),
            port = NetworkUtils.GATEWAY_PORT,
            providers = listOf(initialProvider)
        )
    }

    @Synchronized
    fun save(config: GatewayConfig): Boolean {
        // Encryption depends on the device Keystore, which can fail on some OEM ROMs. Keep the
        // change usable in memory instead of taking the process down.
        return try {
            val root = JSONObject()
            root.put("schemaVersion", config.schemaVersion)
            root.put("currentProviderId", config.currentProviderId)
            root.put("downstreamKeyCiphertext", cryptoProvider.encrypt(config.downstreamKey))
            root.put("port", config.port)
            root.put("guard", writeGuard(config.guard))

            val array = JSONArray()
            for (p in config.providers) {
                val pObj = JSONObject()
                pObj.put("id", p.id)
                pObj.put("name", p.name)
                pObj.put("upstreamUrl", p.upstreamUrl.trim())
                pObj.put("upstreamKeyCiphertext", cryptoProvider.encrypt(p.upstreamKey))
                array.put(pObj)
            }
            root.put("providers", array)

            val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tempFile.writeText(root.toString(2), Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    storageFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (storageFile.exists()) {
                    storageFile.delete()
                }
                tempFile.renameTo(storageFile)
            }
            inMemoryConfig = config
            lastSaveError = null
            true
        } catch (e: Exception) {
            inMemoryConfig = config
            lastSaveError = e
            false
        }
    }

    @Synchronized
    fun updateCurrentProviderDraft(upstreamUrl: String, upstreamKey: String): GatewayConfig {
        val current = load()
        val updatedProviders = current.providers.map {
            if (it.id == current.currentProviderId) {
                it.copy(upstreamUrl = upstreamUrl.trim(), upstreamKey = upstreamKey)
            } else {
                it
            }
        }
        val updated = current.copy(providers = updatedProviders)
        save(updated)
        return updated
    }

    /** Writes the upstream pair for one provider, whichever row the detail page was opened from. */
    @Synchronized
    fun updateProviderUpstream(providerId: String, upstreamUrl: String, upstreamKey: String): GatewayConfig {
        val current = load()
        if (current.providers.none { it.id == providerId }) return current
        val updatedProviders = current.providers.map {
            if (it.id == providerId) {
                it.copy(upstreamUrl = upstreamUrl.trim(), upstreamKey = upstreamKey.trim())
            } else {
                it
            }
        }
        val updated = current.copy(providers = updatedProviders)
        save(updated)
        return updated
    }
    @Synchronized
    fun selectProvider(providerId: String): GatewayConfig {
        val current = load()
        if (current.providers.none { it.id == providerId }) return current
        val updated = current.copy(currentProviderId = providerId)
        save(updated)
        return updated
    }

    @Synchronized
    fun createProvider(name: String, upstreamUrl: String = "", upstreamKey: String = ""): Pair<GatewayConfig, Provider> {
        val current = load()
        val actualName = name.trim().ifEmpty {
            "供应商 ${current.providers.size + 1}"
        }
        val newProvider = Provider(
            id = UUID.randomUUID().toString(),
            name = actualName,
            upstreamUrl = upstreamUrl.trim(),
            upstreamKey = upstreamKey
        )
        val updatedList = current.providers + newProvider
        val updated = current.copy(
            currentProviderId = newProvider.id,
            providers = updatedList
        )
        save(updated)
        return Pair(updated, newProvider)
    }

    @Synchronized
    fun renameProvider(providerId: String, newName: String): GatewayConfig {
        val current = load()
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return current
        val updatedList = current.providers.map {
            if (it.id == providerId) {
                it.copy(name = trimmed)
            } else {
                it
            }
        }
        val updated = current.copy(providers = updatedList)
        save(updated)
        return updated
    }

    @Synchronized
    fun deleteProvider(providerId: String): GatewayConfig {
        val current = load()
        if (current.providers.size <= 1) {
            // Cannot delete the last provider
            return current
        }
        val updatedList = current.providers.filter { it.id != providerId }
        val newCurrentId = if (current.currentProviderId == providerId) {
            updatedList.first().id
        } else {
            current.currentProviderId
        }
        val updated = current.copy(
            currentProviderId = newCurrentId,
            providers = updatedList
        )
        save(updated)
        return updated
    }

    /**
     * Persists the order a drag produced. Ids that no longer exist are ignored and providers missing
     * from [orderedIds] keep their previous relative position at the end, so a stale adapter can
     * never drop a provider.
     */
    @Synchronized
    fun reorderProviders(orderedIds: List<String>): GatewayConfig {
        val current = load()
        val visibleIds = orderedIds.toSet()
        val byId = current.providers.associateBy { it.id }
        val incoming = orderedIds.mapNotNull { byId[it] }
        if (incoming.isEmpty()) return current
        // Only the rows the list actually shows take a new order. The provider that is kept out of
        // the list (the one in use) keeps its slot, so a drag can never shuffle it to the bottom.
        val reordered = ArrayList<Provider>(current.providers.size)
        var next = 0
        for (provider in current.providers) {
            if (provider.id in visibleIds && next < incoming.size) {
                reordered.add(incoming[next])
                next++
            } else {
                reordered.add(provider)
            }
        }
        if (reordered.map { it.id } == current.providers.map { it.id }) return current
        val updated = current.copy(providers = reordered)
        save(updated)
        return updated
    }

    @Synchronized
    fun updatePort(port: Int): GatewayConfig {
        val current = load()
        val validPort = if (port in 1..65535) port else NetworkUtils.GATEWAY_PORT
        val updated = current.copy(port = validPort)
        save(updated)
        return updated
    }

    @Synchronized
    fun regenerateDownstreamKey(): GatewayConfig {
        val current = load()
        val newKey = generateDownstreamKey()
        val updated = current.copy(downstreamKey = newKey)
        save(updated)
        return updated
    }

    @Synchronized
    fun updateGuard(guard: GuardSettings): GatewayConfig {
        val current = load()
        val updated = current.copy(guard = guard)
        save(updated)
        return updated
    }

    /** Reads the guard block. A config file written before schema 2 has none, so defaults apply. */
    private fun readGuard(obj: JSONObject?): GuardSettings {
        if (obj == null) return GuardSettings()
        return GuardSettings(
            antiRouteLevel = AntiRouteLevel.fromName(obj.optString("antiRouteLevel")),
            wireEscape = obj.optBoolean("wireEscape", true),
            scrub = obj.optBoolean("scrub", true),
            split = obj.optBoolean("split", false),
            scrubScope = AntiRouteScrubScope.fromName(obj.optString("scrubScope")),
            demotePrefill = obj.optBoolean("demotePrefill", false),
            separator = AntiRouteSeparator.fromName(obj.optString("separator")),
            modelReport = ModelReportMode.fromName(obj.optString("modelReport")),
            modelReportKey = obj.optString("modelReportKey").trim()
                .ifEmpty { GuardSettings.DEFAULT_MODEL_REPORT_KEY }
        )
    }

    private fun writeGuard(guard: GuardSettings): JSONObject {
        val obj = JSONObject()
        obj.put("antiRouteLevel", guard.antiRouteLevel.wireName)
        obj.put("wireEscape", guard.wireEscape)
        obj.put("scrub", guard.scrub)
        obj.put("split", guard.split)
        obj.put("scrubScope", guard.scrubScope.wireName)
        obj.put("demotePrefill", guard.demotePrefill)
        obj.put("separator", guard.separator.wireName)
        obj.put("modelReport", guard.modelReport.wireName)
        obj.put("modelReportKey", guard.modelReportKey.trim().ifEmpty { GuardSettings.DEFAULT_MODEL_REPORT_KEY })
        return obj
    }
}
