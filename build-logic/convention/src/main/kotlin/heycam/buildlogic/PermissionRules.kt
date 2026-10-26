package heycam.buildlogic

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The permission allowlist of PLAN §3.5: the merged release manifest may request only what
 * config/permissions-allowlist.txt lists. Anything new needs a Data safety review before it is added there.
 */
object PermissionRules {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APPLICATION_ID = "\${applicationId}"

    /** One permission per line; `#` starts a comment; `${applicationId}` stands for the variant's id. */
    fun parseAllowlist(text: String, applicationId: String): Set<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(APPLICATION_ID, applicationId) }
            .toSet()

    fun requestedPermissions(manifest: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val document = factory.newDocumentBuilder().parse(manifest)
        return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }.toSet()
    }

    fun unexpected(requested: Set<String>, allowed: Set<String>): Set<String> = (requested - allowed).toSortedSet()
}
