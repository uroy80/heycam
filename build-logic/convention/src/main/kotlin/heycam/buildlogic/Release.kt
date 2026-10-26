package heycam.buildlogic

/**
 * Play version numbers come from Gradle properties that CI derives from the release tag with
 * scripts/version-from-tag.sh (v1.3.1 -> versionName 1.3.1, versionCode 1030100). Local builds use the defaults.
 */
object AppVersion {
    const val LOCAL_CODE = 1
    const val LOCAL_NAME = "0.0.0-dev"
    const val MAX_CODE = 2_100_000_000

    fun code(property: String?): Int {
        if (property == null) return LOCAL_CODE
        val code = property.toIntOrNull()
        require(code != null && code in 1..MAX_CODE) { "heycam.versionCode must be a whole number from 1 to $MAX_CODE, got '$property'" }
        return code
    }

    fun name(property: String?): String {
        if (property == null) return LOCAL_NAME
        require(property.isNotBlank()) { "heycam.versionName must not be blank" }
        return property
    }
}

/**
 * The upload key for signing release builds on a developer machine, read from HEYCAM_KEYSTORE_* environment
 * variables. Without them the release build is unsigned; CI signs the bundle in its approval-gated publish job.
 */
class UploadKey(val storeFile: String, val storePassword: String, val alias: String, val keyPassword: String) {
    override fun toString() = "UploadKey(storeFile=$storeFile, alias=$alias)"

    companion object {
        const val FILE = "HEYCAM_KEYSTORE_FILE"
        const val PASSWORD = "HEYCAM_KEYSTORE_PASSWORD"
        const val ALIAS = "HEYCAM_KEYSTORE_ALIAS"
        const val KEY_PASSWORD = "HEYCAM_KEYSTORE_KEY_PASSWORD"

        /** Null when none of the variables is set. The key password defaults to the store password (PKCS12). */
        fun fromEnvironment(env: (String) -> String?): UploadKey? {
            val values = listOf(FILE, PASSWORD, ALIAS, KEY_PASSWORD).associateWith { env(it)?.takeIf(String::isNotEmpty) }
            if (values.values.all { it == null }) return null
            val file = values[FILE]
            val password = values[PASSWORD]
            val alias = values[ALIAS]
            require(file != null && password != null && alias != null) {
                val missing = listOf(FILE, PASSWORD, ALIAS).filter { values[it] == null }
                "To sign release builds set ${missing.joinToString()} as well (see README)"
            }
            return UploadKey(storeFile = file, storePassword = password, alias = alias, keyPassword = values[KEY_PASSWORD] ?: password)
        }
    }
}
