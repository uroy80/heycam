package heycam.core.export

import androidx.core.content.FileProvider

/** heycam's own FileProvider class, so it never collides with another library's provider entry in the manifest. */
class ExportFileProvider : FileProvider() {
    companion object {
        fun authority(packageName: String) = "$packageName.exports"
    }
}
