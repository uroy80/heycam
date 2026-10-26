package heycam.feature.scan.entry

import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImportTarget
import heycam.core.model.PageId
import org.junit.Test

class ScanTargetsTest {
    @Test
    fun everyTargetRoundTrips() {
        listOf(
            ImportTarget.NewDocument(),
            ImportTarget.NewDocument(title = "Lease / flat 4B", folderId = FolderId(3)),
            ImportTarget.AppendTo(DocumentId(12)),
            ImportTarget.AppendTo(DocumentId(12), atIndex = 4),
            ImportTarget.Replace(PageId(99)),
        ).forEach { target ->
            assertThat(ScanTargets.decode(ScanTargets.encode(target))).isEqualTo(target)
        }
    }

    @Test
    fun garbageDecodesToNull() {
        assertThat(ScanTargets.decode(null)).isNull()
        assertThat(ScanTargets.decode("not json")).isNull()
        assertThat(ScanTargets.decode("""{"kind":"append"}""")).isNull()
        assertThat(ScanTargets.decode("""{"kind":"teleport"}""")).isNull()
    }
}
