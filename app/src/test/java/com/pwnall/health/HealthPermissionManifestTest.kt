package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Health Connect permission that is asked for but not declared in the manifest can never be
 * granted, and the app then looks unconnected forever. That is invisible until someone installs it.
 *
 * The reverse is a Play rejection: a declared permission nothing asks for reads as excessive data
 * access under the Minimum Scope policy.
 */
class HealthPermissionManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun theManifestIsWhereTheTestThinksItIs() {
        assertTrue(manifest.contains("android.permission.health.WRITE_STEPS"))
    }

    @Test
    fun everyPermissionTheAppAsksForIsDeclared() {
        val undeclared = HEALTH_PERMISSIONS.filterNot { manifest.contains("\"$it\"") }.sorted()
        assertEquals(emptyList<String>(), undeclared)
    }

    @Test
    fun everyDeclaredHealthPermissionIsAskedFor() {
        val declared = Regex("\"(android\\.permission\\.health\\.[A-Z_]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
        val unused = declared.filterNot { it in HEALTH_PERMISSIONS }.toList().sorted()
        assertEquals(emptyList<String>(), unused)
    }
}
