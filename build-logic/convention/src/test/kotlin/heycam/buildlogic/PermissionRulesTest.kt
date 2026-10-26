package heycam.buildlogic

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionRulesTest {
    @get:Rule val temp = TemporaryFolder()

    /** The real config/permissions-allowlist.txt (see build-logic/convention/build.gradle.kts). */
    private val repositoryAllowlist = File(System.getProperty("heycam.permissionsAllowlist"))

    private fun manifest(body: String): File = temp.newFile("AndroidManifest.xml").apply {
        writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="in.edgr.heycam">
            $body
                <application android:label="heycam" />
            </manifest>
            """.trimIndent(),
        )
    }

    @Test
    fun theMvpManifestPassesTheRepositoryAllowlist() {
        val requested = PermissionRules.requestedPermissions(
            manifest(
                """
                <permission android:name="in.edgr.heycam.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature" />
                <uses-permission android:name="in.edgr.heycam.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                <uses-permission android:name="android.permission.INTERNET" />
                """,
            ),
        )
        val allowed = PermissionRules.parseAllowlist(repositoryAllowlist.readText(), "in.edgr.heycam")
        assertThat(requested).hasSize(3)
        assertThat(PermissionRules.unexpected(requested, allowed)).isEmpty()
    }

    @Test
    fun anythingElseIsReported() {
        val requested = PermissionRules.requestedPermissions(
            manifest(
                """
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.CAMERA" />
                <uses-permission-sdk-23 android:name="android.permission.READ_MEDIA_IMAGES" />
                """,
            ),
        )
        val allowed = PermissionRules.parseAllowlist("android.permission.INTERNET\n", "in.edgr.heycam")
        assertThat(PermissionRules.unexpected(requested, allowed))
            .containsExactly("android.permission.CAMERA", "android.permission.READ_MEDIA_IMAGES").inOrder()
    }

    @Test
    fun allowlistSkipsCommentsAndExpandsTheApplicationId() {
        val allowed = PermissionRules.parseAllowlist(
            "# comment\n\nandroid.permission.INTERNET   # from ML Kit\n\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\n",
            "in.edgr.heycam",
        )
        assertThat(allowed).containsExactly(
            "android.permission.INTERNET",
            "in.edgr.heycam.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }

    @Test(expected = Exception::class)
    fun doctypesAreRefused() {
        PermissionRules.requestedPermissions(
            temp.newFile("evil.xml").apply {
                writeText("<?xml version=\"1.0\"?><!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><manifest>&x;</manifest>")
            },
        )
    }
}
