package com.feerlessleadr.dockge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlTest {
    private fun ok(input: String, cleartext: Boolean = false) = BaseUrl.parse(input, cleartext).getOrThrow()

    @Test fun plainHttps() {
        val u = ok("https://dockge.example.com")
        assertEquals("https://dockge.example.com", u.value)
        assertEquals("https://dockge.example.com", u.origin)
        assertEquals("/socket.io/", u.socketPath)
    }

    @Test fun trailingSlashAndCaseAreNormalised() {
        assertEquals("https://dockge.example.com:8443", ok("  HTTPS://Dockge.Example.com:8443/ ").value)
    }

    @Test fun missingSchemeDefaultsToHttps() {
        assertEquals("https://dockge.example.com", ok("dockge.example.com").value)
    }

    @Test fun subPathBecomesSocketPrefix() {
        val u = ok("https://example.com/dockge/")
        assertEquals("https://example.com", u.origin)
        assertEquals("/dockge/socket.io/", u.socketPath)
    }

    @Test fun cleartextOnlyWhenAllowed() {
        assertTrue(BaseUrl.parse("http://dockge.example.com").isFailure)
        assertEquals("http://dockge.example.com", ok("http://dockge.example.com", cleartext = true).value)
    }

    @Test fun rejectsBadInput() {
        listOf(
            "", "   ", "ftp://example.com", "https://user:pw@example.com", "https://example.com/?a=1",
            "https://example.com/#x", "https://", "https://exa mple.com",
        ).forEach { assertTrue("should fail: '$it'", BaseUrl.parse(it).isFailure) }
    }
}
