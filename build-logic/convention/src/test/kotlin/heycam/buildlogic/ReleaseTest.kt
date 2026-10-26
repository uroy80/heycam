package heycam.buildlogic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseTest {
    @Test
    fun localBuildsUseDevVersions() {
        assertThat(AppVersion.code(null)).isEqualTo(1)
        assertThat(AppVersion.name(null)).isEqualTo("0.0.0-dev")
    }

    @Test
    fun ciVersionsArePassedThrough() {
        assertThat(AppVersion.code("1030100")).isEqualTo(1_030_100)
        assertThat(AppVersion.code("2100000000")).isEqualTo(AppVersion.MAX_CODE)
        assertThat(AppVersion.name("1.3.1")).isEqualTo("1.3.1")
    }

    @Test
    fun badVersionsFailTheBuild() {
        listOf("0", "-5", "2100000001", "1.3.1", "").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { AppVersion.code(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppVersion.name(" ") }
    }

    @Test
    fun noKeyMeansUnsigned() {
        assertThat(UploadKey.fromEnvironment { null }).isNull()
        assertThat(UploadKey.fromEnvironment { "" }).isNull()
    }

    @Test
    fun keyPasswordDefaultsToTheStorePassword() {
        val env = mapOf(
            UploadKey.FILE to "/keys/heycam-upload.p12",
            UploadKey.PASSWORD to "store-secret",
            UploadKey.ALIAS to "upload",
        )
        val key = UploadKey.fromEnvironment(env::get)!!
        assertThat(key.storeFile).isEqualTo("/keys/heycam-upload.p12")
        assertThat(key.alias).isEqualTo("upload")
        assertThat(key.keyPassword).isEqualTo("store-secret")
        assertThat(key.toString()).doesNotContain("secret")

        val withKeyPassword = UploadKey.fromEnvironment((env + (UploadKey.KEY_PASSWORD to "key-secret"))::get)!!
        assertThat(withKeyPassword.keyPassword).isEqualTo("key-secret")
    }

    @Test
    fun aPartialKeyFailsLoudly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UploadKey.fromEnvironment(mapOf(UploadKey.FILE to "/keys/heycam-upload.p12")::get)
        }
        assertThat(error).hasMessageThat().contains(UploadKey.PASSWORD)
        assertThat(error).hasMessageThat().contains(UploadKey.ALIAS)
        assertThat(error).hasMessageThat().doesNotContain(UploadKey.FILE)
    }
}
