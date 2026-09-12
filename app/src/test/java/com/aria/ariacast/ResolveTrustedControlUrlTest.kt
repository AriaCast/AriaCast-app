package com.aria.ariacast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DiscoveryManager.resolveTrustedControlUrl].
 */
class ResolveTrustedControlUrlTest {

    // --- Relative paths ---

    @Test
    fun `relative path with explicit port preserves port`() {
        val location = java.net.URI("http://192.168.1.50:49152/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "/AVTransport/control", location, "192.168.1.50"
        )
        assertEquals("http://192.168.1.50:49152/AVTransport/control", result)
    }

    @Test
    fun `relative path with default HTTP port omits port`() {
        // URI.getPort() returns -1 when no port is present in the string
        val location = java.net.URI("http://192.168.1.50/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "/AVTransport/control", location, "192.168.1.50"
        )
        assertEquals("http://192.168.1.50/AVTransport/control", result)
    }

    @Test
    fun `relative path without leading slash gets one inserted`() {
        val location = java.net.URI("http://192.168.1.50:8080/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "AVTransport/control", location, "192.168.1.50"
        )
        assertEquals("http://192.168.1.50:8080/AVTransport/control", result)
    }

    // --- Absolute URLs ---

    @Test
    fun `absolute URL matching expected host is returned as-is`() {
        val location = java.net.URI("http://192.168.1.50:49152/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "http://192.168.1.50:49152/AVTransport/control", location, "192.168.1.50"
        )
        assertEquals("http://192.168.1.50:49152/AVTransport/control", result)
    }

    @Test
    fun `absolute URL pointing to different host is rejected`() {
        val location = java.net.URI("http://192.168.1.50:49152/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "http://evil.example.com/steal", location, "192.168.1.50"
        )
        assertNull(result)
    }

    // --- Edge cases ---

    @Test
    fun `empty raw returns null`() {
        val location = java.net.URI("http://192.168.1.50:49152/description.xml")
        assertNull(DiscoveryManager.resolveTrustedControlUrl("", location, "192.168.1.50"))
    }

    @Test
    fun `malformed absolute URL returns null`() {
        val location = java.net.URI("http://192.168.1.50:49152/description.xml")
        assertNull(DiscoveryManager.resolveTrustedControlUrl("http://[bad", location, "192.168.1.50"))
    }

    @Test
    fun `HTTPS location with default port omits port`() {
        val location = java.net.URI("https://192.168.1.50/description.xml")
        val result = DiscoveryManager.resolveTrustedControlUrl(
            "/AVTransport/control", location, "192.168.1.50"
        )
        assertEquals("https://192.168.1.50/AVTransport/control", result)
    }
}
