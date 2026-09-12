package com.jcversa.swiftslate.api

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Architectural guard for the cleartext policy.
 *
 * The network security config permits cleartext platform-wide (it cannot express IP
 * ranges), so the http://-private-LAN-only rule lives entirely in [EndpointValidator],
 * enforced at connection time by [ApiConnections]. That only holds while every request
 * path funnels through it — a single direct `URL(...).openConnection()` anywhere else
 * silently bypasses the policy. This test fails the build if one appears.
 *
 * No sockets are opened; the main source tree is scanned as text. The source root must
 * be found or the test FAILS rather than skips — a silently-skipped guard is worse than
 * none (see the KeyCipher KDoc for the precedent that motivated this).
 */
class ApiConnectionsChokePointTest {

    @Test
    fun allConnections_openThroughApiConnections() {
        val srcRoot = locateMainSourceRoot()
        var chokePointSeen = false
        val offenders = mutableListOf<String>()
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                if (file.name == "ApiConnections.kt") {
                    chokePointSeen = true
                    return@forEach
                }
                val hit = file.readLines().any { line ->
                    val trimmed = line.trimStart()
                    ".openConnection()" in line &&
                        !trimmed.startsWith("//") &&
                        !trimmed.startsWith("*") &&
                        !trimmed.startsWith("/*")
                }
                if (hit) offenders.add(file.relativeTo(srcRoot).path)
            }
        // Sanity: proves the scan looked at the real tree instead of passing vacuously.
        assertTrue(
            "ApiConnections.kt not found under $srcRoot — the scan root is wrong",
            chokePointSeen
        )
        if (offenders.isNotEmpty()) {
            fail(
                "These files open connections directly instead of via ApiConnections " +
                    "(cleartext-policy bypass risk): $offenders"
            )
        }
    }

    /**
     * Locates `src/main/java`. Unit tests run with the working directory at the module
     * (`app/`), but probing upward also covers a repo-root working directory and IDE runs.
     */
    private fun locateMainSourceRoot(): File {
        // Elvis over !!: explicit failure with context if the property is ever absent.
        val userDir = System.getProperty("user.dir")
            ?: throw AssertionError("user.dir system property is not set")
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/java")
            if (direct.isDirectory) return direct
            val nested = File(dir, "app/src/main/java")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate src/main/java from working directory " +
                System.getProperty("user.dir")
        )
    }
}
