package heycam.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchActionTest {
    private fun image(n: Int) = Uri.parse("content://com.example.photos/image/$n")

    @Test
    fun shortcutAndTileActions() {
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_SCAN))).isEqualTo(LaunchAction.Scan)
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_IMPORT))).isEqualTo(LaunchAction.Import)
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_SEARCH))).isEqualTo(LaunchAction.Search)
    }

    @Test
    fun plainLaunchesAreNotActions() {
        assertThat(LaunchAction.from(null)).isNull()
        assertThat(LaunchAction.from(Intent(Intent.ACTION_MAIN))).isNull()
        assertThat(LaunchAction.from(Intent(Intent.ACTION_VIEW, image(1)))).isNull()
        assertThat(LaunchAction.from(Intent(Intent.ACTION_SEND).setType("image/jpeg"))).isNull()
    }

    @Test
    fun oneSharedImage() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, image(1))
        assertThat(LaunchAction.from(intent)).isEqualTo(LaunchAction.Share(listOf(image(1)), skipped = 0))
    }

    @Test
    fun streamsAndClipDataAreMergedOnce() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image(1), image(2)))
        intent.clipData = ClipData.newRawUri(null, image(2)).apply { addItem(ClipData.Item(image(3))) }
        assertThat(LaunchAction.from(intent)).isEqualTo(LaunchAction.Share(listOf(image(1), image(2), image(3)), skipped = 0))
    }

    @Test
    fun fileUrisAreNeverRead() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("file:///sdcard/DCIM/a.jpg"), image(1)))
        assertThat(LaunchAction.from(intent)).isEqualTo(LaunchAction.Share(listOf(image(1)), skipped = 1))
    }

    /** Android 9-11 finish the task's root on Back; reopening it from Recents replays the intent that started it. */
    @Test
    fun reopeningFromRecentsRunsNothingAgain() {
        val fromHistory = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_SCAN).addFlags(fromHistory))).isNull()
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_IMPORT).addFlags(fromHistory))).isNull()
        val share = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, image(1))
        assertThat(LaunchAction.from(share.addFlags(fromHistory))).isNull()
        assertThat(LaunchAction.from(Intent(LaunchAction.ACTION_SCAN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .isEqualTo(LaunchAction.Scan)
    }

    @Test
    fun aNullEntryIsNotAShare() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri?>(null))
        assertThat(LaunchAction.from(intent)).isNull()
    }

    @Test
    fun entriesThatAreNotUrisAreLeftOut() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Parcelable>(image(1), Bundle()))
        assertThat(LaunchAction.from(intent)).isEqualTo(LaunchAction.Share(listOf(image(1)), skipped = 0))

        val single = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Bundle())
        assertThat(LaunchAction.from(single)).isNull()
    }

    @Test
    fun aSingleUriWhereAListBelongsIsNotAShare() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putExtra(Intent.EXTRA_STREAM, image(1))
        assertThat(LaunchAction.from(intent)).isNull()
    }

    /** Android 12 and older unparcel all extras at once and throw on a class heycam cannot load. */
    @Test
    fun unreadableExtrasStillLeaveTheClipData() {
        val intent = UnreadableExtras(Intent.ACTION_SEND_MULTIPLE)
        assertThat(LaunchAction.from(intent)).isNull()
        intent.clipData = ClipData.newRawUri(null, image(2))
        assertThat(LaunchAction.from(intent)).isEqualTo(LaunchAction.Share(listOf(image(2)), skipped = 0))
        assertThat(LaunchAction.from(UnreadableExtras(Intent.ACTION_SEND))).isNull()
    }

    private class UnreadableExtras(action: String) : Intent(action) {
        override fun <T> getParcelableExtra(name: String?, clazz: Class<T>): T? = unreadable()

        override fun <T> getParcelableArrayListExtra(name: String?, clazz: Class<out T>): ArrayList<T>? = unreadable()

        private fun unreadable(): Nothing = throw BadParcelableException("ClassNotFoundException when unmarshalling")
    }

    @Test
    fun atMostOneHundredImages() {
        val uris = ArrayList((1..130).map(::image))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val share = LaunchAction.from(intent) as LaunchAction.Share
        assertThat(share.images).isEqualTo(uris.take(LaunchAction.MAX_SHARED_IMAGES))
        assertThat(share.skipped).isEqualTo(30)
    }
}
