package org.helix.gateway

import org.helix.gateway.data.AntiRouteLevel
import org.helix.gateway.data.AntiRouteScrubScope
import org.helix.gateway.data.AntiRouteSeparator
import org.helix.gateway.data.ConfigRepository
import org.helix.gateway.data.GuardSettings
import org.helix.gateway.data.ModelReportMode
import org.helix.gateway.data.CryptoProvider
import org.helix.gateway.data.JvmSoftwareCryptoProvider
import org.helix.gateway.data.Provider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var configFile: File
    private lateinit var cryptoProvider: JvmSoftwareCryptoProvider
    private lateinit var repository: ConfigRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "agw_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        configFile = File(tempDir, "config.json")
        cryptoProvider = JvmSoftwareCryptoProvider()
        repository = ConfigRepository(configFile, cryptoProvider)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testInitialConfigCreation() {
        val config = repository.load()
        assertNotNull(config)
        // A fresh install ships no provider: the list is the user's, not a placeholder.
        assertTrue(config.providers.isEmpty())
        assertEquals("", config.currentProviderId)
        assertTrue(config.downstreamKey.startsWith("sk-agw-"))
        assertTrue(configFile.exists())
    }

    @Test
    fun testCryptoProviderEncryptionAndDecryption() {
        val plain = "sk-test-secret-upstream-key-12345"
        val cipher = cryptoProvider.encrypt(plain)
        assertNotEquals(plain, cipher)
        val decrypted = cryptoProvider.decrypt(cipher)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testProviderSwitchingAndDrafts() {
        // 1. Fresh install, then the first provider the user adds
        val (initial, p1) = repository.createProvider("供应商 1")
        val initialDownstreamKey = initial.downstreamKey

        // 2. Update provider 1 draft
        repository.updateCurrentProviderDraft("https://api.openai.com", "sk-openai-key")
        val updated1 = repository.load()
        assertEquals("https://api.openai.com", updated1.getCurrentProvider()?.upstreamUrl)
        assertEquals("sk-openai-key", updated1.getCurrentProvider()?.upstreamKey)
        assertEquals(initialDownstreamKey, updated1.downstreamKey)

        // 3. Create provider 2
        val (configWithP2, p2) = repository.createProvider("Anthropic Claude", "https://api.anthropic.com", "sk-claude-key")
        assertEquals(2, configWithP2.providers.size)
        assertEquals(p2.id, configWithP2.currentProviderId)
        assertEquals("Anthropic Claude", configWithP2.getCurrentProvider()?.name)
        assertEquals("https://api.anthropic.com", configWithP2.getCurrentProvider()?.upstreamUrl)
        // Downstream key remains unchanged!
        assertEquals(initialDownstreamKey, configWithP2.downstreamKey)

        // 4. Switch back to provider 1
        val p1Id = p1.id
        val switchedBack = repository.selectProvider(p1Id)
        assertEquals(p1Id, switchedBack.currentProviderId)
        assertEquals("供应商 1", switchedBack.getCurrentProvider()?.name)
        assertEquals("https://api.openai.com", switchedBack.getCurrentProvider()?.upstreamUrl)
        assertEquals("sk-openai-key", switchedBack.getCurrentProvider()?.upstreamKey)
        // Downstream key still unchanged!
        assertEquals(initialDownstreamKey, switchedBack.downstreamKey)
    }

    @Test
    fun testDownstreamKeyRegenerationDoesNotAffectProviders() {
        repository.createProvider("供应商 1")
        repository.updateCurrentProviderDraft("https://api.openai.com", "sk-openai-key")
        val before = repository.load()
        val oldDownstreamKey = before.downstreamKey

        val after = repository.regenerateDownstreamKey()
        assertNotEquals(oldDownstreamKey, after.downstreamKey)
        assertTrue(after.downstreamKey.startsWith("sk-agw-"))

        // Providers are completely unaffected
        assertEquals(before.currentProviderId, after.currentProviderId)
        assertEquals("https://api.openai.com", after.getCurrentProvider()?.upstreamUrl)
        assertEquals("sk-openai-key", after.getCurrentProvider()?.upstreamKey)
    }

    @Test
    fun testDeleteProviderCanEmptyTheList() {
        val (_, p1) = repository.createProvider("Provider 1")
        val (_, p2) = repository.createProvider("Provider 2")
        val (c3, p3) = repository.createProvider("Provider 3")
        assertEquals(3, c3.providers.size)

        val afterDeleteP2 = repository.deleteProvider(p2.id)
        assertEquals(listOf(p1.id, p3.id), afterDeleteP2.providers.map { it.id })

        val afterDeleteP3 = repository.deleteProvider(p3.id)
        assertEquals(listOf(p1.id), afterDeleteP3.providers.map { it.id })

        // The last row may go too: an empty list is a state the user can reach, and it survives a
        // reload instead of being repopulated behind their back.
        val empty = repository.deleteProvider(p1.id)
        assertTrue(empty.providers.isEmpty())
        assertEquals("", empty.currentProviderId)
        assertTrue(ConfigRepository(configFile, cryptoProvider).load().providers.isEmpty())
    }

    @Test
    fun testRenameProvider() {
        val p1 = repository.createProvider("供应商 1").second
        assertEquals("供应商 1", p1.name)

        val renamed = repository.renameProvider(p1.id, "OpenAI-Official")
        assertEquals("OpenAI-Official", renamed.getCurrentProvider()?.name)

        // Re-load from persistence to ensure saved properly
        val reloaded = repository.load()
        assertEquals("OpenAI-Official", reloaded.getCurrentProvider()?.name)
    }

    @Test
    fun testPortPersistenceAndValidation() {
        val initial = repository.load()
        assertEquals(38472, initial.port)

        // Update to valid custom port
        val updated = repository.updatePort(39123)
        assertEquals(39123, updated.port)

        // Create a new repo instance pointing to same file to verify persistence
        val repo2 = ConfigRepository(configFile, cryptoProvider)
        val reloaded = repo2.load()
        assertEquals(39123, reloaded.port)

        // Update to invalid port falls back to default 38472
        val fallback = repository.updatePort(-5)
        assertEquals(38472, fallback.port)
    }

    @Test
    fun testGuardDefaultsAndPersistence() {
        val initial = repository.load()
        assertEquals(2, initial.schemaVersion)
        assertEquals(AntiRouteLevel.WIRE, initial.guard.antiRouteLevel)
        assertTrue(initial.guard.wireEscape)
        assertTrue(initial.guard.scrub)
        assertFalse(initial.guard.split)
        assertEquals(AntiRouteScrubScope.LEADING, initial.guard.scrubScope)
        assertFalse(initial.guard.demotePrefill)
        assertEquals(AntiRouteSeparator.HYPHEN, initial.guard.separator)
        assertEquals(ModelReportMode.HEADERS, initial.guard.modelReport)
        assertEquals("helix_model", initial.guard.modelReportKey)

        val updated = repository.updateGuard(
            GuardSettings(
                antiRouteLevel = AntiRouteLevel.DEEP,
                wireEscape = true,
                scrub = true,
                split = true,
                scrubScope = AntiRouteScrubScope.ALL,
                demotePrefill = true,
                separator = AntiRouteSeparator.WORD_JOINER,
                modelReport = ModelReportMode.BOTH,
                modelReportKey = "served_model"
            )
        )
        assertEquals(AntiRouteLevel.DEEP, updated.guard.antiRouteLevel)

        val reloaded = ConfigRepository(configFile, cryptoProvider).load()
        assertEquals(2, reloaded.schemaVersion)
        assertEquals(AntiRouteLevel.DEEP, reloaded.guard.antiRouteLevel)
        assertEquals(AntiRouteScrubScope.ALL, reloaded.guard.scrubScope)
        assertEquals(AntiRouteSeparator.WORD_JOINER, reloaded.guard.separator)
        assertEquals(ModelReportMode.BOTH, reloaded.guard.modelReport)
        assertEquals("served_model", reloaded.guard.modelReportKey)
        assertTrue(reloaded.guard.split)
        assertTrue(reloaded.guard.demotePrefill)
    }

    @Test
    fun testLegacyConfigWithoutGuardFallsBackToDefaults() {
        // A file written by an older build carries no guard block and no schema 2 marker.
        val legacy = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"currentProviderId\": \"p1\",\n")
            append("  \"downstreamKeyCiphertext\": \"").append(cryptoProvider.encrypt("sk-agw-legacy")).append("\",\n")
            append("  \"port\": 39444,\n")
            append("  \"providers\": [\n")
            append("    {\"id\": \"p1\", \"name\": \"旧供应商\", \"upstreamUrl\": \"https://legacy.example\", ")
            append("\"upstreamKeyCiphertext\": \"").append(cryptoProvider.encrypt("sk-legacy")).append("\"}\n")
            append("  ]\n")
            append("}\n")
        }
        configFile.writeText(legacy)

        val loaded = ConfigRepository(configFile, cryptoProvider).load()
        assertEquals(39444, loaded.port)
        assertEquals("旧供应商", loaded.getCurrentProvider()?.name)
        assertEquals("sk-legacy", loaded.getCurrentProvider()?.upstreamKey)
        assertEquals(AntiRouteLevel.WIRE, loaded.guard.antiRouteLevel)
        assertEquals(ModelReportMode.HEADERS, loaded.guard.modelReport)
        assertEquals("helix_model", loaded.guard.modelReportKey)
    }

    @Test
    fun testCorruptedFileRecovery() {
        configFile.writeText("{ corrupted_json !!!")
        val recovered = repository.load()
        assertNotNull(recovered)
        assertTrue(recovered.providers.isEmpty())
        assertTrue(recovered.downstreamKey.startsWith("sk-agw-"))
    }

    @Test
    fun testKeystoreFailureDoesNotThrowAndKeepsConfigUsable() {
        // Devices with a broken/blocked Keystore must not crash: the config stays usable in memory.
        val failing = ConfigRepository(configFile, ThrowingCryptoProvider())

        val config = failing.load()
        assertTrue(config.providers.isEmpty())
        assertNotNull("save failure should be recorded", failing.lastSaveError)
        assertFalse(failing.save(config))
        assertFalse("no partial file should be left behind", configFile.exists())

        val updated = failing.updatePort(39005)
        assertEquals(39005, updated.port)
        assertNotNull("still failing to persist, but never throwing", failing.lastSaveError)
    }

    @Test
    fun testUpgradeKeepsConfiguredProviders() {
        // What an in-place upgrade over a filled-in install must never do: drop providers, rename
        // them, or lose the upstream keys that are only stored encrypted.
        val first = Provider(name = "公司网关", upstreamUrl = "https://a.example/v1", upstreamKey = "sk-aaa")
        val second = Provider(name = "备用", upstreamUrl = "https://b.example/v1", upstreamKey = "sk-bbb")
        repository.save(
            repository.load().copy(providers = listOf(first, second), currentProviderId = second.id)
        )

        val reopened = ConfigRepository(configFile, cryptoProvider).load()

        assertEquals(listOf("公司网关", "备用"), reopened.providers.map { it.name })
        assertEquals(
            listOf("https://a.example/v1", "https://b.example/v1"),
            reopened.providers.map { it.upstreamUrl }
        )
        assertEquals(listOf("sk-aaa", "sk-bbb"), reopened.providers.map { it.upstreamKey })
        assertEquals(second.id, reopened.currentProviderId)
    }

    @Test
    fun testEmptyProviderListStaysEmpty() {
        // Clearing the list is a decision, not damage: no launch may invent a row, and the file must
        // keep the empty array as it was written.
        repository.save(repository.load().copy(providers = emptyList(), currentProviderId = ""))

        val reopened = ConfigRepository(configFile, cryptoProvider).load()

        assertTrue(reopened.providers.isEmpty())
        assertEquals("", reopened.currentProviderId)
        assertEquals(
            "the file is not rewritten with a placeholder",
            0,
            JSONObject(configFile.readText()).getJSONArray("providers").length()
        )
    }

    @Test
    fun testUnreadableFileIsKeptForRecovery() {
        // Refuse to clobber a config we could not parse: on an upgrade that file still holds the
        // provider URLs and the downstream key, and the backup is then the only copy left.
        val broken = "{ this is not json"
        configFile.writeText(broken)

        val loaded = ConfigRepository(configFile, cryptoProvider).load()

        assertTrue("the app stays usable", loaded.providers.isEmpty())
        assertEquals("the original file is left alone", broken, configFile.readText())
        val backup = File(tempDir, ConfigRepository.CORRUPTED_BACKUP_NAME)
        assertTrue("the unreadable file is kept", backup.exists())
        assertEquals(broken, backup.readText())
    }

    private class ThrowingCryptoProvider : CryptoProvider {
        override fun encrypt(plainText: String): String =
            throw IllegalStateException("keystore unavailable")

        override fun decrypt(cipherText: String): String =
            throw IllegalStateException("keystore unavailable")
    }
}
