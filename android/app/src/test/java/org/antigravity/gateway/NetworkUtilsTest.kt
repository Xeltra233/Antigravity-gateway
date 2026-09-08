package org.antigravity.gateway

import org.antigravity.gateway.util.NetworkUtils
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class NetworkUtilsTest {

    @Test
    fun testPortConstant() {
        assertEquals(38472, NetworkUtils.GATEWAY_PORT)
    }

    @Test
    fun testBuildAppLink() {
        val linkLocal = NetworkUtils.buildAppLink("127.0.0.1", 38472)
        assertEquals("http://127.0.0.1:38472/v1", linkLocal)

        val linkLan = NetworkUtils.buildAppLink("192.168.1.100", 38472)
        assertEquals("http://192.168.1.100:38472/v1", linkLan)

        val linkCustom = NetworkUtils.buildAppLink("127.0.0.1", 39000)
        assertEquals("http://127.0.0.1:39000/v1", linkCustom)
    }

    @Test
    fun testIsPortAvailable() {
        // 1. Invalid port range
        assertFalse(NetworkUtils.isPortAvailable(0))
        assertFalse(NetworkUtils.isPortAvailable(-1))
        assertFalse(NetworkUtils.isPortAvailable(65536))

        // 2. Dynamically allocate a free port
        val testServer = ServerSocket(0)
        val boundPort = testServer.localPort
        assertTrue(boundPort > 0)

        // When occupied, isPortAvailable should detect conflict and return false
        assertFalse("Occupied port should be detected as unavailable", NetworkUtils.isPortAvailable(boundPort))

        // Close the socket
        testServer.close()

        // Now the port is freed, isPortAvailable should return true
        assertTrue("Freed port should be available", NetworkUtils.isPortAvailable(boundPort))
    }

    @Test
    fun testGetLocalIpAddressNotNull() {
        val ip = NetworkUtils.getLocalIpAddress()
        assertNotNull(ip)
        assertTrue(ip.isNotEmpty())
    }
}
