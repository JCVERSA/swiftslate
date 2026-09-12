package com.jcversa.swiftslate.ui.processtext

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the ProcessTextActivity intent filters in AndroidManifest.xml.
 *
 * The activity carries two filters: ACTION_PROCESS_TEXT (which NEEDS category DEFAULT
 * for implicit-intent resolution) and a VIEW/BROWSABLE/https package-visibility
 * workaround (which must NOT have DEFAULT, or SwiftSlate becomes an https link
 * handler). Both properties were previously verified by hand only; this test pins them
 * so a manifest edit cannot silently break either. It fails — rather than skips — if
 * the manifest cannot be found.
 */
class ProcessTextManifestTest {

    @Test
    fun processTextFilter_resolvableAndViewFilter_notALinkHandler() {
        val activity = findProcessTextActivity(locateManifest())
        assertTrue("ProcessTextActivity not found in AndroidManifest.xml", activity != null)
        // Explicit return over a non-null assertion; unreachable when the assert passes.
        activity ?: return

        val filters = activity.childNodes.let { children ->
            (0 until children.length)
                .mapNotNull { children.item(it) as? Element }
                .filter { it.tagName == "intent-filter" }
        }
        assertTrue(
            "ProcessTextActivity has no intent-filters — the manifest structure changed",
            filters.isNotEmpty()
        )

        var processTextFilterSeen = false
        var viewFilterSeen = false
        filters.forEach { filter ->
            val children = filter.childNodes.let { nodes ->
                (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            }
            val actions = children
                .filter { it.tagName == "action" }
                .map { it.getAttribute("android:name") }
                .toSet()
            val categories = children
                .filter { it.tagName == "category" }
                .map { it.getAttribute("android:name") }
                .toSet()

            if ("android.intent.action.PROCESS_TEXT" in actions) {
                processTextFilterSeen = true
                assertTrue(
                    "PROCESS_TEXT filter lost category DEFAULT — implicit intents will no " +
                        "longer resolve and SwiftSlate will vanish from selection menus",
                    "android.intent.category.DEFAULT" in categories
                )
            }
            if ("android.intent.action.VIEW" in actions) {
                viewFilterSeen = true
                assertTrue(
                    "VIEW filter gained category DEFAULT — SwiftSlate would appear as an " +
                        "https link handler",
                    "android.intent.category.DEFAULT" !in categories
                )
            }
        }
        assertTrue(
            "PROCESS_TEXT filter missing — the manifest structure changed",
            processTextFilterSeen
        )
        // If the visibility workaround is ever deliberately removed, delete this assertion
        // with it; until then its absence means someone broke it by accident.
        assertTrue(
            "VIEW/BROWSABLE package-visibility filter missing — the manifest structure changed",
            viewFilterSeen
        )
    }

    private fun locateManifest(): File {
        // Elvis over !!: explicit failure with context if the property is ever absent.
        val userDir = System.getProperty("user.dir")
            ?: throw AssertionError("user.dir system property is not set")
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/AndroidManifest.xml")
            if (direct.isFile) return direct
            val nested = File(dir, "app/src/main/AndroidManifest.xml")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate src/main/AndroidManifest.xml from working directory " +
                System.getProperty("user.dir")
        )
    }

    private fun findProcessTextActivity(manifest: File): Element? {
        val factory = DocumentBuilderFactory.newInstance()
        // The manifest is repo-controlled, but there is no reason to allow doctypes at all.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as? Element ?: continue
            if (activity.getAttribute("android:name").endsWith("ProcessTextActivity")) {
                return activity
            }
        }
        return null
    }
}
