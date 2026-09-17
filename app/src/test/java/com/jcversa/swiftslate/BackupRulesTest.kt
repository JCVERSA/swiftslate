package com.jcversa.swiftslate

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps privacy-sensitive local stores out of both Android backup formats. The app also sets
 * allowBackup=false, but explicit exclusions remain important for OEM device-transfer behavior
 * and protect the data if backup policy changes in a future release.
 */
class BackupRulesTest {

    @Test
    fun backupPoliciesExcludeKeysConfigurationStatsAndOptInHistory() {
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { name ->
            val document = secureParser().parse(locateResource(name))
            val excludes = document.getElementsByTagName("exclude")
            val paths = (0 until excludes.length).mapNotNull { index ->
                (excludes.item(index) as? Element)?.let { element ->
                    element.getAttribute("domain") to element.getAttribute("path")
                }
            }.toSet()

            listOf(
                "secure_keys_prefs.xml",
                "settings.xml",
                "commands.xml",
                "stats.xml",
                "history.xml"
            ).forEach { store ->
                assertTrue(
                    "$name must exclude shared preferences store $store",
                    "sharedpref" to store in paths
                )
            }
        }
    }

    private fun secureParser(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun locateResource(name: String): File {
        val userDir = System.getProperty("user.dir")
            ?: throw AssertionError("user.dir system property is not set")
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/res/xml/$name")
            if (direct.isFile) return direct
            val nested = File(dir, "app/src/main/res/xml/$name")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $name from $userDir")
    }
}
