package com.feerlessleadr.dockge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release workflow computes versionCode = major*10000 + minor*100 + patch. It must keep increasing. */
class VersionCodeTest {
    private fun code(v: String): Int {
        val (major, minor, patch) = v.split(".").map { it.toInt() }
        return major * 10000 + minor * 100 + patch
    }

    @Test fun formula() = assertEquals(10203, code("1.2.3"))

    @Test fun increasesAcrossReleases() {
        val codes = listOf("0.1.0", "0.1.1", "0.2.0", "0.10.0", "1.0.0", "1.0.1").map(::code)
        assertTrue(codes.zipWithNext().all { (a, b) -> b > a })
    }
}
